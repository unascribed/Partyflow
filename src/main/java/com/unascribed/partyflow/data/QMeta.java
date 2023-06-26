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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;

import static com.unascribed.partyflow.data.util.QueryHelper.*;

public enum QMeta {
	site_name("", 4096, null),
	site_description("Description is missing!", 65536, null),
	background_color("#263238", 7, "#[0-9A-Fa-f]{6}"),
	foreground_color("#ECEFF1", 7, "#[0-9A-Fa-f]{6}"),
	primary_color("#AA22BB", 7, "#[0-9A-Fa-f]{6}"),
	secondary_color("#6633CC", 7, "#[0-9A-Fa-f]{6}"),
	accent_color("#D500F9", 7, "#[0-9A-Fa-f]{6}"),
	;
	
	private final String camelKey;
	private final String def;
	private final int reasonableLength;
	private final Pattern pattern;

	QMeta(String def, int reasonableLength, String regex) {
		this.def = def;
		var bldr = new StringBuilder();
		for (var part : name().split("_")) {
			part = part.toLowerCase(Locale.ROOT);
			if (bldr.length() == 0) {
				bldr.append(part);
			} else {
				bldr.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
				bldr.append(part.substring(1));
			}
		}
		this.camelKey = bldr.toString();
		this.reasonableLength = reasonableLength;
		this.pattern = regex == null ? null : Pattern.compile("^"+regex+"$");
	}

	// These meta keys are really high-traffic, so caching them is extremely worthwhile
	// No reason to use a map structure, as we have continuous small integer keys by virtue of being an enum
	
	private static final Logger log = LoggerFactory.getLogger(QMeta.class);
	@SuppressWarnings("unchecked")
	private static final List<Optional<String>> cache = new CopyOnWriteArrayList<>(new Optional[values().length]);
	
	public Optional<String> peek() {
		var o = cache.get(ordinal());
		// racey. doesn't matter for our use case
		if (o == null) {
			o = getFromDb(this);
			cache.set(ordinal(), o);
		}
		return o;
	}
	
	public String get() {
		if (def == null) return peek().get();
		return peek().orElse(def);
	}
	
	public String camelKey() {
		return camelKey;
	}
	
	public int reasonableLength() {
		return reasonableLength;
	}
	
	public void set(String value) throws SQLException {
		if (pattern != null && !pattern.matcher(value).matches())
			throw new IllegalArgumentException(value+" does not match validation regex "+pattern+" for meta key "+name());
		QMeta.set(name(), value);
		cache.set(ordinal(), Optional.of(value));
	}

	protected static void set(String key, String value) throws SQLException {
		// assignment is so this will fail to compile if a new driver is added
		// really wish _ as an identifier was implemented already
		int dummy = switch (Partyflow.config.database.driver) {
			case mariadb ->
				update("INSERT INTO `meta` (`name`, `value`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `value` = ?;", key, value, value);
			case h2 ->
				update("MERGE INTO `meta` KEY(`name`) VALUES(?, ?);", key, value);
		};
	}
	
	protected static Optional<String> getFromDb(QMeta key) {
		try (var rs = select("SELECT `value` FROM `meta` WHERE `name` = ?;", key.name())) {
			if (rs.first()) {
				return Optional.of(rs.getString("value"));
			} else {
				return Optional.empty();
			}
		} catch (SQLException e) {
			log.error("Unexpected SQL error while attempting to retrieve meta key {}", key, e);
			return Optional.empty();
		}
	}
	
	public static void populate() {
		try (var rs = select("SELECT `name`, `value` FROM `meta`;")) {
			var lk = new HashMap<String, Integer>();
			for (var v : values()) {
				lk.put(v.name(), v.ordinal());
				cache.set(v.ordinal(), Optional.empty());
			}
			while (rs.next()) {
				var idx = lk.get(rs.getString("name"));
				if (idx != null) {
					cache.set(idx, Optional.of(rs.getString("value")));
				}
			}
		} catch (SQLException e) {
			log.error("Unexpected SQL error while attempting to prepopulate meta values", e);
		}
	}
	
	public static void purge() {
		cache.clear();
	}
	
}
