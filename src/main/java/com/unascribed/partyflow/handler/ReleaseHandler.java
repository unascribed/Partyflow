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

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.SimpleHandler.Post;;

public class ReleaseHandler extends SimpleHandler implements GetOrHead, Post {

	private static final Pattern PATH_PATTERN = Pattern.compile("^([^/]+)(/delete)?$");

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		Matcher m = PATH_PATTERN.matcher(path);
		if (!m.matches()) return;
		if ("/delete".equals(m.group(2))) {
			res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
			return;
		}
		try (Connection c = Partyflow.sql.getConnection()) {
			String slugs = m.group(1);
			try (PreparedStatement ps = c.prepareStatement("SELECT title, subtitle, published, art, description FROM releases WHERE slug = ?;")) {
				ps.setString(1, slugs);
				try (ResultSet rs = ps.executeQuery()) {
					// slug is UNIQUE, we don't need to handle more than one row
					if (rs.first()) {
						res.setStatus(HTTP_200_OK);
						MustacheHandler.serveTemplate(req, res, "release.hbs.html", new Object() {
							String title = rs.getString("title");
							String subtitle = rs.getString("subtitle");
							String slug = slugs;
							boolean published = rs.getBoolean("published");
							String art = Partyflow.resolveArt(rs.getString("art"));
							String description = rs.getString("description");
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

	@Override
	public void post(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException {
		Matcher m = PATH_PATTERN.matcher(path);
		if (!m.matches()) return;
		if (!"/delete".equals(m.group(2))) {
			res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
			return;
		}
		try (Connection c = Partyflow.sql.getConnection()) {
			String slugs = m.group(1);
			int releaseId;
			try (PreparedStatement ps = c.prepareStatement("SELECT release_id, art FROM releases WHERE slug = ?;")) {
				ps.setString(1, slugs);
				try (ResultSet rs = ps.executeQuery()) {
					// slug is UNIQUE, we don't need to handle more than one row
					if (rs.first()) {
						releaseId = rs.getInt("release_id");
						Partyflow.storage.removeBlob(Partyflow.storageContainer, rs.getString("art"));
					} else {
						res.sendError(HTTP_404_NOT_FOUND);
						return;
					}
				}
			}
			try (PreparedStatement ps = c.prepareStatement("DELETE FROM releases WHERE release_id = ?; DELETE FROM tracks WHERE release_id = ?;")) {
				ps.setInt(1, releaseId);
				ps.setInt(2, releaseId);
				ps.executeUpdate();
				res.sendRedirect(Partyflow.config.http.path+"releases");
			}
		} catch (SQLException e) {
			throw new ServletException(e);
		}
	}

}
