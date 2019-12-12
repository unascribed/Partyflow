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
import java.net.URL;
import java.net.URLConnection;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;

import com.google.common.io.ByteStreams;

public class StaticHandler extends SimpleHandler implements GetOrHead {

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		URL u = ClassLoader.getSystemResource("static/"+path);
		if (u != null) {
			URLConnection conn = u.openConnection();
			String mime;
			if (path.endsWith(".svg")) {
				mime = "image/svg+xml";
			} else {
				mime = conn.getContentType();
			}
			res.setHeader("Content-Type", mime);
			res.setHeader("Content-Length", Long.toString(conn.getContentLengthLong()));
			if ("quine.zip".equals(path)) {
				res.setHeader("Content-Disposition", "attachment; filename=Partyflow-src-v"+Version.FULL+".zip");
			}
			res.setStatus(HTTP_200_OK);
			if (!head) {
				conn.connect();
				ByteStreams.copy(conn.getInputStream(), res.getOutputStream());
			}
			res.getOutputStream().close();
		} else {
			res.sendError(HTTP_404_NOT_FOUND);
		}
	}

}
