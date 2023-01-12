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

public class TranscodeTrackHandler extends AbstractTranscodeHandler {

	public TranscodeTrackHandler() {
		super("track", """
				SELECT `tracks`.`master` AS `master`, `tracks`.`title` AS `title`, `releases`.`title` AS `release_title`,
						`users`.`display_name` AS `creator`, `releases`.`published` AS `published`, `releases`.`release_id` AS `release_id`,
						`track_id`, `releases`.`peak` AS `album_peak`, `releases`.`loudness` AS `album_loudness`,
						`tracks`.`loudness` AS `track_loudness`, `tracks`.`peak` AS `track_peak`,
						`tracks`.`art` AS `art`, `releases`.`art` AS `fallback_art`,
						EXTRACT(YEAR FROM `tracks`.`created_at`) AS `year`, `tracks`.`track_number` AS `track_number`,
						`lyrics`
				FROM `tracks`
					JOIN `releases` ON `tracks`.`release_id` = `releases`.`release_id`
					JOIN `users` ON `releases`.`user_id` = `users`.`user_id`
				WHERE `tracks`.`slug` = ? AND (`releases`.`published` = true OR {});""");
	}

}
