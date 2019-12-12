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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;

import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.options.PutOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.MultipartData;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.SimpleHandler.MultipartPost;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;

public class CreateReleaseHandler extends SimpleHandler implements MultipartPost, GetOrHead {
	private static final Logger log = LoggerFactory
			.getLogger(CreateReleaseHandler.class);

	private static final byte[] PNG_HEADER = {-119, 80, 78, 71};
	private static final byte[] JPEG_HEADER = {-1, -40, -1, -32};

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		MustacheHandler.serveTemplate(res, "create-release.hbs.html");
	}

	@Override
	public void multipartPost(String path, HttpServletRequest req, HttpServletResponse res, MultipartData data)
			throws IOException, ServletException {
		Part art = data.getPart("art");
		String title = Strings.nullToEmpty(data.getPartAsString("title", 1024));
		String subtitle = Strings.nullToEmpty(data.getPartAsString("subtitle", 1024));
		String description = Strings.nullToEmpty(data.getPartAsString("description", 65536));
		if (title.trim().isEmpty()) {
			res.setStatus(HTTP_400_BAD_REQUEST);
			MustacheHandler.serveTemplate(res, "create-release.hbs.html", new Object() {
				String error = "Title is required";
			});
			return;
		}
		if (title.length() > 255) {
			res.setStatus(HTTP_400_BAD_REQUEST);
			MustacheHandler.serveTemplate(res, "create-release.hbs.html", new Object() {
				String error = "Title is too long";
			});
			return;
		}
		if (subtitle.length() > 255) {
			res.setStatus(HTTP_400_BAD_REQUEST);
			MustacheHandler.serveTemplate(res, "create-release.hbs.html", new Object() {
				String error = "Subtitle is too long";
			});
			return;
		}
		if (description.length() > 16384) {
			res.setStatus(HTTP_400_BAD_REQUEST);
			MustacheHandler.serveTemplate(res, "create-release.hbs.html", new Object() {
				String error = "Description is too long";
			});
			return;
		}
		String artPath = null;
		if (art != null && art.getSize() > 0) {
			byte[] probe = new byte[4];
			try (InputStream is = art.getInputStream()) {
				ByteStreams.readFully(is, probe);
			}
			String mime;
			String format;
			if (Arrays.equals(probe, PNG_HEADER)) {
				mime = "image/png";
				format = "png";
			} else if (Arrays.equals(probe, JPEG_HEADER)) {
				mime = "image/jpeg";
				format = "jpeg";
			} else {
				res.setStatus(HTTP_400_BAD_REQUEST);
				MustacheHandler.serveTemplate(res, "create-release.hbs.html", new Object() {
					String error = "Invalid file format for art; only PNG and JPEG are accepted";
				});
				return;
			}
			Process magick = Partyflow.magick_convert("- -strip -resize x576> -quality 80 "+format+":-");
			ByteStreams.copy(art.getInputStream(), magick.getOutputStream());
			magick.getOutputStream().close();
			byte[] imgData = ByteStreams.toByteArray(magick.getInputStream());
			magick.getInputStream().close();
			while (magick.isAlive()) {
				try {
					magick.waitFor();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			if (magick.exitValue() != 0) {
				String s = new String(ByteStreams.toByteArray(magick.getErrorStream()), Charsets.UTF_8);
				log.warn("Failed to process image with ImageMagick:\n{}", s);
				res.setStatus(HTTP_500_INTERNAL_SERVER_ERROR);
				MustacheHandler.serveTemplate(res, "create-release.hbs.html", new Object() {
					String error = "Failed to process art";
				});
				return;
			}
			String name;
			do {
				String rand = Partyflow.randomString(16);
				name = "art/"+rand.substring(0, 3)+"/"+rand+"."+format;
			} while (Partyflow.storage.blobExists(Partyflow.storageContainer, name));
			Blob b = Partyflow.storage.blobBuilder(name)
					.payload(imgData)
					.cacheControl("public, immutable")
					.contentLength(imgData.length)
					.contentType(mime)
					.build();
			Partyflow.storage.putBlob(Partyflow.storageContainer, b, new PutOptions().setBlobAccess(BlobAccess.PUBLIC_READ));
			artPath = name;
		}
		try (Connection c = Partyflow.sql.getConnection()) {
			String slug = Partyflow.sanitizeSlug(title);
			try (PreparedStatement s = c.prepareStatement("SELECT 1 FROM releases WHERE slug = ?;")) {
				int i = 0;
				String suffix = "";
				while (true) {
					if (i > 0) {
						suffix = "-"+(i+1);
					}
					s.setString(1, slug+suffix);
					try (ResultSet rs = s.executeQuery()) {
						if (!rs.first()) break;
					}
					i++;
				}
				slug = slug+suffix;
			}
			try (PreparedStatement s = c.prepareStatement(
					"INSERT INTO releases (title, subtitle, slug, published, art, description) "
					+ "VALUES (?, ?, ?, FALSE, ?, ?);")) {
				s.setString(1, title);
				s.setString(2, subtitle);
				s.setString(3, slug);
				s.setString(4, artPath);
				s.setString(5, description);
				s.executeUpdate();
			}
			res.sendRedirect(Partyflow.config.http.path+"releases/"+slug);
		} catch (SQLException e) {
			throw new ServletException(e);
		}
	}

}
