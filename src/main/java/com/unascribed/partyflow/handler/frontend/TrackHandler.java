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

package com.unascribed.partyflow.handler.frontend;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.io.ContentMetadata;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.overzealous.remark.Remark;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.handler.frontend.release.AddTrackHandler;
import com.unascribed.partyflow.handler.util.MultipartData;
import com.unascribed.partyflow.handler.util.MustacheHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.handler.util.SimpleHandler.UrlEncodedOrMultipartPost;
import com.unascribed.partyflow.TranscodeFormat;
import com.unascribed.partyflow.URLs;
import com.unascribed.partyflow.data.QGeneric;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;

public class TrackHandler extends SimpleHandler implements GetOrHead, UrlEncodedOrMultipartPost {

	private static final Logger log = LoggerFactory.getLogger(TrackHandler.class);
	private static final Gson gson = new Gson();

	private static final Pattern PATH_PATTERN = Pattern.compile("^([^/]+)(/delete|/edit|/master)?$");

	private final Remark remark = new Remark(com.overzealous.remark.Options.github());

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		Matcher m = PATH_PATTERN.matcher(path);
		if (!m.matches()) return;
		Map<String, String> query = parseQuery(req);
		Session s = SessionHelper.getSession(req);
		if (m.group(2) == null) {
			try (Connection c = Partyflow.sql.getConnection()) {
				String trackSlug = m.group(1);
				String suffix = s == null ? "" : " OR `releases`.`user_id` = ?";
				try (PreparedStatement ps = c.prepareStatement(
						"SELECT `tracks`.`title`, `tracks`.`subtitle`, `releases`.`published`, `releases`.`art`, `releases`.`title`, `releases`.`slug`, "
								+ "`tracks`.`art`, `tracks`.`description`, `releases`.`user_id`, `users`.`display_name`, `tracks`.`loudness`, `duration`, "
								+ "`lyrics` "
							+ " FROM `tracks` "
						+ "JOIN `releases` ON `releases`.`release_id` = `tracks`.`release_id` "
						+ "JOIN `users` ON `releases`.`user_id` = `users`.`user_id` "
						+ "WHERE `tracks`.`slug` = ? AND (`releases`.`published` = true"+suffix+");")) {
					ps.setString(1, trackSlug);
					if (s != null) {
						ps.setInt(2, s.userId());
					}
					try (ResultSet rs = ps.executeQuery()) {
						// slug is UNIQUE, we don't need to handle more than one row
						if (rs.first()) {
							res.setStatus(HTTP_200_OK);
							boolean _editable = s != null && rs.getInt("releases.user_id") == s.userId();
							String desc = rs.getString("tracks.description");
							String trackArt = rs.getString("tracks.art");
							String _art;
							if (trackArt == null) {
								_art = rs.getString("releases.art");
							} else {
								_art = trackArt;
							}
							JsonArray _tracksJson = new JsonArray();
							JsonObject obj = new JsonObject();
							obj.addProperty("title", rs.getString("tracks.title"));
							obj.addProperty("subtitle", rs.getString("tracks.subtitle"));
							obj.addProperty("slug", trackSlug);
							obj.addProperty("art", _art);
							obj.addProperty("start", 0);
							obj.addProperty("end", rs.getLong("duration")/48000D);
							_tracksJson.add(obj);
							MustacheHandler.serveTemplate(req, res, "track.hbs.html", new Object() {
								Object release = new Object() {
									String title = rs.getString("releases.title");
									String creator = rs.getString("users.display_name");
									String slug = rs.getString("releases.slug");
									boolean published = rs.getBoolean("releases.published");
								};
								List<Object> download_formats = TranscodeFormat.enumerate(tf -> tf.usage().canDownload());
								List<Object> stream_formats = TranscodeFormat.enumerate(tf -> tf.usage().canStream());
								String title = rs.getString("tracks.title");
								String subtitle = rs.getString("tracks.subtitle");
								String slug = trackSlug;
								boolean editable = _editable;
								String art = URLs.resolveArt(_art);
								String lyrics = rs.getString("lyrics");
								String description = desc;
								String descriptionMd = remark.convert(desc);
								String error = query.get("error");
								double loudness = rs.getInt("tracks.loudness")/10D;
								double durationSecs = rs.getLong("duration")/48000D;
								String tracks_json = gson.toJson(_tracksJson);
								String stream_formats_json = gson.toJson(TranscodeFormat.enumerateAsJson(tf -> tf.usage().canStream()));
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
		} else if ("/master".equals(m.group(2))) {
			try (Connection c = Partyflow.sql.getConnection()) {
				String trackSlug = m.group(1);
				String suffix = s == null ? "" : " OR `releases`.`user_id` = ?";
				try (PreparedStatement ps = c.prepareStatement(
						"SELECT `master` FROM `tracks` "
						+ "JOIN `releases` ON `releases`.`release_id` = `tracks`.`release_id` "
						+ "WHERE `tracks`.`slug` = ? AND (`releases`.`published` = true"+suffix+");")) {
					ps.setString(1, trackSlug);
					if (s != null) {
						ps.setInt(2, s.userId());
					}
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.first()) {
							res.setStatus(HTTP_200_OK);
							// masters have private access, so we can't just redirect
							Blob blob = Partyflow.storage.getBlob(Partyflow.storageContainer, rs.getString("master"));
							Long l = blob.getMetadata().getSize();
							ContentMetadata cm = blob.getMetadata().getContentMetadata();
							if (l != null) {
								res.setHeader("Content-Length", Long.toString(l));
							}
							res.setHeader("Content-Type", cm.getContentType());
							res.setHeader("Content-Disposition", cm.getContentDisposition());
							res.setHeader("Cache-Control", cm.getCacheControl());
							try (InputStream in = blob.getPayload().openStream(); OutputStream out = res.getOutputStream()) {
								if (!head) {
									ByteStreams.copy(in, out);
								}
							}
						} else {
							res.sendError(HTTP_404_NOT_FOUND);
							return;
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
	public void urlEncodedPost(String path, HttpServletRequest req, HttpServletResponse res, Map<String, String> params) throws IOException, ServletException, SQLException {
		Matcher m = PATH_PATTERN.matcher(path);
		if (!m.matches()) return;
		if ("/delete".equals(m.group(2))) {
			Session s = SessionHelper.getSession(req);
			if (s == null) {
				res.sendRedirect(URLs.url("login?message=You must log in to do that."));
				return;
			}
			String csrf = params.get("csrf");
			if (!Partyflow.isCsrfTokenValid(s, csrf)) {
				res.sendRedirect(URLs.root());
				return;
			}
			try (Connection c = Partyflow.sql.getConnection()) {
				String slugs = m.group(1);
				String releaseSlug;
				long trackId;
				long releaseId;
				try (PreparedStatement ps = c.prepareStatement("SELECT `track_id`, `tracks`.`art`, `master`, `releases`.`slug`, `releases`.`release_id` FROM `tracks` "
						+ "JOIN `releases` ON `releases`.`release_id` = `tracks`.`release_id` "
						+ "WHERE `tracks`.`slug` = ? AND `releases`.`user_id` = ?;")) {
					ps.setString(1, slugs);
					ps.setInt(2, s.userId());
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.first()) {
							trackId = rs.getLong("track_id");
							releaseId = rs.getLong("releases.release_id");
							String art = Strings.emptyToNull(rs.getString("tracks.art"));
							if (art != null) {
								log.trace("Deleting {}", art);
								Partyflow.storage.removeBlob(Partyflow.storageContainer, art);
							}
							log.trace("Deleting {}", rs.getString("master"));
							Partyflow.storage.removeBlob(Partyflow.storageContainer, rs.getString("master"));
							releaseSlug = rs.getString("releases.slug");
						} else {
							res.sendError(HTTP_404_NOT_FOUND);
							return;
						}
					}
				}
				try (PreparedStatement ps = c.prepareStatement("SELECT `file` FROM `transcodes` WHERE `track_id` = ?;")) {
					ps.setLong(1, trackId);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							log.trace("Deleting {}", rs.getString("file"));
							Partyflow.storage.removeBlob(Partyflow.storageContainer, rs.getString("file"));
						}
					}
				}
				try (PreparedStatement ps = c.prepareStatement("DELETE FROM `transcodes` WHERE `track_id` = ?;")) {
					ps.setLong(1, trackId);
					ps.executeUpdate();
				}
				try (PreparedStatement ps = c.prepareStatement("DELETE FROM `tracks` WHERE `track_id` = ?;")) {
					ps.setLong(1, trackId);
					ps.executeUpdate();
				}
				AddTrackHandler.regenerateAlbumFile(releaseId);
				res.sendRedirect(URLs.url("release/"+escPathSeg(releaseSlug)));
			} catch (SQLException e) {
				throw new ServletException(e);
			}
		} else if ("/edit".equals(m.group(2))) {
			res.sendError(HTTP_415_UNSUPPORTED_MEDIA_TYPE);
		} else {
			res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
		}
	}

	@Override
	public void multipartPost(String path, HttpServletRequest req,
			HttpServletResponse res, MultipartData data)
			throws IOException, ServletException, SQLException {
		Matcher m = PATH_PATTERN.matcher(path);
		if (!m.matches()) return;
		if ("/edit".equals(m.group(2))) {
			Session s = SessionHelper.getSession(req);
			if (s == null) {
				res.sendRedirect(URLs.url("login?message=You must log in to do that."));
				return;
			}
			String csrf = data.getPartAsString("csrf", 64);
			if (!Partyflow.isCsrfTokenValid(s, csrf)) {
				res.sendRedirect(URLs.url("track/"+escPathSeg(m.group(1))+"?error=Invalid CSRF token"));
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
			String lyrics = data.getPartAsString("lyrics", 65536);
			if (title.trim().isEmpty()) {
				res.sendRedirect(URLs.url("track/"+escPathSeg(m.group(1))+"?error=Title is required"));
				return;
			}
			if (title.length() > 255) {
				res.sendRedirect(URLs.url("track/"+escPathSeg(m.group(1))+"?error=Title is too long"));
				return;
			}
			if (subtitle.length() > 255) {
				res.sendRedirect(URLs.url("track/"+escPathSeg(m.group(1))+"?error=Subtitle is too long"));
				return;
			}
			if (description.length() > 16384) {
				res.sendRedirect(URLs.url("track/"+escPathSeg(m.group(1))+"?error=Description is too long"));
				return;
			}
			String artPath = null;
			if (art != null && art.getSize() > 4) {
				try {
					artPath = CreateReleaseHandler.processArt(art);
				} catch (IllegalArgumentException e) {
					res.sendRedirect(URLs.url("track/"+escPathSeg(m.group(1))+"?error="+URLEncoder.encode(e.getMessage(), "UTF-8")));
					return;
				}
			}
			try (Connection c = Partyflow.sql.getConnection()) {
				boolean published;
				try (PreparedStatement ps = c.prepareStatement(
						"SELECT `releases`.`published` FROM `tracks` "
							+ "JOIN `releases` ON `tracks`.`release_id` = `releases`.`release_id` "
						+ "WHERE `tracks`.`slug` = ? AND `releases`.`user_id` = ?;")) {
					ps.setString(1, m.group(1));
					ps.setInt(2, s.userId());
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.first()) {
							published = rs.getBoolean("published");
						} else {
							res.sendRedirect(URLs.url("track/"+escPathSeg(m.group(1))+"?error=You're not allowed to do that"));
							return;
						}
					}
				}
				String slug;
				if (published) {
					slug = m.group(1);
				} else {
					slug = QGeneric.findSlug("releases", Partyflow.sanitizeSlug(title));
				}
				String extraCols = artPath != null ? "`art` = ?," : "";
				try (PreparedStatement ps = c.prepareStatement(
						"UPDATE `tracks` SET `title` = ?, `subtitle` = ?, `slug` = ?, "+extraCols+" `description` = ?, `lyrics` = ?, `last_updated` = NOW() "
						+ " WHERE `slug` = ?;")) {
					int i = 1;
					ps.setString(i++, title);
					ps.setString(i++, subtitle);
					ps.setString(i++, slug);
					if (artPath != null) {
						ps.setString(i++, artPath);
					}
					ps.setString(i++, description);
					ps.setString(i++, lyrics);
					ps.setString(i++, m.group(1));
					ps.executeUpdate();
				}
				res.sendRedirect(URLs.url("track/"+escPathSeg(slug)));
			} catch (SQLException e) {
				throw new ServletException(e);
			}
		} else if ("/delete".equals(m.group(2))) {
			res.sendError(HTTP_415_UNSUPPORTED_MEDIA_TYPE);
		}
	}

	private String sanitizeHtml(String html) {
		return Jsoup.clean(html, Safelist.relaxed().removeTags("img"));
	}

}
