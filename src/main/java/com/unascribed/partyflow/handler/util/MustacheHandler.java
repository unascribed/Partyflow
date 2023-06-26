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
import java.io.StringWriter;
import java.io.Writer;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.MustacheFactory;
import com.github.mustachejava.MustacheNotFoundException;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.data.QMeta;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.logic.CSRF;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.URLs;
import com.unascribed.partyflow.logic.UserRole;
import com.unascribed.partyflow.logic.permission.Permission;
import com.unascribed.partyflow.util.OkColor;

import com.google.common.base.Function;

public class MustacheHandler extends SimpleHandler implements GetOrHead {

	private static final MustacheFactory mustache = new DefaultMustacheFactory("templates");
	private static final Object globalContext = new Object() {
		Object partyflow = new Object() {
			String version = Version.FULL;
		};
		String root = URLs.root();
		String publicUrl = URLs.absoluteRoot();
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

	private static final ThreadLocal<Map<String, Object>> metaTL = ThreadLocal.withInitial(HashMap::new);
	
	private static final Pattern cssInsn = Pattern.compile("\\$(contrast|contrast-filter|brighten|mix)#([0-9A-Fa-f]{6})(?:#([0-9A-Fa-f]{6}))?");
	
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
		Object[] arr = new Object[context.length+3];
		arr[0] = globalContext;
		var s = SessionHelper.get(req);
		UserRole role = s.role();
		arr[1] = new Object() {
			boolean loggedIn = s.isPresent();
			boolean admin = role.grants(Permission.admin.administrate); // TODO
			String username = s.username().orElse(null);
			String displayName = s.displayName().orElse(null);
			String csrf = s.map(CSRF::allocate).orElse(null);
			String body = "body";
			String fontFamilyId = QMeta.font_family.get().toLowerCase(Locale.ROOT).replace(' ', '-');
		};
		var meta = metaTL.get();
		for (var v : QMeta.values()) {
			meta.put(v.camelKey(), v == QMeta.bunny_font ? v.get().equals("on") : v.get());
		}
		arr[2] = meta;
		System.arraycopy(context, 0, arr, 3, context.length);
		try {
			Writer w = res.getWriter();
			if (path.endsWith(".css")) {
				w = new StringWriter();
			}
			(Partyflow.config.http.cacheTemplates ? mustache : new DefaultMustacheFactory("templates"))
				.compile(path).execute(w, arr);
			w.close();
			if (path.endsWith(".css") && w instanceof StringWriter sw) {
				Appendable buf = sw.getBuffer();
				while (true) {
					var m = cssInsn.matcher((CharSequence)buf);
					if (!m.find()) break;
					var sb = new StringBuilder(((CharSequence)buf).length());
					do {
						String output = null;
						int rgb = Integer.parseInt(m.group(2), 16);
						var ok = OkColor.fromRGB(rgb);
						boolean needReconvert = true;
						switch (m.group(1)) {
							case "contrast" -> {
								ok.l = (ok.l+(ok.l > 0.6 ? 0 : 4))/5;
								ok.a = ok.a/4;
								ok.b = ok.b/4;
							}
							case "contrast-filter" -> {
								output = ok.l > 0.6 ? "invert(95%)" : "''";
							}
							case "brighten" -> {
								ok.l += 0.1f;
							}
							case "mix" -> {
								if (m.group(3) == null) {
									m.appendReplacement(sb, m.group().replace("$", "\\$"));
									continue;
								}
								var ok2 = OkColor.fromRGB(Integer.parseInt(m.group(3), 16));
								ok.l = (ok.l+ok2.l)/2;
								ok.a = (ok.a+ok2.a)/2;
								ok.b = (ok.b+ok2.b)/2;
							}
						}
						if (output == null) {
							if (needReconvert) {
								rgb = ok.toRGB();
							}
							output = "#"+(Integer.toHexString(rgb|0xFF000000).substring(2));
						}
						m.appendReplacement(sb, output);
					} while (m.find());
					m.appendTail(sb);
					buf = sb;
				}
				res.getWriter().write(buf.toString());
				res.getWriter().close();
			}
		} catch (MustacheNotFoundException e) {
			res.sendError(404);
		}
	}

}
