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

package com.unascribed.partyflow.logic;

import static com.unascribed.partyflow.handler.util.SimpleHandler.HTTP_302_FOUND;

import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.crypto.Mac;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.data.QSessions;
import com.unascribed.partyflow.handler.util.UserVisibleException;
import com.unascribed.partyflow.logic.permission.PermissionNode;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;

public class SessionHelper {
	
	public sealed interface BaseSession<T extends BaseSession<T>> permits Session, AssertedSession {
		UserRole role();
		
		default boolean hasPermission(PermissionNode perm) {
			return role().grants(perm);
		}
		
		boolean isPresent();
		default boolean isEmpty() { return !isPresent(); }

		AssertedSession assertPresent() throws UserVisibleException;
		default T assertPermission(PermissionNode perm) throws UserVisibleException {
			if (!hasPermission(perm))
				throw new UserVisibleException(399, "You don't have permission to do that.");
			return (T)this;
		}
		AssertedSession assertCsrf(String csrf) throws UserVisibleException;
	}
	
	public sealed interface Session extends BaseSession<Session> permits RealSession, GuestSession {
		Optional<UUID> sessionId();
		OptionalInt userId();
		Optional<String> username();
		Optional<String> displayName();

		@Override
		default AssertedSession assertPresent() throws UserVisibleException {
			if (!isPresent())
				throw new UserVisibleException(HTTP_302_FOUND, URLs.url("login?message=You must log in to do that."));
			return new AssertedSession(sessionId().get(), userId().getAsInt(), username().get(), displayName().get(), role());
		}
		
		@Override
		default AssertedSession assertCsrf(String csrf) throws UserVisibleException {
			if (!Partyflow.isCsrfTokenValid(this, csrf))
				throw new UserVisibleException(399, "Invalid CSRF token");
			return assertPresent();
		}
		
		default <T> Optional<T> map(Function<Session, T> func) {
			return isPresent() ? Optional.of(func.apply(this)) : Optional.empty();
		}
	}
	
	public record AssertedSession(UUID sessionId, int userId, String username, String displayName, UserRole role) implements BaseSession<AssertedSession> {

		@Override
		public AssertedSession assertCsrf(String csrf) throws UserVisibleException {
			if (!Partyflow.isCsrfTokenValid(sessionId, csrf))
				throw new UserVisibleException(399, "Invalid CSRF token");
			return this;
		}

		@Override @Deprecated public boolean isPresent() { return true; }
		@Override @Deprecated public AssertedSession assertPresent() { return this; }
		
	}
	
	public record RealSession(Optional<UUID> sessionId, OptionalInt userId, Optional<String> username, Optional<String> displayName, UserRole role)
			implements Session {
		
		public RealSession(UUID sessionId, int userId, String username, String displayName, UserRole role) {
			this(Optional.of(sessionId), OptionalInt.of(userId), Optional.of(username), Optional.of(displayName), role);
		}
		
		@Override
		public boolean isPresent() {
			return true;
		}
	}
	
	public static final class GuestSession implements Session {
		public static final GuestSession INSTANCE = new GuestSession();
		
		private GuestSession() {}
		
		@Override
		public boolean isPresent() { return false; }

		@Override public Optional<UUID> sessionId() { return Optional.empty(); }
		@Override public OptionalInt userId() { return OptionalInt.empty(); }
		@Override public Optional<String> username() { return Optional.empty(); }
		@Override public Optional<String> displayName() { return Optional.empty(); }
		@Override public UserRole role() { return UserRole.GUEST; }

	}

	private static final Splitter SEMICOLON_SPLITTER = Splitter.on(';').trimResults();
	
	public static @Nonnull Session get(HttpServletRequest req) throws SQLException {
		if (req.getAttribute("partyflow.sessionCache") instanceof Session cache)
			return cache;
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
						req.setAttribute("partyflow.sessionCache", s);
						return s;
					}
				}
			} catch (IllegalArgumentException e) {}
		}
		req.setAttribute("partyflow.sessionCache", GuestSession.INSTANCE);
		return GuestSession.INSTANCE;
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
		return (Partyflow.config.security.https ? "Secure;" : "")+"Path="+URLs.root()+";HttpOnly;";
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
