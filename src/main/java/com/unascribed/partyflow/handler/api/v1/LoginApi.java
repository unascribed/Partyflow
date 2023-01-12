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

package com.unascribed.partyflow.handler.api.v1;

import java.sql.SQLException;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.data.QSessions;
import com.unascribed.partyflow.data.QUsers;
import com.unascribed.partyflow.handler.util.ApiHandler;
import com.unascribed.partyflow.handler.util.UserVisibleException;
import com.unascribed.partyflow.logic.SessionHelper;

import com.google.common.base.CharMatcher;

public class LoginApi extends ApiHandler {
	
	private static final CharMatcher HEX = CharMatcher.anyOf("0123456789abcdef");

	public record LoginResponse(@Nonnull String token) {}
	
	@POST
	public static LoginResponse invoke(String username, String password, boolean remember)
			throws UserVisibleException, SQLException {
		if (password.length() != 128 || !HEX.matchesAllOf(password)) {
			throw new UserVisibleException(HTTP_400_BAD_REQUEST, "Hashed password is invalid");
		}
		
		// TODO this sucks - should we have a separate login id or maybe email?
		String name = Partyflow.sanitizeSlug(username);
		var au = QUsers.findForAuth(name);
		if (!au.verify(password)) {
			throw new UserVisibleException(HTTP_401_UNAUTHORIZED, "Username or password incorrect");
		}
		QUsers.updateLastLogin(au.userId());
		UUID sessionId = UUID.randomUUID();
		QSessions.create(au.userId(), sessionId, remember ? 365 : 7);
		return new LoginResponse(SessionHelper.buildToken(sessionId));
	}

}
