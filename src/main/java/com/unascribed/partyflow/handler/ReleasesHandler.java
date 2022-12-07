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
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;

import com.google.common.collect.Lists;

public class ReleasesHandler extends SimpleHandler implements GetOrHead {

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		Session s = SessionHelper.getSession(req);
		try (Connection c = Partyflow.sql.getConnection()) {
			String suffix = s == null ? "" : " OR `releases`.`user_id` = ?";
			try (PreparedStatement ps = c.prepareStatement(
					"SELECT `title`, `subtitle`, `slug`, `published`, `art`, `users`.`display_name` FROM `releases`"
					+ " JOIN `users` ON `users`.`user_id` = `releases`.`user_id`"
					+ " WHERE `published` = true"+suffix+";")) {
				if (s != null) {
					ps.setInt(1, s.userId());
				}
				try (ResultSet rs = ps.executeQuery()) {
					List<Object> li = Lists.newArrayList();
					while (rs.next()) {
						li.add(new Object() {
							String title = rs.getString("title");
							String subtitle = rs.getString("subtitle");
							String creator = rs.getString("users.display_name");
							String slug = rs.getString("slug");
							boolean published = rs.getBoolean("published");
							String art = Partyflow.resolveArt(rs.getString("art"));
						});
					}
					res.setStatus(HTTP_200_OK);
					MustacheHandler.serveTemplate(req, res, "releases.hbs.html", new Object() {
						List<Object> releases = li;
					});
				}
			}
		} catch (SQLException e) {
			throw new ServletException(e);
		}
	}

}
