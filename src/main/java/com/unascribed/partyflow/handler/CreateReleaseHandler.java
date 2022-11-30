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

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.options.PutOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Commands;
import com.unascribed.partyflow.MultipartData;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SessionHelper.Session;
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
		Session session = SessionHelper.getSession(req);
		if (session != null) {
			res.setStatus(HTTP_200_OK);
			MustacheHandler.serveTemplate(req, res, "create-release.hbs.html");
		} else {
			res.sendRedirect(Partyflow.config.http.path+"login?message=You must log in to do that.");
		}
	}

	@Override
	public void multipartPost(String path, HttpServletRequest req, HttpServletResponse res, MultipartData data)
			throws IOException, ServletException {
		Session session = SessionHelper.getSession(req);
		if (session != null) {
			String csrf = data.getPartAsString("csrf", 64);
			if (!Partyflow.isCsrfTokenValid(session, csrf)) {
				res.sendRedirect(Partyflow.config.http.path);
				return;
			}
			String title = Strings.nullToEmpty(data.getPartAsString("title", 1024));
			String subtitle = Strings.nullToEmpty(data.getPartAsString("subtitle", 1024));
			if (title.trim().isEmpty()) {
				res.setStatus(HTTP_400_BAD_REQUEST);
				MustacheHandler.serveTemplate(req, res, "create-release.hbs.html", new Object() {
					String error = "Title is required";
				});
				return;
			}
			if (title.length() > 255) {
				res.setStatus(HTTP_400_BAD_REQUEST);
				MustacheHandler.serveTemplate(req, res, "create-release.hbs.html", new Object() {
					String error = "Title is too long";
				});
				return;
			}
			if (subtitle.length() > 255) {
				res.setStatus(HTTP_400_BAD_REQUEST);
				MustacheHandler.serveTemplate(req, res, "create-release.hbs.html", new Object() {
					String error = "Subtitle is too long";
				});
				return;
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
						"INSERT INTO `releases` (`user_id`, `title`, `subtitle`, `slug`, `description`, `published`, `created_at`, `last_updated`) "
						+ "VALUES (?, ?, ?, ?, ?, FALSE, NOW(), NOW());")) {
					s.setInt(1, session.userId);
					s.setString(2, title);
					s.setString(3, subtitle);
					s.setString(4, slug);
					s.setString(5, "");
					s.executeUpdate();
				}
				res.sendRedirect(Partyflow.config.http.path+"releases/"+slug);
			} catch (SQLException e) {
				throw new ServletException(e);
			}
		} else {
			res.sendRedirect(Partyflow.config.http.path+"login?message=You must log in to do that.");
		}
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
		} else if (Arrays.equals(probe, JPEG_HEADER)) {
			mime = "image/jpeg";
			format = "jpeg";
		} else {
			throw new IllegalArgumentException("Invalid file format for art; only PNG and JPEG are accepted");
		}
		Process magick = Commands.magick_convert("-", "-strip", "-resize", "x576>", "-quality", "80", format+":-").start();
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
			throw new IllegalArgumentException("Failed to process art");
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
		return b.getMetadata().getName();
	}

}
