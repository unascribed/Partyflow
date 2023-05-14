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

public class Version {

	// almost semver
	public static final int MAJOR = 0;
	public static final int MINOR = 1;
	public static final int PATCH = 0;
	public static final String VARIANT = ""; // a fork/branch name should go here
	public static final String BUILD = "";

	// usage of == on strings is intentional to allow this to be evaluated at compile-time
	@SuppressWarnings("unused")
	public static final String FULL = MAJOR+"."+MINOR+(PATCH > 0 ? "."+PATCH : "")+(VARIANT == "" ? "" : "-"+VARIANT)+(BUILD == "" ? "" : "+"+BUILD);

}
