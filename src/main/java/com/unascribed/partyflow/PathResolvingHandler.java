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

package com.unascribed.partyflow;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.HandlerWrapper;

public class PathResolvingHandler extends HandlerWrapper {

	private final boolean directory;
	private final String path;

	public PathResolvingHandler(String path, Handler delegate) {
		this.path = path;
		directory = path.endsWith("/");
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
		if (directory) {
			if (target.startsWith(path)) {
				super.handle(target.substring(path.length()), baseRequest, req, res);
			}
		} else {
			if (target.equals(path)) {
				super.handle(target.substring(path.length()), baseRequest, req, res);
			}
		}
	}

}
