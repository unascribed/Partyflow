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

package com.unascribed.partyflow.handler.frontend;


import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.zip.GZIPInputStream;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;

public class StaticHandler extends SimpleHandler implements GetOrHead {

	private static final String etag = StaticHandler.class.getPackage().getImplementationVersion();
	private static final String qetag = "\""+etag+"\"";
	
	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		if (path.endsWith(".gz")) {
			res.sendError(HTTP_404_NOT_FOUND);
			return;
		}
		if (etag != null && qetag.equals(req.getHeader("If-None-Match"))) {
			res.setStatus(HTTP_304_NOT_MODIFIED);
			res.getOutputStream().close();
			return;
		}
		var encodings = ((Request)req).getHttpFields().getQualityCSV(HttpHeader.ACCEPT_ENCODING);
		URL gz = ClassLoader.getSystemResource("static/"+path+".gz");
		URL raw = ClassLoader.getSystemResource("static/"+path);
		
		String etag = StaticHandler.etag;
		String qetag = etag == null ? null : StaticHandler.qetag;
		if (etag == null) {
			if (raw != null && "file".equals(raw.getProtocol())) {
				// dev environment, use lastModified as etag
				try {
					File f = new File(raw.toURI());
					etag = "dev-"+Long.toHexString(f.lastModified());
					qetag = "\""+etag+"\"";
					if (qetag.equals(req.getHeader("If-None-Match"))) {
						res.setStatus(HTTP_304_NOT_MODIFIED);
						res.getOutputStream().close();
						return;
					}
				} catch (URISyntaxException e) {}
			}
		}
		
		
		String mime = "application/octet-stream";
		Long len;
		InputStream in;
		if (gz != null) {
			var conn = gz.openConnection();
			conn.setDoInput(!head);
			if (encodings.contains("gzip")) {
				len = conn.getContentLengthLong();
				res.setHeader("Content-Encoding", "gzip");
				in = head ? null : conn.getInputStream();
			} else {
				len = null;
				in = head ? null : new GZIPInputStream(conn.getInputStream());
			}
		} else if (raw != null) {
			var conn = raw.openConnection();
			conn.setDoInput(!head);
			len = conn.getContentLengthLong();
			in = head ? null : conn.getInputStream();
		} else {
			res.sendError(HTTP_404_NOT_FOUND);
			return;
		}
		if (path.endsWith(".svg")) {
			mime = "image/svg+xml";
		} else if (path.endsWith(".js")) {
			mime = "application/javascript";
		} else if (path.endsWith(".css")) {
			mime = "text/css";
		} else if (path.endsWith(".png")) {
			mime = "image/png";
		}
		if ("quine.zip".equals(path)) {
			res.setHeader("Content-Disposition", "attachment; filename=Partyflow-src-v"+Version.FULL+".zip");
		}
		res.setHeader("Content-Type", mime);
		res.setHeader("ETag", qetag);
		if (len != null) res.setHeader("Content-Length", Long.toString(len));
		if (in != null) {
			try (in; var out = res.getOutputStream()) {
				in.transferTo(out);
			}
		} else {
			res.getOutputStream().close();
		}
	}

}
