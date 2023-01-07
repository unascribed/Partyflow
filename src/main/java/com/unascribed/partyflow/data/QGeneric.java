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

public class QGeneric extends Queries {

	public static String findSlug(String table, String slug) throws SQLException {
		try (var c = conn(); var s = c.prepareStatement("SELECT 1 FROM "+table+" WHERE slug = ?;")) {
			int i = 0;
			String suffix = "";
			while (true) {
				if (i > 0) suffix = "-"+(i+1);
				s.setString(1, slug+suffix);
				try (var rs = s.executeQuery()) {
					if (!rs.first()) break;
				}
				i++;
			}
			slug = slug+suffix;
		}
		return slug;
	}

}
