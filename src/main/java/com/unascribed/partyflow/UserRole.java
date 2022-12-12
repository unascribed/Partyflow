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

package com.unascribed.partyflow;

import java.util.Arrays;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

public enum UserRole {
	// TODO it'd be nice to have some kind of ACL instead of this hardcoded system
	GUEST(-1),
	USER(0),
	CREATOR(10),
	MODERATOR(100),
	ADMIN(255), // you can't have more permissions than "all"
	;
	private static final Logger log = LoggerFactory.getLogger(UserRole.class);
	
	private static final ImmutableMap<Integer, UserRole> BY_ID = Arrays.stream(values())
			.collect(ImmutableMap.toImmutableMap(UserRole::id, Function.identity()));
	
	private final int id;
	UserRole(int id) { this.id = id; }
	
	public int id() { return id; }
	
	public boolean admin() {
		return this == ADMIN;
	}

	public static UserRole byId(int id) {
		var res = BY_ID.get(id);
		if (res == null) {
			log.warn("Unknown role ID {} - defaulting to user", id);
			return USER;
		}
		return res;
	}
}