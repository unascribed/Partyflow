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

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.Arrays;

import javax.annotation.WillClose;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.options.PutOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.data.QReleases.Release;
import com.unascribed.partyflow.handler.util.MultipartData;
import com.unascribed.partyflow.handler.util.MustacheHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.handler.util.SimpleHandler.MultipartPost;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.Storage;
import com.unascribed.partyflow.logic.URLs;
import com.unascribed.partyflow.logic.permission.Permission;
import com.unascribed.partyflow.util.Commands;
import com.unascribed.partyflow.util.MoreByteStreams;
import com.unascribed.partyflow.util.Processes;
import com.unascribed.partyflow.util.Services;
import com.unascribed.partyflow.data.QGeneric;
import com.unascribed.partyflow.data.QReleases;

import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;

public class CreateReleaseHandler extends SimpleHandler implements MultipartPost, GetOrHead {
	private static final Logger log = LoggerFactory
			.getLogger(CreateReleaseHandler.class);

	private static final byte[] PNG_HEADER = {-119, 80, 78, 71};
	private static final byte[] JPEG_HEADER = {-1, -40};

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		var s = SessionHelper.get(req)
				.assertPresent()
				.assertPermission(Permission.release.create);
		res.setStatus(HTTP_200_OK);
		MustacheHandler.serveTemplate(req, res, "create-release.hbs.html", new Object() {
			String error = req.getParameter("error");
		});
	}

	@Override
	public void multipartPost(String path, HttpServletRequest req, HttpServletResponse res, MultipartData data)
			throws IOException, ServletException, SQLException {
		var session = SessionHelper.get(req)
				.assertPresent()
				.assertCsrf(data.getPartAsString("csrf", 64))
				.assertPermission(Permission.release.create);
		
		String title = Strings.nullToEmpty(data.getPartAsString("title", 1024));
		String subtitle = Strings.nullToEmpty(data.getPartAsString("subtitle", 1024));
		String _error;
		if (title.trim().isEmpty()) {
			_error = "Title is required";
		} else if (title.length() > 255) {
			_error = "Title is too long";
		} else if (subtitle.length() > 255) {
			_error = "Subtitle is too long";
		} else {
			_error = null;
		}
		if (_error != null) {
			res.setStatus(HTTP_400_BAD_REQUEST);
			MustacheHandler.serveTemplate(req, res, "create-release.hbs.html", new Object() {
				String error = _error;
			});
			return;
		}
		String slug = QGeneric.findSlug("releases", Partyflow.sanitizeSlug(title));
		QReleases.create(new Release(slug, session.userId(), title, subtitle, "", false, null, null));
		res.sendRedirect(URLs.relative("release/"+slug));
	}

	public static String processArt(Part art) throws IOException, IllegalArgumentException {
		byte[] probe = new byte[4];
		try (InputStream is = art.getInputStream()) {
			ByteStreams.readFully(is, probe);
		}
		String mime;
		String format;
		if (Arrays.equals(probe, PNG_HEADER)) {
			mime = "image/png";
			format = "png";
		} else if (Arrays.equals(Arrays.copyOf(probe, 2), JPEG_HEADER)) {
			mime = "image/jpeg";
			format = "jpeg";
		} else {
			throw new IllegalArgumentException("Invalid file format for art; only PNG and JPEG are accepted");
		}
		byte[] main = magick(art.getInputStream(), format, "-resize", "x576>", "-quality", "80");
		byte[] thumb = magick(art.getInputStream(), "webp", "-resize", "x128>", "-quality", "75");
		String name;
		do {
			String rand = Partyflow.randomString(Services.random, 16);
			name = "art/"+rand.substring(0, 3)+"/"+rand+"."+format;
		} while (Storage.blobExists(name));
		Blob mainBlob = Storage.blobBuilder(name)
				.payload(main)
				.cacheControl("public, immutable")
				.contentLength(main.length)
				.contentType(mime)
				.build();
		Blob thumbBlob = Storage.blobBuilder(name+"-thumb.webp")
				.payload(thumb)
				.cacheControl("public, immutable")
				.contentLength(thumb.length)
				.contentType("image/webp")
				.build();
		Storage.putBlob(mainBlob, new PutOptions().setBlobAccess(BlobAccess.PUBLIC_READ));
		Storage.putBlob(thumbBlob, new PutOptions().setBlobAccess(BlobAccess.PUBLIC_READ));
		return mainBlob.getMetadata().getName();
	}

	private static byte[] magick(@WillClose InputStream in, String format, String... args) throws IOException {
		try (in) {
			Process magick = Commands.magick_convert("-", "-strip", args, format+":-").start();
			try (var out = magick.getOutputStream()) {
				in.transferTo(out);
			}
			byte[] imgData = MoreByteStreams.consume(magick.getInputStream());
			if (Processes.waitForUninterruptibly(magick) != 0) {
				String s = MoreByteStreams.slurp(magick.getErrorStream());
				log.warn("Failed to process image with ImageMagick:\n{}", s);
				throw new IllegalArgumentException("Failed to process art");
			}
			return imgData;
		}
	}

}
