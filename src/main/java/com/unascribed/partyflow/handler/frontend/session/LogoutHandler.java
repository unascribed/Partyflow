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

package com.unascribed.partyflow.handler.frontend.session;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Map;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.data.QSessions;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.UrlEncodedPost;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.URLs;
import com.unascribed.partyflow.logic.SessionHelper.Session;

public class LogoutHandler extends SimpleHandler implements UrlEncodedPost {

	@Override
	public void urlEncodedPost(String path, HttpServletRequest req, HttpServletResponse res, Map<String, String> params) throws IOException, ServletException, SQLException {
		Session session = SessionHelper.getSession(req);
		if (session == null) {
			res.sendRedirect(URLs.root());
			return;
		}
		String csrf = params.get("csrf");
		if (!Partyflow.isCsrfTokenValid(session, csrf)) {
			res.sendRedirect(URLs.root());
			return;
		}
		QSessions.destroy(session.sessionId());
		SessionHelper.clearSessionCookie(res);
		res.sendRedirect(URLs.root());
	}

}
