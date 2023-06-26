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
import java.util.zip.GZIPInputStream;

import com.google.common.io.ByteSource;
import com.google.common.io.Resources;

import io.github.martincameron.ibxm2.Channel;
import io.github.martincameron.ibxm2.IBXM;
import io.github.martincameron.ibxm2.WavInputStream;
import io.github.martincameron.ibxm2.Module;

public class IBXMByteSource extends ByteSource {

	private final ByteSource underlying;

	public IBXMByteSource(ByteSource underlying) {
		this.underlying = underlying;
	}
	
	public IBXMByteSource(String path) {
		this(Resources.asByteSource(ClassLoader.getSystemResource(path)));
	}
	
	@Override
	public InputStream openStream() throws IOException {
		var ibxm = new IBXM(new Module(new GZIPInputStream(underlying.openStream())), 48000);
		ibxm.setInterpolation(Channel.SINC);
		return new WavInputStream(ibxm);
	}
	
}
