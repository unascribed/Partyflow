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
import java.util.List;
import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QTracks extends Queries {
	
	private static final Logger log = LoggerFactory.getLogger(QTracks.class);
	
	public record Track(
			String slug, String title, String subtitle, @Column("art") String artId,
			OptionalInt trackNumber, long duration, String lyrics
		) implements Artful {
		String trackNumberStr() { return trackNumber.isPresent() ? trackNumber.getAsInt()+"." : ""; }
		double durationSecs() { return duration/48000D; }
	}
	
	private static final String COLUMNS = columnsForRecord("tracks", Track.class);
	
	public static List<Track> get(long releaseId) throws SQLException {
		try (var stream = unpack(Track.class, select("SELECT "+COLUMNS+" FROM `tracks` "
						+ "WHERE `release_id` = ? ORDER BY `track_number` ASC;", releaseId))) {
			return stream.toList();
		}
	}

}
