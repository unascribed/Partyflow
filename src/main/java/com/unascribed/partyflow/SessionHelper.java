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
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

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
		private Session(UUID sessionId, int userId, String username, String displayName) {
			this.sessionId = sessionId;
			this.userId = userId;
			this.username = username;
			this.displayName = displayName;
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
												"SELECT sessions.user_id, users.display_name, users.username FROM sessions JOIN users ON users.user_id = sessions.user_id "
												+ "WHERE session_id = ? AND expires > NOW();")) {
											ps.setString(1, sessionId.toString());
											try (ResultSet rs = ps.executeQuery()) {
												if (rs.first()) {
													int uid = rs.getInt("user_id");
													Session s = new Session(sessionId, uid, rs.getString("users.username"), rs.getString("users.display_name"));
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
