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

import com.unascribed.partyflow.handler.util.ApiHandler;
import com.unascribed.partyflow.handler.util.UserVisibleException;
import com.unascribed.partyflow.logic.SessionHelper.Session;

public class WhoAmIApi extends ApiHandler {
	
	public record WhoAmIResponse(boolean sessionValid, String name, String username) {}
	
	@GET
	public static WhoAmIResponse invoke(Session session)
			throws UserVisibleException, SQLException {
		return new WhoAmIResponse(session.isPresent(), session.displayName().orElse(null), session.username().orElse(null));
	}

}
