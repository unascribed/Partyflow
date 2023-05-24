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

package com.unascribed.partyflow.logic;

import java.util.UUID;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.logic.SessionHelper.Session;
import com.unascribed.partyflow.util.Services;

import com.google.common.collect.Maps;

public class CSRF {
	private static final Logger log = LoggerFactory.getLogger(CSRF.class);

	private static final ConcurrentMap<String, Long> tokens = Maps.newConcurrentMap();

	public static String allocate(Session s) {
		if (s.isEmpty()) throw new IllegalArgumentException("Cannot allocate a CSRF token for a guest session");
		String token = Partyflow.randomString(Services.secureRandom, 32);
		tokens.put(s.sessionId().get()+":"+token, System.currentTimeMillis()+(30*60*1000));
		return token;
	}

	public static boolean validate(Session s, String token) {
		return validate(s.sessionId().orElse(null), token);
	}

	public static boolean validate(UUID sessionId, String token) {
		if (token == null) return false;
		if (sessionId == null) return false;
		Long l = tokens.remove(sessionId+":"+token);
		return l != null && l > System.currentTimeMillis();
	}
	
	public static void cleanup() {
		var iter = tokens.values().iterator();
		long now = System.currentTimeMillis();
		int removed = 0;
		while (iter.hasNext()) {
			if (iter.next() < now) {
				iter.remove();
				removed++;
			}
		}
		if (removed > 0) {
			log.debug("Pruned {} expired CSRF token{}", removed, removed == 1 ? "" : "s");
		}
	}

}
