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

package com.unascribed.partyflow.handler.frontend;

import java.util.HashMap;

import com.unascribed.partyflow.data.QMeta;
import com.unascribed.partyflow.handler.util.MustacheHandler;

public class ColorsHandler extends MustacheHandler {

	public ColorsHandler() {
		super("assets/_colors.hbs.css", req -> {
			var map = new HashMap<String, String>();
			map.put("body", ".color-sample");
			for (var m : QMeta.values()) {
				var p = req.getParameter(m.name());
				if (p != null) {
					map.put(m.camelKey(), p);
				}
			}
			return map;
		});
	}

}
