/*
 * This file is part of Partyflow.
 *
 * Partyflow is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * Partyflow is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public
 * License along with Partyflow.
 *
 * If not, see <https://www.gnu.org/licenses/>.
 */

package com.unascribed.partyflow.handler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.options.PutOptions;
import org.jsoup.Jsoup;
import org.jsoup.safety.Whitelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.overzealous.remark.Remark;
import com.unascribed.partyflow.MultipartData;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.SimpleHandler.UrlEncodedOrMultipartPost;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Ints;

public class ReleaseHandler extends SimpleHandler implements GetOrHead, UrlEncodedOrMultipartPost {

	private static final Logger log = LoggerFactory
			.getLogger(ReleaseHandler.class);

	private static final File WORK_DIR = new File(System.getProperty("java.io.tmpdir"), "partyflow/work");

	private static final Pattern PATH_PATTERN = Pattern.compile("^([^/]+)(/delete|/publish|/unpublish|/edit|/add-track)?$");
	private static final Pattern TITLE_FFPROBE_PATTERN = Pattern.compile("^format.tags.(?:title|TITLE)=\"?(.*?)\"?$", Pattern.MULTILINE);
	private static final Pattern TRACK_FFPROBE_PATTERN = Pattern.compile("^format.tags.(?:track|TRACK)=\"?(.*?)\"?$", Pattern.MULTILINE);

