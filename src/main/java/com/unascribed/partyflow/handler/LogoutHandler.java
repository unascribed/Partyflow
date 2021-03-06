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
import java.sql.SQLException;
import java.util.Map;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.SimpleHandler.UrlEncodedPost;

public class LogoutHandler extends SimpleHandler implements UrlEncodedPost {

	@Override
	public void urlEncodedPost(String path, HttpServletRequest req, HttpServletResponse res, Map<String, String> params) throws IOException, ServletException {
		Session session = SessionHelper.getSession(req);
		if (session == null) {
			res.sendRedirect(Partyflow.config.http.path);
			return;
		}
		String csrf = params.get("csrf");
		if (!Partyflow.isCsrfTokenValid(session, csrf)) {
			res.sendRedirect(Partyflow.config.http.path);
			return;
		}
		try (Connection c = Partyflow.sql.getConnection()) {
			try (PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE session_id = ?;")) {
				ps.setString(1, session.sessionId.toString());
				ps.executeUpdate();
			}
			SessionHelper.clearSessionCookie(res);
			res.sendRedirect(Partyflow.config.http.path);
		} catch (SQLException e) {
			throw new ServletException(e);
		}
	}

}
