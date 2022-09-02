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

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.annotation.Nullable;
import javax.crypto.Mac;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.io.BaseEncoding;

public class SessionHelper {

	private static final Object NOT_LOGGED_IN = new Object();

	public static class Session {
		public final UUID sessionId;
		public final int userId;
		public final String username;
		public final String displayName;
		public final boolean admin;
		private Session(UUID sessionId, int userId, String username, String displayName, boolean admin) {
			this.sessionId = sessionId;
			this.userId = userId;
			this.username = username;
			this.displayName = displayName;
			this.admin = admin;
		}
	}

	private static final Splitter SEMICOLON_SPLITTER = Splitter.on(';').trimResults();

	public static @Nullable Session getSession(HttpServletRequest req) throws ServletException {
		Object cache = req.getAttribute(SessionHelper.class.getName()+".cache");
		if (cache == NOT_LOGGED_IN) {
			return null;
		} else if (cache instanceof Session) {
			return (Session)cache;
		}
		String cookies = req.getHeader("Cookie");
		if (cookies != null) {
			for (String cookie : SEMICOLON_SPLITTER.split(cookies)) {
				int eqIdx = cookie.indexOf('=');
				if (eqIdx != -1) {
					String key = cookie.substring(0, eqIdx);
					if (getCookieName().equals(key)) {
						String value = cookie.substring(eqIdx+1);
						int dIdx = value.indexOf('$');
						if (dIdx != -1) {
							String sessionIdStr = value.substring(0, dIdx);
							UUID sessionId = UUID.fromString(sessionIdStr);
							String theirHmacStr = value.substring(dIdx+1);
							byte[] theirHmac = BaseEncoding.base64Url().decode(theirHmacStr);
							try {
								Mac mac = Mac.getInstance("HmacSHA512");
								mac.init(Partyflow.sessionSecret);
								mac.update(sessionId.toString().getBytes(Charsets.UTF_8));
								byte[] hmac = mac.doFinal();
								if (MessageDigest.isEqual(hmac, theirHmac)) {
									try (Connection c = Partyflow.sql.getConnection()) {
										try (PreparedStatement ps = c.prepareStatement(
												"SELECT sessions.user_id, users.display_name, users.username, users.admin FROM sessions JOIN users ON users.user_id = sessions.user_id "
												+ "WHERE session_id = ? AND expires > NOW();")) {
											ps.setString(1, sessionId.toString());
											try (ResultSet rs = ps.executeQuery()) {
												if (rs.first()) {
													int uid = rs.getInt("user_id");
													Session s = new Session(sessionId, uid, rs.getString("users.username"), rs.getString("users.display_name"), rs.getBoolean("users.admin"));
													req.setAttribute(SessionHelper.class.getName()+".cache", s);
													return s;
												} else {
													break;
												}
											}
										}
									} catch (SQLException e) {
										throw new ServletException(e);
									}
								} else {
									break;
								}
							} catch (NoSuchAlgorithmException | InvalidKeyException e) {
								throw new AssertionError(e);
							} catch (IllegalArgumentException e) {
								break;
							}
						}
					}
				}
			}
		}
		req.setAttribute(SessionHelper.class.getName()+".cache", NOT_LOGGED_IN);
		return null;
	}

	public static void clearSessionCookie(HttpServletResponse res) {
		res.setHeader("Set-Cookie", getCookieName()+"=;Expires=Thu, 01 Jan 1970 00:00:00 GMT;"+getCookieOptions());
	}

	public static String getCookieName() {
		return (Partyflow.config.security.https ? "__Host-" : "")+"PartyflowSession";
	}

	public static String getCookieOptions() {
		return (Partyflow.config.security.https ? "Secure;" : "")+"Path="+Partyflow.config.http.path+";HttpOnly;";
	}

}