	private final Remark remark = new Remark(com.overzealous.remark.Options.github());

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		Matcher m = PATH_PATTERN.matcher(path);
		if (!m.matches()) return;
		Map<String, String> query = parseQuery(req);
		Session s = SessionHelper.getSession(req);
		if (m.group(2) == null) {
			try (Connection c = Partyflow.sql.getConnection()) {
				String slugs = m.group(1);
				String suffix = s == null ? "" : " OR releases.user_id = ?";
				try (PreparedStatement ps = c.prepareStatement(
						"SELECT release_id, title, subtitle, published, art, description, releases.user_id, users.display_name FROM releases "
						+ "JOIN users ON releases.user_id = users.user_id "
						+ "WHERE slug = ? AND (published = true"+suffix+");")) {
					ps.setString(1, slugs);
					if (s != null) {
						ps.setInt(2, s.userId);
					}
					try (ResultSet rs = ps.executeQuery()) {
						// slug is UNIQUE, we don't need to handle more than one row
						if (rs.first()) {
							List<Object> _tracks = Lists.newArrayList();
							try (PreparedStatement ps2 = c.prepareStatement(
									"SELECT title, subtitle, slug, art, track_number FROM tracks "
									+ "WHERE release_id = ? ORDER BY track_number ASC;")) {
								ps2.setInt(1, rs.getInt("release_id"));
								try (ResultSet rs2 = ps2.executeQuery()) {
									while (rs2.next()) {
										_tracks.add(new Object() {
											String title = rs2.getString("title");
											String subtitle = rs2.getString("subtitle");
											String slug = rs2.getString("slug");
											String art = rs2.getString("art") == null ? null : Partyflow.resolveArt(rs2.getString("art"));
											int track_number = rs2.getInt("track_number");
										});
									}
								}
							}
							res.setStatus(HTTP_200_OK);
							boolean _editable = s != null && rs.getInt("releases.user_id") == s.userId;
							String _descriptionMd;
							String desc = rs.getString("description");
							if (_editable) {
								_descriptionMd = remark.convert(desc);
							} else {
								_descriptionMd = null;
							}
							MustacheHandler.serveTemplate(req, res, "release.hbs.html", new Object() {
								String title = rs.getString("title");
								String subtitle = rs.getString("subtitle");
								String creator = rs.getString("users.display_name");
								String slug = slugs;
								boolean editable = _editable;
								boolean published = rs.getBoolean("published");
								String art = Partyflow.resolveArt(rs.getString("art"));
								String description = desc;
								String descriptionMd = _descriptionMd;
								String error = query.get("error");
								List<Object> tracks = _tracks;
								boolean has_tracks = !_tracks.isEmpty();
							});
						} else {
							res.sendError(HTTP_404_NOT_FOUND);
							return;
						}
					}
				}
			} catch (SQLException e) {
				throw new ServletException(e);
			}
		} else if ("/add-track".equals(m.group(2))) {
			if (s == null) {
				res.sendRedirect(Partyflow.config.http.path+"login?message=You must log in to do that.");
				return;
			}
			try (Connection c = Partyflow.sql.getConnection()) {
				try (PreparedStatement ps = c.prepareStatement(
						"SELECT title, subtitle, published, art FROM releases "
						+ "WHERE slug = ? AND user_id = ?;")) {
					ps.setString(1, m.group(1));
					ps.setInt(2, s.userId);
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.first()) {
							MustacheHandler.serveTemplate(req, res, "add-track.hbs.html", new Object() {
								Object release = new Object() {
									String slug = m.group(1);
									String title = rs.getString("title");
									String subtitle = rs.getString("subtitle");
									boolean published = rs.getBoolean("published");
									String art = Partyflow.resolveArt(rs.getString("art"));
									String error = query.get("error");
								};
							});
						} else {
							res.sendError(HTTP_404_NOT_FOUND);
						}
					}
				}
			} catch (SQLException e) {
				throw new ServletException(e);
			}
		} else {
			res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
		}
	}

	@Override
	public void urlEncodedPost(String path, HttpServletRequest req, HttpServletResponse res, Map<String, String> params) throws IOException, ServletException {
		Matcher m = PATH_PATTERN.matcher(path);
		if (!m.matches()) return;
		if ("/delete".equals(m.group(2))) {
			Session s = SessionHelper.getSession(req);
			if (s == null) {
				res.sendRedirect(Partyflow.config.http.path+"login?message=You must log in to do that.");
				return;
			}
			String csrf = params.get("csrf");
			if (!Partyflow.isCsrfTokenValid(s, csrf)) {
				res.sendRedirect(Partyflow.config.http.path);
				return;
			}
			try (Connection c = Partyflow.sql.getConnection()) {
				String slugs = m.group(1);
				int releaseId;
				try (PreparedStatement ps = c.prepareStatement("SELECT release_id, art FROM releases WHERE slug = ? AND user_id = ?;")) {
					ps.setString(1, slugs);
					ps.setInt(2, s.userId);
					try (ResultSet rs = ps.executeQuery()) {
						// slug is UNIQUE, we don't need to handle more than one row
						if (rs.first()) {
							releaseId = rs.getInt("release_id");
							String art = Strings.emptyToNull(rs.getString("art"));
							if (art != null) {
								Partyflow.storage.removeBlob(Partyflow.storageContainer, art);
							}
						} else {
							res.sendError(HTTP_404_NOT_FOUND);
							return;
						}
					}
				}
				// TODO delete transcodes and masters
				try (PreparedStatement ps = c.prepareStatement("DELETE FROM releases WHERE release_id = ?; DELETE FROM tracks WHERE release_id = ?;")) {
					ps.setInt(1, releaseId);
					ps.setInt(2, releaseId);
					ps.executeUpdate();
					res.sendRedirect(Partyflow.config.http.path+"releases");
				}
			} catch (SQLException e) {
				throw new ServletException(e);
			}
		} else if ("/publish".equals(m.group(2)) || "/unpublish".equals(m.group(2))) {
			Session s = SessionHelper.getSession(req);
			if (s == null) {
				res.sendRedirect(Partyflow.config.http.path+"login?message=You must log in to do that.");
				return;
			}
			String csrf = params.get("csrf");
			if (!Partyflow.isCsrfTokenValid(s, csrf)) {
				res.sendRedirect(Partyflow.config.http.path);
				return;
			}
			boolean published = "/publish".equals(m.group(2));
			try (Connection c = Partyflow.sql.getConnection()) {
				String slug = m.group(1);
				String midfix = published ? ", published_at = NOW()" : "";
				try (PreparedStatement ps = c.prepareStatement("UPDATE releases SET published = ?"+midfix+" WHERE slug = ? AND user_id = ?;")) {
					ps.setBoolean(1, published);
					ps.setString(2, slug);
					ps.setInt(3, s.userId);
					if (ps.executeUpdate() >= 1) {
						res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(slug));
					} else {
						res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(slug)+"?error=You're not allowed to do that");
					}
				}
			} catch (SQLException e) {
				throw new ServletException(e);
			}
		} else if ("/edit".equals(m.group(2)) || "/add-track".equals(m.group(2))) {
			res.sendError(HTTP_415_UNSUPPORTED_MEDIA_TYPE);
		} else {
			res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
		}
	}

	@Override
	public void multipartPost(String path, HttpServletRequest req, HttpServletResponse res, MultipartData data)
			throws IOException, ServletException {
		Matcher m = PATH_PATTERN.matcher(path);
		if (!m.matches()) return;
		if ("/edit".equals(m.group(2))) {
			Session s = SessionHelper.getSession(req);
			if (s == null) {
				res.sendRedirect(Partyflow.config.http.path+"login?message=You must log in to do that.");
				return;
			}
			String csrf = data.getPartAsString("csrf", 64);
			if (!Partyflow.isCsrfTokenValid(s, csrf)) {
				res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(m.group(1))+"?error=Invalid CSRF token");
				return;
			}
			Part art = data.getPart("art");
			String title = Strings.nullToEmpty(data.getPartAsString("title", 1024));
			String subtitle = Strings.nullToEmpty(data.getPartAsString("subtitle", 1024));
			String descriptionMd = data.getPartAsString("descriptionMd", 65536);
			String description;
			if (descriptionMd != null) {
				Parser parser = Parser.builder().build();
				HtmlRenderer rend = HtmlRenderer.builder().build();
				description = sanitizeHtml(rend.render(parser.parse(descriptionMd)));
			} else {
				description = sanitizeHtml(Strings.nullToEmpty(data.getPartAsString("description", 65536)));
			}
			if (title.trim().isEmpty()) {
				res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(m.group(1))+"?error=Title is required");
				return;
			}
			if (title.length() > 255) {
				res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(m.group(1))+"?error=Title is too long");
				return;
			}
			if (subtitle.length() > 255) {
				res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(m.group(1))+"?error=Subtitle is too long");
				return;
			}
			if (description.length() > 16384) {
				res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(m.group(1))+"?error=Description is too long");
				return;
			}
			String artPath = null;
			if (art != null && art.getSize() > 4) {
				try {
					artPath = CreateReleaseHandler.processArt(art);
				} catch (IllegalArgumentException e) {
					res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(m.group(1))+"?error="+URLEncoder.encode(e.getMessage(), "UTF-8"));
					return;
				}
			}
			try (Connection c = Partyflow.sql.getConnection()) {
				boolean published;
				try (PreparedStatement ps = c.prepareStatement(
						"SELECT published FROM releases "
						+ "WHERE slug = ? AND user_id = ?;")) {
					ps.setString(1, m.group(1));
					ps.setInt(2, s.userId);
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.first()) {
							published = rs.getBoolean("published");
						} else {
							res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(m.group(1))+"?error=You're not allowed to do that");
							return;
						}
					}
				}
				String slug = published ? m.group(1) : Partyflow.sanitizeSlug(title);
				if (!Objects.equal(slug, m.group(1))) {
					try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM releases WHERE slug = ?;")) {
						int i = 0;
						String suffix = "";
						while (true) {
							if (i > 0) {
								suffix = "-"+(i+1);
							}
							ps.setString(1, slug+suffix);
							try (ResultSet rs = ps.executeQuery()) {
								if (!rs.first()) break;
							}
							i++;
						}
						slug = slug+suffix;
					}
				}
				String midfix = data.getPart("publish") != null ? ", published = true, published_at = NOW()" : "";
				String extraCols = artPath != null ? "art = ?," : "";
				try (PreparedStatement ps = c.prepareStatement(
						"UPDATE releases SET title = ?, subtitle = ?, slug = ?, "+extraCols+" description = ?, last_updated = NOW()"+midfix
						+ " WHERE slug = ? AND user_id = ?;")) {
					int i = 1;
					ps.setString(i++, title);
					ps.setString(i++, subtitle);
					ps.setString(i++, slug);
					if (artPath != null) {
						ps.setString(i++, artPath);
					}
					ps.setString(i++, description);
					ps.setString(i++, m.group(1));
					ps.setInt(i++, s.userId);
					ps.executeUpdate();
				}
				if (data.getPart("addTrack") != null) {
					res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(slug)+"/add-track");
				} else {
					res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(slug));
				}
			} catch (SQLException e) {
				throw new ServletException(e);
			}
		} else if ("/add-track".equals(m.group(2))) {
			Session s = SessionHelper.getSession(req);
			if (s == null) {
				res.sendRedirect(Partyflow.config.http.path+"login?message=You must log in to do that.");
				return;
			}
			String csrf = data.getPartAsString("csrf", 64);
			if (!Partyflow.isCsrfTokenValid(s, csrf)) {
				res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(m.group(1))+"/add-track?error=Invalid CSRF token");
				return;
			}
			List<Part> masters = data.getAllParts("master");
			if (masters.isEmpty()) {
				res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(m.group(1))+"/add-track?error=At least one master is required");
				return;
			}
			String lastSlug = null;
			boolean committed = false;
			try (Connection c = Partyflow.sql.getConnection()) {
				try (Statement st = c.createStatement()) {
					st.execute("BEGIN TRANSACTION;");
				}
				int releaseId;
				try (PreparedStatement ps = c.prepareStatement("SELECT release_id FROM releases WHERE slug = ? AND user_id = ?;")) {
					ps.setString(1, m.group(1));
					ps.setInt(2, s.userId);
					try (ResultSet rs = ps.executeQuery()) {
						// slug is UNIQUE, we don't need to handle more than one row
						if (rs.first()) {
							releaseId = rs.getInt("release_id");
						} else {
							res.sendError(HTTP_404_NOT_FOUND);
							return;
						}
					}
				}
				WORK_DIR.mkdirs();
				for (Part master : masters) {
					File tmpFile = File.createTempFile("transcode-", ".flac", WORK_DIR);
					try {
						log.debug("Transcoding master to 16-bit 48kHz FLAC\n"
								+ "(more than enough for anybody: https://people.xiph.org/~xiphmont/demo/neil-young.html)");
						Process p = Partyflow.ffmpeg("-v", "error",
								"-i", "-",
								"-map", "a",
								"-af", "aresample=osf=s16:osr=48000:dither_method=improved_e_weighted:filter_type=kaiser",
								"-map_metadata", "-1",
								"-metadata", "comment=Generated by Partyflow v"+Version.FULL+" hosted at "+Partyflow.publicUri.getHost(),
								"-f", "flac",
								"-y", tmpFile.getAbsolutePath());
						try (InputStream in = master.getInputStream()) {
							ByteStreams.copy(in, p.getOutputStream());
							p.getOutputStream().close();
						} catch (IOException e) {
							if (!"Broken pipe".equals(e.getMessage())) {
								throw e;
							}
						}
						while (p.isAlive()) {
							try {
								p.waitFor();
							} catch (InterruptedException e) {
							}
						}
						if (p.exitValue() != 0) {
							String str = new String(ByteStreams.toByteArray(p.getErrorStream()), Charsets.UTF_8);
							log.warn("Failed to process audio with FFmpeg:\n{}", str);
							throw new ServletException("Failed to transcode; FFmpeg exited with code "+p.exitValue());
						}
						log.debug("Transcode complete");
						String blobName;
						do {
							String rand = Partyflow.randomString(16);
							blobName = "masters/"+rand.substring(0, 3)+"/"+rand+".flac";
						} while (Partyflow.storage.blobExists(Partyflow.storageContainer, blobName));
						String sfm = master.getSubmittedFileName();
						if (sfm.contains(".")) {
							sfm = sfm.substring(0, sfm.lastIndexOf('.'))+".flac";
						}
						String filename = TranscodeHandler.encodeFilename(sfm);
						Blob blob = Partyflow.storage.blobBuilder(blobName)
								.payload(tmpFile)
								.cacheControl("private")
								.contentLength(tmpFile.length())
								.contentDisposition("attachment; filename="+filename+"; filename*=utf-8''"+filename)
								.contentType("audio/flac")
								.build();
						Partyflow.storage.putBlob(Partyflow.storageContainer, blob, new PutOptions().multipart().setBlobAccess(BlobAccess.PRIVATE));
						String titleFromFilename = sfm.contains(".") ? sfm.substring(0, sfm.lastIndexOf('.')) : sfm;
						Process probe = Partyflow.ffprobe("-v", "error", "-print_format", "flat", "-show_format", "-");
						try (InputStream in = master.getInputStream()) {
							ByteStreams.copy(in, probe.getOutputStream());
							probe.getOutputStream().close();
						} catch (IOException e) {
							if (!"Broken pipe".equals(e.getMessage())) {
								throw e;
							}
						}
						while (probe.isAlive()) {
							try {
								probe.waitFor();
							} catch (InterruptedException e) {
							}
						}
						String title;
						int trackNumber = -1;
						if (probe.exitValue() != 0) {
							String str = new String(ByteStreams.toByteArray(probe.getErrorStream()), Charsets.UTF_8);
							log.warn("Failed to probe master with FFprobe:\n{}", str);
							title = titleFromFilename;
						} else {
							String probeOut = new String(ByteStreams.toByteArray(probe.getInputStream()), Charsets.UTF_8);
							Matcher probeTitleM = TITLE_FFPROBE_PATTERN.matcher(probeOut);
							if (probeTitleM.find()) {
								title = probeTitleM.group(1);
							} else {
								title = titleFromFilename;
							}
							Matcher probeTrackM = TRACK_FFPROBE_PATTERN.matcher(probeOut);
							if (probeTrackM.find()) {
								String trackStr = probeTrackM.group(1);
								Integer trackI = Ints.tryParse(trackStr);
								if (trackI != null && trackI >= 1) {
									trackNumber = trackI;
								}
							}
						}
						if (trackNumber == -1) {
							try (PreparedStatement ps = c.prepareStatement("SELECT MAX(track_number)+1 AS next FROM tracks WHERE release_id = ?;")) {
								ps.setInt(1, releaseId);
								try (ResultSet rs = ps.executeQuery()) {
									if (rs.first()) {
										if (rs.getObject("next") == null) {
											trackNumber = 1;
										} else {
											trackNumber = rs.getInt("next");
										}
									} else {
										trackNumber = 1;
									}
								}
							}
						}
						String slug = Partyflow.sanitizeSlug(title);
						try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM tracks WHERE slug = ? AND release_id = ?;")) {
							ps.setInt(2, releaseId);
							int i = 0;
							String suffix = "";
							while (true) {
								if (i > 0) {
									suffix = "-"+(i+1);
								}
								ps.setString(1, slug+suffix);
								try (ResultSet rs = ps.executeQuery()) {
									if (!rs.first()) break;
								}
								i++;
							}
							slug = slug+suffix;
						}
						lastSlug = slug;
						try (PreparedStatement ps = c.prepareStatement(
								"INSERT INTO tracks (release_id, title, subtitle, slug, master, description, track_number, created_at, last_updated) "
								+ "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW());")) {
							ps.setInt(1, releaseId);
							ps.setString(2, title);
							ps.setString(3, "");
							ps.setString(4, slug);
							ps.setString(5, blobName);
							ps.setString(6, "");
							ps.setInt(7, trackNumber);
							ps.execute();
						}
					} finally {
						tmpFile.delete();
					}
				}
				try (Statement st = c.createStatement()) {
					st.execute("COMMIT;");
					committed = true;
				}
			} catch (SQLException e) {
				throw new ServletException(e);
			} finally {
				if (!committed) {
					try (Connection c = Partyflow.sql.getConnection()) {
						try (Statement st = c.createStatement()) {
							st.execute("ROLLBACK;");
						}
					} catch (SQLException ignore) {}
				}
			}
			if (masters.size() == 1) {
				res.sendRedirect(Partyflow.config.http.path+"track/"+escPathSeg(lastSlug));
			} else {
				res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(m.group(1)));
			}
		} else if ("/delete".equals(m.group(2))) {
			res.sendError(HTTP_415_UNSUPPORTED_MEDIA_TYPE);
		} else {
			res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
		}
	}

	private String sanitizeHtml(String html) {
		return Jsoup.clean(html, Whitelist.relaxed().removeTags("img"));
	}

}
