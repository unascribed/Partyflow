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

import org.jclouds.blobstore.domain.Blob;

import com.google.common.base.Optional;
import com.google.common.io.ByteSource;

public class BlobByteSource extends ByteSource {

	private final Blob underlying;

	public BlobByteSource(Blob underlying) {
		this.underlying = underlying;
	}
	
	@Override
	public InputStream openStream() throws IOException {
		return underlying.getPayload().openStream();
	}
	
	@Override
	public Optional<Long> sizeIfKnown() {
		return Optional.fromNullable(underlying.getMetadata().getSize());
	}
	
}
