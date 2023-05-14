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

package com.unascribed.partyflow.data;

import java.sql.SQLException;
import java.util.UUID;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.data.util.Queries;
import com.unascribed.partyflow.logic.UserRole;
import com.unascribed.partyflow.logic.SessionHelper.GuestSession;
import com.unascribed.partyflow.logic.SessionHelper.RealSession;
import com.unascribed.partyflow.logic.SessionHelper.Session;

public class QSessions extends Queries {

	public static Session get(UUID sessionId) throws SQLException {
		try (var rs = select("SELECT `sessions`.`user_id`, `users`.`display_name`, `users`.`username`, `users`.`role` FROM `sessions` "
					+ "JOIN `users` ON `users`.`user_id` = `sessions`.`user_id` "
					+ "WHERE `session_id` = ? AND `expires` > NOW();",
				sessionId.toString())) {
			if (rs.first()) {
				return new RealSession(sessionId, rs.getInt("user_id"), rs.getString("users.username"),
						rs.getString("users.display_name"), UserRole.byId(rs.getInt("users.role")));
			} else {
				return GuestSession.INSTANCE;
			}
		}
	}

	public static void create(int userId, UUID sessionId, int days) throws SQLException {
		var dateadd = switch (Partyflow.config.database.driver) {
			case h2 -> "DATEADD(DAY, ?, NOW())";
			case mariadb -> "DATE_ADD(NOW(), INTERVAL ? DAY)";
			default -> throw new AssertionError();
		};
		update("INSERT INTO `sessions` (`session_id`, `user_id`, `expires`) VALUES (?, ?, "+dateadd+");",
			sessionId.toString(), userId, days);
	}

	public static void destroy(UUID sessionId) throws SQLException {
		update("DELETE FROM `sessions` WHERE `session_id` = ?;",
				sessionId.toString());
	}
	
}
