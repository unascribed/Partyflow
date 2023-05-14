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

package com.unascribed.partyflow.handler.util;

import java.io.IOException;
import java.sql.SQLException;
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.MustacheNotFoundException;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.URLs;
import com.unascribed.partyflow.logic.UserRole;
import com.unascribed.partyflow.logic.permission.Permission;
import com.google.common.base.Function;

public class MustacheHandler extends SimpleHandler implements GetOrHead {

	private static final MustacheFactory mustache = new DefaultMustacheFactory("templates");
	private static final Object globalContext = new Object() {
		Object partyflow = new Object() {
			String version = Version.FULL;
		};
		String root = URLs.root();
		String publicUrl = URLs.publicRoot();
	};
	private static final Pattern FILE_EXT_PATTERN = Pattern.compile("\\.[^.]+$");

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
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head) throws IOException, ServletException, SQLException {
		String tmpl = template;
		if (tmpl.contains("{}")) {
			tmpl = tmpl.replace("{}", FILE_EXT_PATTERN.matcher(path).replaceFirst(".hbs$0"));
		}
		if (contextComputer == null) {
			serveTemplate(req, res, tmpl);
		} else {
			serveTemplate(req, res, tmpl, contextComputer.apply(req));
		}
	}

	public static void serveTemplate(HttpServletRequest req, HttpServletResponse res, String path, Object... context) throws IOException, ServletException, SQLException {
		if (path.endsWith(".html")) {
			res.setHeader("Content-Type", "text/html; charset=utf-8");
		} else if (path.endsWith(".css")) {
			res.setHeader("Content-Type", "text/css; charset=utf-8");
		} else if (path.endsWith(".js")) {
			res.setHeader("Content-Type", "application/javascript; charset=utf-8");
		} else if (path.endsWith(".xml")) {
			res.setHeader("Content-Type", "text/xml; charset=utf-8");
		}
		Object[] arr = new Object[context.length+2];
		arr[0] = globalContext;
		var s = SessionHelper.get(req);
		UserRole role = s.role();
		arr[1] = new Object() {
			boolean loggedIn = s.isPresent();
			boolean admin = role.grants(Permission.admin.administrate); // TODO
			String username = s.username().orElse(null);
			String displayName = s.displayName().orElse(null);
			String csrf = s.map(Partyflow::allocateCsrfToken).orElse(null);
		};
		System.arraycopy(context, 0, arr, 2, context.length);
		try {
			(Partyflow.config.http.cacheTemplates ? mustache : new DefaultMustacheFactory("templates"))
				.compile(path).execute(res.getWriter(), arr);
			res.getWriter().close();
		} catch (MustacheNotFoundException e) {
			res.sendError(404);
		}
	}

}
