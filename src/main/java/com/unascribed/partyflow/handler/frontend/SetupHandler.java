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
import java.net.URLDecoder;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.data.QUsers;
import com.unascribed.partyflow.handler.util.MustacheHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.Any;
import com.unascribed.partyflow.logic.URLs;
import com.unascribed.partyflow.logic.UserRole;

import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.base.Splitter;
import com.google.common.base.Splitter.MapSplitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;

public class SetupHandler extends SimpleHandler implements Any {

	private static final Logger log = LoggerFactory
			.getLogger(SetupHandler.class);

	private static final CharMatcher HEX = CharMatcher.anyOf("0123456789abcdef");
	private static final MapSplitter URLENCODED_SPLITTER = Splitter.on('&').withKeyValueSeparator('=');

	private static final ImmutableSet<String> WHITELISTED_PATHS = ImmutableSet.of(
			"assets/partyflow.css",
			"assets/password-hasher.js",
			"static/quine.zip"
		);

	@Override
	public boolean any(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException {
		if (Partyflow.setupToken == null) return true;
		if (!path.startsWith(URLs.root())) {
			res.sendError(HTTP_404_NOT_FOUND);
			return true;
		}
		path = path.substring(URLs.root().length());
		if (WHITELISTED_PATHS.contains(path)) return true;
		if (path.equals("setup")) {
			if (req.getMethod().equals("GET") || req.getMethod().equals("HEAD")) {
				res.setStatus(HTTP_200_OK);
				MustacheHandler.serveTemplate(req, res, "setup.hbs.html");
			} else if (req.getMethod().equals("POST")) {
				if (req.getContentType().equals("application/x-www-form-urlencoded")) {
					byte[] bys = Partyflow.readWithLimit(req.getInputStream(), 8192);
					if (bys == null) {
						res.sendError(HTTP_413_PAYLOAD_TOO_LARGE);
						return true;
					}
					String str = new String(bys, Charsets.UTF_8);
					Map<String, String> params = URLENCODED_SPLITTER.split(str);
					String token = URLDecoder.decode(params.get("token"), "UTF-8");
					if (!MessageDigest.isEqual(token.getBytes(Charsets.UTF_8), Partyflow.setupToken.getBytes(Charsets.UTF_8))) {
						res.setStatus(HTTP_400_BAD_REQUEST);
						MustacheHandler.serveTemplate(req, res, "setup.hbs.html", new Object() {
							String error = "Wrong secret token";
						});
						return true;
					}
					String passwordSha512;
					if (params.containsKey("password")) {
						String password = URLDecoder.decode(params.get("password"), "UTF-8");
						String confirm = URLDecoder.decode(params.get("confirm-password"), "UTF-8");
						if (!Objects.equal(password, confirm)) {
							res.setStatus(HTTP_400_BAD_REQUEST);
							MustacheHandler.serveTemplate(req, res, "setup.hbs.html", new Object() {
								String error = "Confirm password does not match";
							});
							return true;
						}
						if (password.length() < 8) {
							res.setStatus(HTTP_400_BAD_REQUEST);
							MustacheHandler.serveTemplate(req, res, "setup.hbs.html", new Object() {
								String error = "Password is too short";
							});
							return true;
						}
						passwordSha512 = Hashing.sha512().hashString(password, Charsets.UTF_8).toString();
					} else {
						passwordSha512 = URLDecoder.decode(params.get("hashed-password"), "UTF-8").toLowerCase(Locale.ROOT);
						if (passwordSha512.length() != 128 || !HEX.matchesAllOf(passwordSha512)) {
							res.setStatus(HTTP_400_BAD_REQUEST);
							MustacheHandler.serveTemplate(req, res, "setup.hbs.html", new Object() {
								String error = "Hashed password is invalid";
							});
							return true;
						}
					}
					String name = URLDecoder.decode(params.get("name"), "UTF-8");
					String username = Partyflow.sanitizeSlug(name);
					QUsers.create(name, username, passwordSha512, UserRole.ADMIN);
					log.info("Admin user {} created successfully. Setup mode disabled.", username);
					Partyflow.setupToken = null;
					res.sendRedirect(URLs.root());
				} else {
					res.setStatus(HTTP_415_UNSUPPORTED_MEDIA_TYPE);
					MustacheHandler.serveTemplate(req, res, "setup.hbs.html", new Object() {
						String error = "Unsupported media type";
					});
				}
			} else if (req.getMethod().equals("OPTIONS")) {
				res.setHeader("Allow", "OPTIONS, GET, HEAD");
				res.setStatus(HTTP_204_NO_CONTENT);
				res.getOutputStream().close();
			} else {
				res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
			}
		} else {
			res.sendRedirect(Partyflow.config.http.path+"setup");
		}
		return true;
	}

}
