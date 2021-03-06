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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.MustacheFactory;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;

import com.google.common.base.Function;

public class MustacheHandler extends SimpleHandler implements GetOrHead {

	private static final MustacheFactory mustache = new DefaultMustacheFactory("templates");
	private static final Object globalContext = new Object() {
		Object partyflow = new Object() {
			String version = Version.FULL;
		};
		String root = Partyflow.config.http.path;
	};

	private final String template;
	private final Function<HttpServletRequest, Object> contextComputer;

	public MustacheHandler(String template) {
		this.template = template;
		this.contextComputer = null;
	}

	public MustacheHandler(String template, Function<HttpServletRequest, Object> contextComputer) {
		this.template = template;
		this.contextComputer = contextComputer;
	}

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head) throws IOException, ServletException {
		if (contextComputer == null) {
			serveTemplate(req, res, template);
		} else {
			serveTemplate(req, res, template, contextComputer.apply(req));
		}
	}

	public static void serveTemplate(HttpServletRequest req, HttpServletResponse res, String path, Object... context) throws IOException, ServletException {
		if (path.endsWith(".html")) {
			res.setHeader("Content-Type", "text/html; charset=utf-8");
		} else if (path.endsWith(".css")) {
			res.setHeader("Content-Type", "text/css; charset=utf-8");
		} else if (path.endsWith(".js")) {
			res.setHeader("Content-Type", "application/javascript; charset=utf-8");
		}
		Object[] arr = new Object[context.length+2];
		arr[0] = globalContext;
		Session session = SessionHelper.getSession(req);
		arr[1] = new Object() {
			boolean loggedIn = session != null;
			boolean admin = session != null && session.admin;
			String username = session == null ? null : session.username;
			String displayName = session == null ? null : session.displayName;
			String csrf = session != null ? Partyflow.allocateCsrfToken(session) : null;
		};
		System.arraycopy(context, 0, arr, 2, context.length);
		(Partyflow.config.http.cacheTemplates ? mustache : new DefaultMustacheFactory("templates"))
			.compile(path).execute(res.getWriter(), arr);
		res.getWriter().close();
	}

}
