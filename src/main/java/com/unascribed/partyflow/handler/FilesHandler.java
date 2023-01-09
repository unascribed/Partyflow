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
import java.io.InputStream;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.InclusiveByteRange;
import org.jclouds.blobstore.KeyNotFoundException;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.options.GetOptions;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.URLs;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.handler.util.SimpleHandler.Options;

import com.google.common.io.ByteStreams;

public class FilesHandler extends SimpleHandler implements GetOrHead, Options {

	@Override
	public boolean options(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException {
		res.setHeader("Accept-Ranges", "bytes");
		return false;
	}
	
	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		if (!Partyflow.config.storage.publicUrlPattern.startsWith("files/")
				&& !Partyflow.config.storage.publicUrlPattern.startsWith(URLs.url("files/"))) {
			res.setStatus(HTTP_307_TEMPORARY_REDIRECT);
			res.setHeader("Location", Partyflow.config.storage.publicUrlPattern.replace("{}", path));
			return;
		}
		try {
			BlobAccess ba = Partyflow.storage.getBlobAccess(Partyflow.storageContainer, path);
			if (ba == BlobAccess.PRIVATE) {
				res.sendError(HTTP_404_NOT_FOUND);
				return;
			}
		} catch (KeyNotFoundException e) {
			res.sendError(HTTP_404_NOT_FOUND);
			return;
		}
		res.setHeader("Accept-Ranges", "bytes");
		var rangesHdr = req.getHeaders("Range");
		List<InclusiveByteRange> ranges = null;
		Long fullLen = null;
		if (rangesHdr != null && rangesHdr.hasMoreElements()) {
			fullLen = Partyflow.storage.blobMetadata(Partyflow.storageContainer, path).getSize();
			if (fullLen != null) {
				ranges = InclusiveByteRange.satisfiableRanges(rangesHdr, fullLen);
				if (ranges == null || ranges.isEmpty()) {
					res.sendError(HTTP_416_RANGE_NOT_SATISFIABLE);
					return;
				}
				if (ranges.size() > 1) {
					// TODO
					ranges = null;
				}
			}
		}
		var opt = new GetOptions();
		if (ranges != null) {
			for (var r : ranges) {
				opt.range(r.getFirst(), r.getLast());
			}
		}
		Blob b = Partyflow.storage.getBlob(Partyflow.storageContainer, path, opt);
		if (b != null) {
			res.setHeader("Cache-Control", "public, immutable");
			String etag = b.getMetadata().getETag();
			if (!etag.startsWith("\"")) {
				etag = "\""+etag.replace("\"", "\\\"")+"\"";
			}
			if (etag != null) {
				if (etag.equals(req.getHeader("If-None-Match"))) {
					res.setStatus(HTTP_304_NOT_MODIFIED);
					res.getOutputStream().close();
					return;
				}
				res.setHeader("ETag", etag);
			}
			Long len = b.getMetadata().getContentMetadata().getContentLength();
			if (len != null && rangesHdr == null) {
				res.setHeader("Content-Length", Long.toString(len));
			}
			String mime = b.getMetadata().getContentMetadata().getContentType();
			if (mime == null) {
				mime = "application/octet-stream";
			}
			res.setHeader("Content-Type", mime);
			String cd = b.getMetadata().getContentMetadata().getContentDisposition();
			if (cd != null) {
				res.setHeader("Content-Disposition", cd);
			}
			if (ranges == null) {
				res.setStatus(HTTP_200_OK);
			} else {
				res.setHeader("Content-Range", ranges.get(0).toHeaderRangeString(fullLen));
				res.setStatus(HTTP_206_PARTIAL_CONTENT);
			}
			if (!head) {
				try (InputStream in = b.getPayload().openStream()) {
					ByteStreams.copy(in, res.getOutputStream());
				}
			}
			res.getOutputStream().close();
		} else {
			res.sendError(HTTP_404_NOT_FOUND);
		}
	}

}
