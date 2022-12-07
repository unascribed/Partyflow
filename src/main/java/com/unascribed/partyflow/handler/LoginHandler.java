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
import java.net.URLDecoder;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.SimpleHandler.UrlEncodedPost;
import com.unascribed.partyflow.data.QSessions;
import com.unascribed.partyflow.data.QUsers;

import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;

public class LoginHandler extends SimpleHandler implements GetOrHead, UrlEncodedPost {

	private static final CharMatcher HEX = CharMatcher.anyOf("0123456789abcdef");

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		String _message;
		Map<String, String> query = parseQuery(req);
		if (query.containsKey("message")) {
			_message = query.get("message");
		} else {
			_message = null;
		}
		res.setStatus(HTTP_200_OK);
		MustacheHandler.serveTemplate(req, res, "login.hbs.html", new Object() {
			String message = _message;
		});
	}

	@Override
	public void urlEncodedPost(String path, HttpServletRequest req, HttpServletResponse res, Map<String, String> params) throws IOException, ServletException, SQLException {
		String passwordSha512;
		if (params.containsKey("password")) {
			passwordSha512 = Hashing.sha512().hashString(URLDecoder.decode(params.get("password"), "UTF-8"), Charsets.UTF_8).toString();
		} else {
			passwordSha512 = URLDecoder.decode(params.get("hashed-password"), "UTF-8").toLowerCase(Locale.ROOT);
			if (passwordSha512.length() != 128 || !HEX.matchesAllOf(passwordSha512)) {
				res.setStatus(HTTP_400_BAD_REQUEST);
				MustacheHandler.serveTemplate(req, res, "login.hbs.html", new Object() {
					String error = "Hashed password is invalid";
				});
				return;
			}
		}
		// TODO this sucks - should we have a separate login id or maybe email?
		String name = Partyflow.sanitizeSlug(params.get("name"));
		var au = QUsers.findForAuth(name);
		if (!au.verify(passwordSha512)) {
			res.setStatus(HTTP_401_UNAUTHORIZED);
			MustacheHandler.serveTemplate(req, res, "login.hbs.html", new Object() {
				String error = "Username or password incorrect";
			});
			return;
		}
		QUsers.updateLastLogin(au.userId());
		UUID sessionId = UUID.randomUUID();
		boolean remember = params.containsKey("remember");
		QSessions.create(au.userId(), sessionId, remember ? 365 : 7);
		res.setHeader("Set-Cookie", SessionHelper.buildCookie(sessionId, remember ? 365 : 0));
		res.sendRedirect(Partyflow.config.http.path);
	}

}
