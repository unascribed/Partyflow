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
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;

import com.unascribed.partyflow.Partyflow;

public class PathResolvingHandler extends HandlerWrapper {

	private final Pattern pattern;

	public PathResolvingHandler(String path, Handler delegate) {
		if (path.contains("{}")) {
			int idx = path.indexOf("{}");
			String l = Pattern.quote(path.substring(0, idx));
			String r = Pattern.quote(path.substring(idx+2));
			this.pattern = Pattern.compile(l+"([^/]*)"+r);
		} else if (path.endsWith("/")) {
			this.pattern = Pattern.compile(Pattern.quote(path)+"(.*)");
		} else {
			this.pattern = Pattern.compile(Pattern.quote(path)+"()");
		}
		setHandler(delegate);
	}

	@Override
	public void handle(String _target, Request baseRequest, HttpServletRequest req, HttpServletResponse res)
			throws IOException, ServletException {
		if (_target.contains("/..")) {
			res.sendError(400);
			return;
		}
		if (!_target.startsWith(Partyflow.config.http.path)) {
			res.sendError(404);
			return;
		}
		String target = _target.substring(Partyflow.config.http.path.length());
		var m = pattern.matcher(target);
		if (m.matches()) {
			super.handle(m.group(1), baseRequest, req, res);
		}
	}

}
