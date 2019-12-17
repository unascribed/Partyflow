package com.unascribed.partyflow.handler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jclouds.blobstore.domain.Blob;
import org.jclouds.io.ContentMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.overzealous.remark.Remark;
import com.unascribed.partyflow.MultipartData;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.SimpleHandler.UrlEncodedOrMultipartPost;
import com.unascribed.partyflow.TranscodeFormat.Usage;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;

public class TrackHandler extends SimpleHandler implements GetOrHead, UrlEncodedOrMultipartPost {

	private static final Logger log = LoggerFactory.getLogger(TrackHandler.class);

	private static final Pattern PATH_PATTERN = Pattern.compile("^([^/]+)(/delete|/edit|/master)?$");

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
				String trackSlug = m.group(1);
				String suffix = s == null ? "" : " OR releases.user_id = ?";
				try (PreparedStatement ps = c.prepareStatement(
						"SELECT tracks.title, tracks.subtitle, releases.published, releases.art, releases.title, releases.slug, "
								+ "tracks.art, tracks.description, releases.user_id, users.display_name FROM tracks "
						+ "JOIN releases ON releases.release_id = tracks.release_id "
						+ "JOIN users ON releases.user_id = users.user_id "
						+ "WHERE tracks.slug = ? AND (releases.published = true"+suffix+");")) {
					ps.setString(1, trackSlug);
					if (s != null) {
						ps.setInt(2, s.userId);
					}
					try (ResultSet rs = ps.executeQuery()) {
						// slug is UNIQUE, we don't need to handle more than one row
						if (rs.first()) {
							res.setStatus(HTTP_200_OK);
							boolean _editable = s != null && rs.getInt("releases.user_id") == s.userId;
							String _descriptionMd;
							String desc = rs.getString("tracks.description");
							if (_editable) {
								_descriptionMd = remark.convert(desc);
							} else {
								_descriptionMd = null;
							}
							String trackArt = rs.getString("tracks.art");
							String _art;
							if (trackArt == null) {
								_art = rs.getString("releases.art");
							} else {
								_art = trackArt;
							}
							MustacheHandler.serveTemplate(req, res, "track.hbs.html", new Object() {
								Object release = new Object() {
									String title = rs.getString("releases.title");
									String creator = rs.getString("users.display_name");
									String slug = rs.getString("releases.slug");
									boolean published = rs.getBoolean("releases.published");
								};
								List<Object> download_formats = Partyflow.enumerateFormats(Usage.DOWNLOAD);
								List<Object> stream_formats = Partyflow.enumerateFormats(Usage.STREAM);
								String title = rs.getString("tracks.title");
								String subtitle = rs.getString("tracks.subtitle");
								String slug = trackSlug;
								boolean editable = _editable;
								String art = Partyflow.resolveArt(_art);
								String description = desc;
								String descriptionMd = _descriptionMd;
								String error = query.get("error");
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
				String suffix = s == null ? "" : " OR releases.user_id = ?";
				try (PreparedStatement ps = c.prepareStatement(
						"SELECT master FROM tracks "
						+ "JOIN releases ON releases.release_id = tracks.release_id "
						+ "WHERE tracks.slug = ? AND (releases.published = true"+suffix+");")) {
					ps.setString(1, trackSlug);
					if (s != null) {
						ps.setInt(2, s.userId);
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
				String releaseSlug;
				int trackId;
				try (PreparedStatement ps = c.prepareStatement("SELECT track_id, tracks.art, master, releases.slug FROM tracks "
						+ "JOIN releases ON releases.release_id = tracks.release_id "
						+ "WHERE tracks.slug = ? AND releases.user_id = ?;")) {
					ps.setString(1, slugs);
					ps.setInt(2, s.userId);
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.first()) {
							trackId = rs.getInt("track_id");
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
				try (PreparedStatement ps = c.prepareStatement("SELECT file FROM transcodes WHERE track_id = ?;")) {
					ps.setInt(1, trackId);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							log.trace("Deleting {}", rs.getString("file"));
							Partyflow.storage.removeBlob(Partyflow.storageContainer, rs.getString("file"));
						}
					}
				}
				try (PreparedStatement ps = c.prepareStatement("DELETE FROM transcodes WHERE track_id = ?; DELETE FROM tracks WHERE track_id = ?;")) {
					ps.setInt(1, trackId);
					ps.setInt(2, trackId);
					ps.executeUpdate();
					res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(releaseSlug));
				}
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
			throws IOException, ServletException {
		// TODO Auto-generated method stub

	}

}
