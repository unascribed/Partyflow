package com.unascribed.partyflow.handler;

import java.io.IOException;
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

import com.overzealous.remark.Remark;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.TranscodeFormat.Usage;

public class TrackHandler extends SimpleHandler implements GetOrHead {

	private static final Pattern PATH_PATTERN = Pattern.compile("^([^/]+)(/delete|/edit)?$");

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
		} else {
			res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
		}
	}

}
