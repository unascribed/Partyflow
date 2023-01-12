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

package com.unascribed.partyflow.handler.frontend.transcode;

public class TranscodeReleaseHandler extends AbstractTranscodeHandler {

	public TranscodeReleaseHandler() {
		super("release", """
				SELECT `concat_master` AS `master`, `title`, NULL as `release_title`, `users`.`display_name` AS `creator`,
						`releases`.`published` AS `published`, `release_id`, NULL as `track_id`,
						`releases`.`peak` AS `album_peak`, `releases`.`loudness` AS `album_loudness`,
						`releases`.`peak` AS `track_peak`, `releases`.`loudness` AS `track_loudness`,
						`art`, NULL AS `fallback_art`, NULL AS `track_number`, NULL AS `lyrics`,
						EXTRACT(YEAR FROM `releases`.`created_at`) AS `year`
				FROM `releases`
					JOIN `users` ON `users`.`user_id` = `releases`.`user_id`
				WHERE `slug` = ? AND (`published` = true OR {});""");
	}

}
