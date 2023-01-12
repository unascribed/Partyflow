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

package com.unascribed.partyflow.util;

import java.io.IOException;
import java.io.InputStream;

import javax.annotation.WillClose;
import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;

public class MoreByteStreams {

	public static byte[] consume(@WillClose InputStream in) throws IOException {
		try (in) { return ByteStreams.toByteArray(in); }
	}
	
	public static String slurp(@WillClose InputStream in) throws IOException {
		return new String(consume(in), Charsets.UTF_8);
	}
	
}
