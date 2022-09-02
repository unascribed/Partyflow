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

import com.unascribed.partyflow.SimpleHandler.GetOrHead;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SimpleHandler;

public class IndexHandler extends SimpleHandler implements GetOrHead {

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		String def = "<div class=\"message error\">missingno.</div>";
		String siteDescription;
		try (Connection c = Partyflow.sql.getConnection()) {
			try (Statement s = c.createStatement()) {
				try (ResultSet rs = s.executeQuery("SELECT value FROM meta WHERE name = 'site_description';")) {
					if (rs.first()) {
						siteDescription = rs.getString("value");
					} else {
						siteDescription = def;
					}
				}
			}
		} catch (SQLException e) {
			throw new ServletException(e);
		}
		MustacheHandler.serveTemplate(req, res, "index.hbs.html", new Object() {
			String site_description = siteDescription;
		});
	}

}
