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

package com.unascribed.partyflow.handler.frontend.release;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.overzealous.remark.Remark;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.handler.util.MustacheHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.TranscodeFormat;

import com.google.common.collect.Lists;

public class ViewReleaseHandler extends SimpleHandler implements GetOrHead {

	private static final Logger log = LoggerFactory.getLogger(ViewReleaseHandler.class);
	private static final Gson gson = new Gson();
	
	private final Remark remark = new Remark(com.overzealous.remark.Options.github());

	@Override
	public void getOrHead(String slugs, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		Map<String, String> query = parseQuery(req);
		Session s = SessionHelper.getSession(req);
		try (Connection c = Partyflow.sql.getConnection()) {
			String suffix = s == null ? "" : " OR `releases`.`user_id` = ?";
			try (PreparedStatement ps = c.prepareStatement(
					"SELECT `release_id`, `title`, `subtitle`, `published`, `art`, `description`, `loudness`, `releases`.`user_id`, `users`.`display_name`, `concat_master` FROM `releases` "
					+ "JOIN `users` ON `releases`.`user_id` = `users`.`user_id` "
					+ "WHERE `slug` = ? AND (`published` = true"+suffix+");")) {
				ps.setString(1, slugs);
				if (s != null) {
					ps.setInt(2, s.userId());
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
									String artCol = rs2.getString("art");
									String _art = artCol == null ? null : Partyflow.resolveArt(artCol);
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
						boolean _editable = s != null && rs.getInt("releases.user_id") == s.userId();
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
							List<Object> download_formats = TranscodeFormat.enumerate(tf -> tf.usage().canDownload());
							List<Object> stream_formats = TranscodeFormat.enumerate(tf -> tf.usage().canStream());
							String stream_formats_json = gson.toJson(TranscodeFormat.enumerateAsJson(tf -> tf.usage().canStream()));
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
	}

}
