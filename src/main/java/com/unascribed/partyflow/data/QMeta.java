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
import java.util.Optional;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.data.util.Queries;

public class QMeta extends Queries {

	protected static void set(String key, String value) throws SQLException {
		// assignment is so this will fail to compile if a new driver is added
		int dummy = switch (Partyflow.config.database.driver) {
			case mariadb ->
				update("INSERT INTO `meta` (`name`, `value`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `value` = ?;", key, value, value);
			case h2 ->
				update("MERGE INTO `meta` KEY(`name`) VALUES(?, ?);", key, value);
		};
	}
	
	protected static Optional<String> get(String key) throws SQLException {
		try (var rs = select("SELECT `value` FROM `meta` WHERE `name` = ?;", key)) {
			if (rs.first()) {
				return Optional.of(rs.getString("value"));
			} else {
				return Optional.empty();
			}
		}
	}
	
	public static String getSiteName() throws SQLException {
		return get("site_name").orElse("");
	}
	
	public static void setSiteName(String siteName) throws SQLException {
		set("site_name", siteName);
	}

	public static String getSiteDescription() throws SQLException {
		return get("site_description").orElse("<div class=\"message error\">missingno.</div>");
	}
	
	public static void setSiteDescription(String siteDescription) throws SQLException {
		set("site_description", siteDescription);
	}
	
}
