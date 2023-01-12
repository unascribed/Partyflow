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
import java.net.URLDecoder;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.handler.api.v1.LoginApi;
import com.unascribed.partyflow.handler.util.MustacheHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.UserVisibleException;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.handler.util.SimpleHandler.UrlEncodedPost;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.URLs;

import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;

public class LoginHandler extends SimpleHandler implements GetOrHead, UrlEncodedPost {

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
		}
		try {
			boolean remember = params.containsKey("remember");
			var ar = LoginApi.invoke(params.get("name"), passwordSha512, remember);
			res.setHeader("Set-Cookie", SessionHelper.buildCookie(ar.token(), remember ? 365 : 0));
			res.sendRedirect(URLs.root());
		} catch (UserVisibleException uve) {
			res.setStatus(uve.getCode());
			MustacheHandler.serveTemplate(req, res, "login.hbs.html", new Object() {
				String error = uve.getMessage();
			});
			return;
		}
	}

}
