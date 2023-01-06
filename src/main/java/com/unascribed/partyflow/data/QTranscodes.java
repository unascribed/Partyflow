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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.TranscodeFormat;
import com.unascribed.partyflow.TranscodeFormat.Shortcut;

import com.google.common.base.Strings;

public class QTranscodes extends Queries {
	
	private static final Logger log = LoggerFactory.getLogger(QTranscodes.class);
	
	public interface TranscodeFindResult {}
	
	public record FoundTranscode(String blob) implements TranscodeFindResult {}
	public record FoundShortcut(Shortcut shortcut, String srcBlob) implements TranscodeFindResult {}
	
	public static TranscodeFindResult findExistingTranscode(Connection c, boolean updateLastDownload, String kind, String slug,
			TranscodeFormat format, String master) throws SQLException {
		String addnFormats = Strings.repeat(", ?", format.shortcuts().size());
		try (var ps = c.prepareStatement("SELECT `transcode_id`, `file`, `format` FROM `transcodes` "
				+ "WHERE `master` = ? AND `transcodes`.`format` IN (?"+addnFormats+");")) {
			int i = 1;
			ps.setString(i++, master);
			ps.setString(i++, format.name());
			for (Shortcut sc : format.shortcuts()) {
				ps.setString(i++, sc.source().name());
			}
			try (var rs = ps.executeQuery()) {
				if (rs.first()) {
					String id = rs.getString("transcodes.format");
					if (!format.name().equals(id)) {
						for (Shortcut sc : format.shortcuts()) {
							if (sc.source().name().equals(id)) {
								return new FoundShortcut(sc, rs.getString("transcodes.file"));
							}
						}
						return null;
					} else {
						if (Partyflow.storage.blobExists(Partyflow.storageContainer, rs.getString("transcodes.file"))) {
							if (updateLastDownload) {
								try (PreparedStatement ps2 = c.prepareStatement("UPDATE `transcodes` SET `last_downloaded` = NOW() WHERE `transcode_id` = ?;")) {
									ps2.setInt(1, rs.getInt("transcode_id"));
									ps2.execute();
								}
							}
							return new FoundTranscode(rs.getString("transcodes.file"));
						} else {
							log.warn("A transcode of {} {} to {} has gone missing!", kind, slug, format.name());
							try (PreparedStatement ps2 = c.prepareStatement("DELETE FROM `transcodes` WHERE `transcode_id` = ?;")) {
								ps2.setInt(1, rs.getInt("transcode_id"));
								ps2.executeUpdate();
							}
							return null;
						}
					}
				} else {
					return null;
				}
			}
		}
	}

}
