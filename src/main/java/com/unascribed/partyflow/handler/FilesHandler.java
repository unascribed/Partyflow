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

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;

import com.google.common.io.ByteStreams;

public class FilesHandler extends SimpleHandler implements GetOrHead {

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		BlobAccess ba = Partyflow.storage.getBlobAccess(Partyflow.storageContainer, path);
		if (ba == BlobAccess.PRIVATE) {
			res.sendError(HTTP_404_NOT_FOUND);
			return;
		}
		Blob b = Partyflow.storage.getBlob(Partyflow.storageContainer, path);
		if (b != null) {
			String mime = b.getMetadata().getContentMetadata().getContentType();
			if (mime == null) {
				mime = "application/octet-stream";
			}
			res.setHeader("Content-Type", mime);
			String cd = b.getMetadata().getContentMetadata().getContentDisposition();
			if (cd != null) {
				res.setHeader("Content-Disposition", cd);
			}
			Long len = b.getMetadata().getContentMetadata().getContentLength();
			if (len != null) {
				res.setHeader("Content-Length", Long.toString(len));
			}
			res.setStatus(HTTP_200_OK);
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
