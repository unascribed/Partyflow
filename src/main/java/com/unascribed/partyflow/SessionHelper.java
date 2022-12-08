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
import java.sql.SQLException;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.crypto.Mac;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.data.QSessions;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;

public class SessionHelper {

	private static final Object NOT_LOGGED_IN = new Object();

	public record Session(UUID sessionId, int userId, String username, String displayName, boolean admin) {}

	private static final Splitter SEMICOLON_SPLITTER = Splitter.on(';').trimResults();
	
	public static @Nullable Session getSession(HttpServletRequest req) throws SQLException {
		Object cache = req.getAttribute("partyflow.sessionCache");
		if (cache == NOT_LOGGED_IN) {
			return null;
		} else if (cache instanceof Session) {
			return (Session)cache;
		}
		String token = "";
		if (req.getAttribute("partyflow.isApi") == Boolean.TRUE) {
			var auth = req.getHeader("Authorization");
			if (auth != null && auth.startsWith("Bearer ")) {
				token = auth.substring(7);
			}
		} else {
			String cookies = req.getHeader("Cookie");
			if (cookies != null) {
				for (String cookie : SEMICOLON_SPLITTER.split(cookies)) {
					int eqIdx = cookie.indexOf('=');
					if (eqIdx != -1) {
						String key = cookie.substring(0, eqIdx);
						if (getCookieName().equals(key)) {
							token = cookie.substring(eqIdx+1);
							break;
						}
					}
				}
			}
		}
		if (token.startsWith("pf_")) {
			try {
				byte[] data = BaseEncoding.base64Url().decode(token.substring(3));
				if (data.length == 16+32) {
					var badi = ByteStreams.newDataInput(data);
					UUID sessionId = new UUID(badi.readLong(), badi.readLong());
					byte[] theirHmac = new byte[256/8];
					badi.readFully(theirHmac);
					byte[] hmac = hmac(sessionId);
					if (MessageDigest.isEqual(hmac, theirHmac)) {
						Session s = QSessions.get(sessionId);
						if (s != null) {
							req.setAttribute("partyflow.sessionCache", s);
							return s;
						}
					}
				}
			} catch (IllegalArgumentException e) {}
		}
		req.setAttribute("partyflow.sessionCache", NOT_LOGGED_IN);
		return null;
	}

	private static byte[] hmac(UUID sessionId) {
		try {
			Mac mac = Mac.getInstance("HmacSHA3-256");
			mac.init(Partyflow.sessionSecret);
			mac.update(sessionId.toString().getBytes(Charsets.UTF_8));
			byte[] hmac = mac.doFinal();
			return hmac;
		} catch (NoSuchAlgorithmException | InvalidKeyException e) {
			throw new AssertionError(e);
		}
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

	public static @Nonnull String buildToken(UUID sessionId) {
		var bado = ByteStreams.newDataOutput();
		bado.writeLong(sessionId.getMostSignificantBits());
		bado.writeLong(sessionId.getLeastSignificantBits());
		bado.write(hmac(sessionId));
		return "pf_"+BaseEncoding.base64Url().encode(bado.toByteArray());
	}
	
	public static String buildCookie(String token, int days) {
		String maxAge = days == 0 ? "" : "Max-Age="+(days*24*60*60)+";";
		return getCookieName()+"="+token+";"+maxAge+getCookieOptions();
	}

}
