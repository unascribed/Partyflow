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
import java.util.Map;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.handler.util.SimpleHandler.UrlEncodedPost;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.URLs;
import com.google.common.base.Strings;

public class DeleteReleaseHandler extends SimpleHandler implements GetOrHead, UrlEncodedPost {

	private static final Logger log = LoggerFactory.getLogger(DeleteReleaseHandler.class);
	
	@Override
	public void getOrHead(String slug, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		res.sendRedirect(URLs.url("release/"+escPathSeg(slug)+keepQuery(req)));
	}

	@Override
	public void urlEncodedPost(String slugs, HttpServletRequest req, HttpServletResponse res, Map<String, String> params)
			throws IOException, ServletException, SQLException {
		var s = SessionHelper.get(req)
				.assertPresent()
				.assertCsrf(params.get("csrf"));
		
		try (Connection c = Partyflow.sql.getConnection()) {
			long releaseId;
			try (PreparedStatement ps = c.prepareStatement("SELECT `release_id`, `art`, `concat_master` FROM `releases` WHERE `slug` = ? AND `user_id` = ?;")) {
				ps.setString(1, slugs);
				ps.setInt(2, s.userId());
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
			try (PreparedStatement ps = c.prepareStatement("DELETE FROM `transcodes` WHERE `release_id` = ?;")) {
				ps.setLong(1, releaseId);
				ps.executeUpdate();
				res.sendRedirect(URLs.url("releases"));
			}
			try (PreparedStatement ps = c.prepareStatement("DELETE FROM `tracks` WHERE `release_id` = ?;")) {
				ps.setLong(1, releaseId);
				ps.executeUpdate();
			}
			try (PreparedStatement ps = c.prepareStatement("DELETE FROM `releases` WHERE `release_id` = ?;")) {
				ps.setLong(1, releaseId);
				ps.executeUpdate();
			}
		} catch (SQLException e) {
			throw new ServletException(e);
		}
	}

}
