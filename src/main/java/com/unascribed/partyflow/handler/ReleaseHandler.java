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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.options.PutOptions;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.overzealous.remark.Remark;
import com.unascribed.partyflow.MultipartData;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.SimpleHandler.UrlEncodedOrMultipartPost;
import com.unascribed.partyflow.ThreadPools;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;

public class ReleaseHandler extends SimpleHandler implements GetOrHead, UrlEncodedOrMultipartPost {

	private static final Logger log = LoggerFactory.getLogger(ReleaseHandler.class);
	private static final Gson gson = new Gson();

	private static final File WORK_DIR = new File(System.getProperty("java.io.tmpdir"), "partyflow/work");

	private static final Pattern PATH_PATTERN = Pattern.compile("^([^/]+)(/delete|/publish|/unpublish|/edit|/add-track)?$");
	private static final Pattern DURATION_FFPROBE_PATTERN = Pattern.compile("^format.duration=\"?(.*?)\"?$", Pattern.MULTILINE);
	private static final Pattern TITLE_FFPROBE_PATTERN = Pattern.compile("^format.tags.(?:title|TITLE)=\"?(.*?)\"?$", Pattern.MULTILINE);
	private static final Pattern TRACK_FFPROBE_PATTERN = Pattern.compile("^format.tags.(?:track|TRACK)=\"?(.*?)\"?$", Pattern.MULTILINE);
	private static final Pattern LYRICS_FFPROBE_PATTERN = Pattern.compile("^format.tags.(?:unsynced|UNSYNCED)?(?:lyrics|LYRICS)=\"?(.*?)\"?$", Pattern.MULTILINE);
	
	private static final Pattern LOUDNESS_FFMPEG_PATTERN = Pattern.compile("^\\s+I:\\s+(-?\\d+\\.\\d+) LUFS$", Pattern.MULTILINE);
	private static final Pattern PEAK_FFMPEG_PATTERN = Pattern.compile("^\\s+Peak:\\s+(-?(?:\\d+\\.\\d+|inf)) dBFS$", Pattern.MULTILINE);
	
