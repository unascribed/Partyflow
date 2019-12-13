package com.unascribed.partyflow.handler;

import java.io.IOException;
import java.net.URLDecoder;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.lambdaworks.crypto.SCrypt;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.SimpleHandler.UrlEncodedPost;

import com.google.common.base.CharMatcher;
import com.google.common.base.Charsets;
import com.google.common.hash.Hashing;
import com.google.common.io.BaseEncoding;

public class LoginHandler extends SimpleHandler implements GetOrHead, UrlEncodedPost {

	private static final CharMatcher HEX = CharMatcher.anyOf("0123456789abcdef");

	private static final String DUMMY = "$s0$f0801$hJyPXcPmrCP9YacjtfRxbQ==$ttcR1Ccf3fUElWc2qLBcORQkgwt5UZVkTJnuMGS2XZ4=";

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		String _message;
		Map<String, String> query = parseQuery(req);
		if (query.containsKey("message")) {
			_message = query.get("message");
		} else {
			_message = null;
		}
		res.setStatus(HTTP_200_OK);
		MustacheHandler.serveTemplate(req, res, "login.hbs.html", new Object() {
			String message = _message;
		});
	}

	@Override
	public void urlEncodedPost(String path, HttpServletRequest req, HttpServletResponse res, Map<String, String> params) throws IOException, ServletException {
		String passwordSha512;
		if (params.containsKey("password")) {
			passwordSha512 = Hashing.sha512().hashString(URLDecoder.decode(params.get("password"), "UTF-8"), Charsets.UTF_8).toString();
		} else {
			passwordSha512 = URLDecoder.decode(params.get("hashed-password"), "UTF-8").toLowerCase(Locale.ROOT);
			if (passwordSha512.length() != 128 || !HEX.matchesAllOf(passwordSha512)) {
				res.setStatus(HTTP_400_BAD_REQUEST);
				MustacheHandler.serveTemplate(req, res, "login.hbs.html", new Object() {
					String error = "Hashed password is invalid";
				});
				return;
			}
		}
		// TODO this sucks - should we have a separate login id or maybe email?
		String name = Partyflow.sanitizeSlug(URLDecoder.decode(params.get("name"), "UTF-8"));
		try (Connection c = Partyflow.sql.getConnection()) {
			boolean success = false;
			int userId = -1;
			try (PreparedStatement ps = c.prepareStatement("SELECT user_id, password FROM users WHERE username = ?;")) {
				ps.setString(1, name);
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.first()) {
						if (SCrypt.check(passwordSha512, rs.getString("password"))) {
							success = true;
							userId = rs.getInt("user_id");
						} else {
							res.setStatus(HTTP_401_UNAUTHORIZED);
							MustacheHandler.serveTemplate(req, res, "login.hbs.html", new Object() {
								String error = "Username or password incorrect";
							});
						}
					} else {
						SCrypt.check(passwordSha512, DUMMY);
						res.setStatus(HTTP_401_UNAUTHORIZED);
						MustacheHandler.serveTemplate(req, res, "login.hbs.html", new Object() {
							String error = "Username or password incorrect";
						});
					}
				}
			}
			if (!success) return;
			try (PreparedStatement ps = c.prepareStatement("UPDATE users SET last_login = NOW() WHERE user_id = ?;")) {
				ps.setInt(1, userId);
				ps.execute();
			}
			boolean remember = params.containsKey("remember");
			UUID sessionId = UUID.randomUUID();
			int days = remember ? 365 : 7;
			try (PreparedStatement ps = c.prepareStatement("INSERT INTO sessions (session_id, user_id, expires) VALUES (?, ?, DATEADD(DAY, ?, NOW()));")) {
				ps.setString(1, sessionId.toString());
				ps.setInt(2, userId);
				ps.setInt(3, days);
				ps.execute();
			}
			Mac mac = Mac.getInstance("HmacSHA512");
			mac.init(Partyflow.sessionSecret);
			mac.update(sessionId.toString().getBytes(Charsets.UTF_8));
			String b64Mac = BaseEncoding.base64Url().encode(mac.doFinal());
			String maxAge = (remember ? "Max-Age="+(days*24*60*60)+";" : "");
			res.setHeader("Set-Cookie", SessionHelper.getCookieName()+"="+sessionId+"$"+b64Mac+";"+maxAge+SessionHelper.getCookieOptions());
			res.sendRedirect(Partyflow.config.http.path);
		} catch (SQLException e) {
			throw new ServletException(e);
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new AssertionError(e);
		}
	}

}
