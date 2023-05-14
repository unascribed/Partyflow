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

package com.unascribed.partyflow.logic.permission;

import java.util.Arrays;

public sealed abstract class Grant permits Grant.Universal, Grant.Wildcard1, Grant.Wildcard, Grant.Exact {

	public static Grant of(String spec) {
		if (spec.isEmpty())
			throw new IllegalArgumentException("Empty string is not a valid grant");
		
		if ("*".equals(spec))
			return Universal.INSTANCE;
		
		if (spec.substring(0, spec.length()-1).contains("*"))
			throw new IllegalArgumentException("Middle asterisk grants are not supported");
		
		String[] parts = spec.split("\\.");
		if (parts.length == 2 && "*".equals(parts[1]))
			return new Wildcard1(spec, parts[0]);
		if (parts[parts.length-1].equals("*"))
			return new Wildcard(spec, Arrays.copyOf(parts, parts.length-1));
		return new Exact(spec);
	}
	
	private final String spec;
	
	protected Grant(String spec) {
		this.spec = spec;
	}
	
	public abstract boolean grants(PermissionNode perm);
	
	@Override
	public String toString() {
		return spec;
	}
	
	@Override
	public int hashCode() {
		return spec.hashCode();
	}
	
	@Override
	public boolean equals(Object obj) {
		return spec.equals(obj);
	}
	
	
	static final class Universal extends Grant {
		static final Universal INSTANCE = new Universal();
		private Universal() { super("*"); }
		
		@Override
		public boolean grants(PermissionNode perm) {
			return true;
		}
	}
	
	static final class Wildcard1 extends Grant {
		private final String section;

		public Wildcard1(String spec, String section) {
			super(spec);
			this.section = section;
		}
		
		@Override
		public boolean grants(PermissionNode perm) {
			return perm.parts[0].equals(section);
		}
	}
	
	static final class Wildcard extends Grant {
		private final String[] prefix;
		
		public Wildcard(String spec, String[] prefix) {
			super(spec);
			this.prefix = prefix;
		}

		@Override
		public boolean grants(PermissionNode perm) {
			for (int i = 0; i < prefix.length; i++) {
				if (!perm.parts[i].equals(prefix[i])) return false;
			}
			return true;
		}
	}
	
	static final class Exact extends Grant {
		private final String node;

		public Exact(String node) {
			super(node);
			this.node = node;
		}
		
		@Override
		public boolean grants(PermissionNode perm) {
			return perm.toString().equals(node);
		}
	}
	
}