	private static final BigDecimal TO_SAMPLES = new BigDecimal(48000);

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
				String suffix = s == null ? "" : " OR `releases`.`user_id` = ?";
				try (PreparedStatement ps = c.prepareStatement(
						"SELECT `release_id`, `title`, `subtitle`, `published`, `art`, `description`, `loudness`, `releases`.`user_id`, `users`.`display_name`, `concat_master` FROM `releases` "
						+ "JOIN `users` ON `releases`.`user_id` = `users`.`user_id` "
						+ "WHERE `slug` = ? AND (`published` = true"+suffix+");")) {
					ps.setString(1, slugs);
					if (s != null) {
						ps.setInt(2, s.userId);
					}
					try (ResultSet rs = ps.executeQuery()) {
						// slug is UNIQUE, we don't need to handle more than one row
						if (rs.first()) {
							List<Object> _tracks = Lists.newArrayList();
							JsonArray _tracksJson = new JsonArray();
							try (PreparedStatement ps2 = c.prepareStatement(
									"SELECT `title`, `subtitle`, `slug`, `art`, `track_number`, `duration`, `lyrics` FROM `tracks` "
									+ "WHERE `release_id` = ? ORDER BY `track_number` ASC;")) {
								long durAccum = 0;
								ps2.setInt(1, rs.getInt("release_id"));
								try (ResultSet rs2 = ps2.executeQuery()) {
									while (rs2.next()) {
										String _art = rs2.getString("art") == null ? null : Partyflow.resolveArt(rs2.getString("art"));
										_tracks.add(new Object() {
											String title = rs2.getString("title");
											String subtitle = rs2.getString("subtitle");
											String slug = rs2.getString("slug");
											String art = _art;
											String lyrics = rs2.getString("lyrics");
											int track_number = rs2.getInt("track_number");
										});
										JsonObject obj = new JsonObject();
										obj.addProperty("title", rs2.getString("title"));
										obj.addProperty("subtitle", rs2.getString("subtitle"));
										obj.addProperty("slug", rs2.getString("slug"));
										obj.addProperty("art", _art);
										obj.addProperty("trackNumber", rs2.getInt("track_number"));
										obj.addProperty("start", durAccum/48000D);
										durAccum += rs2.getLong("duration");
										obj.addProperty("end", durAccum/48000D);
										_tracksJson.add(obj);
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
								double albumLoudness = rs.getInt("loudness")/10D;
								boolean has_tracks = !_tracks.isEmpty();
								List<Object> download_formats = Partyflow.enumerateFormats(tf -> tf.usage().canDownload());
								List<Object> stream_formats = Partyflow.enumerateFormats(tf -> tf.usage().canStream());
								String stream_formats_json = gson.toJson(Partyflow.enumerateJsonFormats(tf -> tf.usage().canStream()));
								String tracks_json = gson.toJson(_tracksJson);
								boolean doneProcessing = rs.getString("concat_master") != null;
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
						"SELECT `title`, `subtitle`, `published`, `art` FROM `releases` "
						+ "WHERE `slug` = ? AND `user_id` = ?;")) {
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
				long releaseId;
				try (PreparedStatement ps = c.prepareStatement("SELECT `release_id`, `art`, `concat_master` FROM `releases` WHERE `slug` = ? AND `user_id` = ?;")) {
					ps.setString(1, slugs);
					ps.setInt(2, s.userId);
					try (ResultSet rs = ps.executeQuery()) {
						// slug is UNIQUE, we don't need to handle more than one row
						if (rs.first()) {
							releaseId = rs.getLong("release_id");
							String art = Strings.emptyToNull(rs.getString("art"));
							if (art != null) {
								log.trace("Deleting {}", art);
								Partyflow.storage.removeBlob(Partyflow.storageContainer, art);
							}
							String concatMaster = Strings.emptyToNull(rs.getString("concat_master"));
							if (concatMaster != null) {
								log.trace("Deleting {}", concatMaster);
								Partyflow.storage.removeBlob(Partyflow.storageContainer, concatMaster);
							}
						} else {
							res.sendError(HTTP_404_NOT_FOUND);
							return;
						}
					}
				}
				try (PreparedStatement ps = c.prepareStatement("SELECT `file` FROM `transcodes` WHERE `release_id` = ?;")) {
					ps.setLong(1, releaseId);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							log.trace("Deleting {}", rs.getString("file"));
							Partyflow.storage.removeBlob(Partyflow.storageContainer, rs.getString("file"));
						}
					}
				}
				try (PreparedStatement ps = c.prepareStatement("SELECT `master` FROM `tracks` WHERE `release_id` = ?;")) {
					ps.setLong(1, releaseId);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							log.trace("Deleting {}", rs.getString("master"));
							Partyflow.storage.removeBlob(Partyflow.storageContainer, rs.getString("master"));
						}
					}
				}
				try (PreparedStatement ps = c.prepareStatement("DELETE FROM `releases` WHERE `release_id` = ?;"
						+ "DELETE FROM `tracks` WHERE `release_id` = ?; DELETE FROM `transcodes` WHERE `release_id` = ?;")) {
					ps.setLong(1, releaseId);
					ps.setLong(2, releaseId);
					ps.setLong(3, releaseId);
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
				try (PreparedStatement ps = c.prepareStatement("UPDATE `releases` SET `published` = ?"+midfix+" WHERE `slug` = ? AND `user_id` = ?;")) {
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
						"SELECT `published` FROM `releases` "
						+ "WHERE `slug` = ? AND `user_id` = ?;")) {
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
					try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM `releases` WHERE `slug` = ?;")) {
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
				String midfix = data.getPart("publish") != null ? ", `published` = true, `published_at` = NOW()" : "";
				String extraCols = artPath != null ? "`art` = ?," : "";
				try (PreparedStatement ps = c.prepareStatement(
						"UPDATE `releases` SET `title` = ?, `subtitle` = ?, `slug` = ?, "+extraCols+" `description` = ?, `last_updated` = NOW()"+midfix
						+ " WHERE `slug` = ? AND `user_id` = ?;")) {
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
				try {
					c.setAutoCommit(false);
					int releaseId;
					try (PreparedStatement ps = c.prepareStatement("SELECT `release_id` FROM `releases` WHERE `slug` = ? AND `user_id` = ?;")) {
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
					record TrackData(String title, int trackNumber, String blobName, long duration, double loudness, double peak, String lyrics) {}
					List<Future<TrackData>> futures = new ArrayList<>();
					for (Part master : masters) {
						futures.add(ThreadPools.TRANSCODE.submit(() -> {
							File tmpFile = File.createTempFile("transcode-", ".flac", WORK_DIR);
							try {
								log.debug("Processing master {}...", master.getSubmittedFileName());
								Process p = Partyflow.ffmpeg("-v", "info", "-nostats",
										"-i", "-", "-map", "a",
										"-af", "ebur128=framelog=verbose:peak=true",
										"-dither_method", "improved_e_weighted",
										"-filter_type", "kaiser",
										"-sample_fmt", "s16",
										"-ar", "48k",
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
								String mpegErr = new String(ByteStreams.toByteArray(p.getErrorStream()), Charsets.UTF_8);
								if (p.exitValue() != 0) {
									log.warn("Failed to process audio with FFmpeg, output:\n{}", mpegErr);
									throw new ServletException("Failed to transcode; FFmpeg exited with code "+p.exitValue());
								}
								double loudness = find(LOUDNESS_FFMPEG_PATTERN, mpegErr)
										.map(Doubles::tryParse)
										.orElse(0D);
								double peak = find(PEAK_FFMPEG_PATTERN, mpegErr)
										.map(Doubles::tryParse)
										.orElse(0D);
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
								long duration;
								String lyrics;
								if (probe.exitValue() != 0) {
									String str = new String(ByteStreams.toByteArray(probe.getErrorStream()), Charsets.UTF_8);
									log.warn("Failed to probe master with FFprobe:\n{}", str);
									title = titleFromFilename;
									duration = 0;
									lyrics = null;
								} else {
									String probeOut = new String(ByteStreams.toByteArray(probe.getInputStream()), Charsets.UTF_8);
									title = find(TITLE_FFPROBE_PATTERN, probeOut).orElse(titleFromFilename);
									trackNumber = find(TRACK_FFPROBE_PATTERN, probeOut)
											.map(Ints::tryParse)
											.filter(i -> i >= 1)
											.orElse(-1);
									duration = find(DURATION_FFPROBE_PATTERN, probeOut)
											.map(this::tryParseBigDecimal)
											.map(bd -> bd.multiply(TO_SAMPLES))
											.map(BigDecimal::longValue)
											.orElse(0L);
									lyrics = find(LYRICS_FFPROBE_PATTERN, probeOut)
											.map(str -> str.replace("\\n", "\n").replace("\\r", ""))
											.orElse(null);
								}
								log.debug("Processed master {}. {} successfully.\nDuration: {}ms, loudness: {}LUFS, peak: {}dBFS", trackNumber, title, duration/48, loudness, peak);
								return new TrackData(title, trackNumber, blobName, duration, loudness, peak, lyrics);
							} finally {
								tmpFile.delete();
							}
						}));
					}
					for (var f : futures) {
						TrackData td;
						try {
							td = f.get();
						} catch (InterruptedException | ExecutionException e) {
							throw new ServletException(e);
						}
						int trackNumber = td.trackNumber;
						if (trackNumber == -1) {
							try (PreparedStatement ps = c.prepareStatement("SELECT MAX(`track_number`)+1 AS `next` FROM `tracks` WHERE `release_id` = ?;")) {
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
						String slug = Partyflow.sanitizeSlug(td.title);
						try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM `tracks` WHERE `slug` = ?;")) {
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
								"INSERT INTO `tracks` (`release_id`, `title`, `subtitle`, `slug`, `master`, `description`, `track_number`, `loudness`, `peak`, `duration`, `lyrics`, `created_at`, `last_updated`) "
								+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW());")) {
							ps.setInt(1, releaseId);
							ps.setString(2, td.title);
							ps.setString(3, "");
							ps.setString(4, slug);
							ps.setString(5, td.blobName);
							ps.setString(6, "");
							ps.setInt(7, trackNumber);
							ps.setInt(8, (int)(td.loudness*10));
							ps.setInt(9, (int)(td.peak*10));
							ps.setLong(10, td.duration);
							ps.setString(11, td.lyrics);
							ps.execute();
						}
					}
					c.commit();
					committed = true;
					regenerateAlbumFile(releaseId);
				} finally {
					if (!committed) {
						c.rollback();
					}
					c.setAutoCommit(true);
				}
			} catch (SQLException e) {
				throw new ServletException(e);
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
	
	private BigDecimal tryParseBigDecimal(String s) {
		try {
			return new BigDecimal(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
	
	public static void regenerateAlbumFile(long releaseId) {
		ThreadPools.GENERIC.execute(() -> {
			List<File> tmpFiles = new ArrayList<>();
			try (Connection c = Partyflow.sql.getConnection()) {
				List<String> concatLines = new ArrayList<>();
				concatLines.add("ffconcat version 1.0");
				log.debug("Regenerating gapless album file for release ID {}", releaseId);
				List<String> masters = new ArrayList<>();
				double loudness = -70;
				double peak = 0;
				try (PreparedStatement ps = c.prepareStatement("SELECT `master`, `loudness`, `peak` FROM `tracks` WHERE `release_id` = ? ORDER BY `track_number` ASC;")) {
					ps.setLong(1, releaseId);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							loudness = rs.getInt("loudness")/10D;
							peak = rs.getInt("peak")/10D;
							masters.add(rs.getString("master"));
						}
						
					}
				}
				if (masters.isEmpty()) {
					log.debug("Trivial case: No tracks. Empty concat.");
					updateConcatMaster(c, releaseId, -70, 0, null);
					return;
				}
				if (masters.size() == 1) {
					log.debug("Trivial case: One track. Concat = sole track.");
					updateConcatMaster(c, releaseId, loudness, peak, masters.get(0));
					return;
				}
				for (String m : masters) {
					File tmpFile = File.createTempFile("concat-", ".flac", WORK_DIR);
					tmpFiles.add(tmpFile);
					try (InputStream in = Partyflow.storage.getBlob(Partyflow.storageContainer, m).getPayload().openStream()) {
						try (OutputStream out = new FileOutputStream(tmpFile)) {
							ByteStreams.copy(in, out);
						}
					}
					concatLines.add("file "+tmpFile.getName());
				}
				File concatFile = File.createTempFile("concat-", ".txt", WORK_DIR);
				tmpFiles.add(concatFile);
				Files.asCharSink(concatFile, Charsets.UTF_8).writeLines(concatLines);
				File outFile = File.createTempFile("concat-", ".flac", WORK_DIR);
				tmpFiles.add(outFile);
				Process p = Partyflow.ffmpeg("-v", "info", "-nostdin", "-nostats",
						"-i", concatFile.getAbsolutePath(), "-map", "a",
						"-af", "ebur128=framelog=verbose:peak=true",
						"-sample_fmt", "s16",
						"-dither_method", "improved_e_weighted",
						"-map_metadata", "-1",
						"-metadata", "comment=Generated by Partyflow v"+Version.FULL+" hosted at "+Partyflow.publicUri.getHost(),
						"-f", "flac",
						"-y", outFile.getAbsolutePath());
				p.getOutputStream().close();
				while (p.isAlive()) {
					try {
						p.waitFor();
					} catch (InterruptedException e) {
					}
				}
				String mpegErr = new String(ByteStreams.toByteArray(p.getErrorStream()), Charsets.UTF_8);
				if (p.exitValue() != 0) {
					log.warn("Failed to process audio with FFmpeg, output:\n{}", mpegErr);
					return;
				}
				loudness = find(LOUDNESS_FFMPEG_PATTERN, mpegErr)
						.map(Doubles::tryParse)
						.orElse(0D);
				peak = find(PEAK_FFMPEG_PATTERN, mpegErr)
						.map(Doubles::tryParse)
						.orElse(0D);
				String blobName;
				do {
					String rand = Partyflow.randomString(16);
					blobName = "concats/"+rand.substring(0, 3)+"/"+rand+".flac";
				} while (Partyflow.storage.blobExists(Partyflow.storageContainer, blobName));
				Blob blob = Partyflow.storage.blobBuilder(blobName)
						.payload(outFile)
						.cacheControl("private")
						.contentLength(outFile.length())
						.contentDisposition("attachment")
						.contentType("audio/flac")
						.build();
				Partyflow.storage.putBlob(Partyflow.storageContainer, blob, new PutOptions().multipart().setBlobAccess(BlobAccess.PRIVATE));
				updateConcatMaster(c, releaseId, loudness, peak, blobName);
			} catch (Throwable e) {
				log.warn("Failed to regenerate album file for release ID {}", releaseId, e);
			} finally {
				tmpFiles.forEach(File::delete);
			}
		});
	}

	private static void updateConcatMaster(Connection c, long releaseId, double loudness, double peak, String blobName) throws SQLException {
		try {
			c.setAutoCommit(false);
			boolean committed = false;
			String oldBlob = null;
			try {
				try (PreparedStatement ps = c.prepareStatement("SELECT `concat_master` FROM `releases` WHERE `release_id` = ?;")) {
					ps.setLong(1, releaseId);
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.first()) {
							oldBlob = rs.getString("concat_master");
						}
					}
				}
				boolean success = false;
				try (PreparedStatement ps = c.prepareStatement("UPDATE `releases` SET `concat_master` = ?, `loudness` = ?, `peak` = ? WHERE `release_id` = ?;")) {
					ps.setString(1, blobName);
					ps.setInt(2, (int)(loudness*10));
					ps.setInt(3, (int)(peak*10));
					ps.setLong(4, releaseId);
					success = (ps.executeUpdate() > 0);
				}
				c.commit();
				committed = true;
				log.debug("Processed concatenation successfully.\nAlbum loudness: {}LUFS, album peak: {}dBFS", loudness, peak);
				if (success && oldBlob != null) {
					log.trace("Deleting {}", oldBlob);
					Partyflow.storage.removeBlob(Partyflow.storageContainer, oldBlob);
				}
			} finally {
				if (!committed) {
					c.rollback();
				}
			}
		} finally {
			c.setAutoCommit(true);
		}
	}

	private static Optional<String> find(Pattern pattern, String haystack) {
		Matcher m = pattern.matcher(haystack);
		if (m.find()) {
			return Optional.of(m.group(1));
		} else {
			return Optional.empty();
		}
	}

	private String sanitizeHtml(String html) {
		return Jsoup.clean(html, Safelist.relaxed().removeTags("img"));
	}

}
