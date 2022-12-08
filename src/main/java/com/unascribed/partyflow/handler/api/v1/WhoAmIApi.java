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
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.handler.UserVisibleException;
import com.unascribed.partyflow.handler.util.ApiHandler;

public class WhoAmIApi extends ApiHandler {
	
	public record WhoAmIResponse(boolean sessionValid, String name, String username) {}
	
	public static WhoAmIResponse invoke(Session session)
			throws UserVisibleException, SQLException {
		if (session == null) return new WhoAmIResponse(false, null, null);
		return new WhoAmIResponse(true, session.displayName(), session.username());
	}

}
