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

import java.util.Arrays;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.logic.permission.Grant;
import com.unascribed.partyflow.logic.permission.PermissionNode;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

public enum UserRole {
	// TODO allow customization
	GUEST(-1),
	USER(0),
	CREATOR(10,
		"release.create"
	),
	MODERATOR(100,
		"moderate.bypass"
	),
	ADMIN(255, "*"), // 255 as you can't have more permissions than "all"
	;
	private static final Logger log = LoggerFactory.getLogger(UserRole.class);
	
	private static final ImmutableMap<Integer, UserRole> BY_ID = Arrays.stream(values())
			.collect(ImmutableMap.toImmutableMap(UserRole::id, Function.identity()));
	
	private final int id;
	private final ImmutableSet<PermissionNode> grantedPermissions;
	
	UserRole(int id, String... grants) {
		this.id = id;
		if (grants.length == 0) {
			this.grantedPermissions = ImmutableSet.of();
		} else {
			var tmpGrants = Arrays.stream(grants)
					.map(Grant::of).toList();
			this.grantedPermissions = PermissionNode.VALUES.stream()
					.filter(p -> tmpGrants.stream().anyMatch(g -> g.grants(p)))
					.collect(Sets.toImmutableEnumSet());
		}
	}
	
	public int id() { return id; }

	public boolean grants(PermissionNode p) {
		return grantedPermissions.contains(p);
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