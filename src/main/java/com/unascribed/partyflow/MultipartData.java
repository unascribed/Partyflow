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

import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.server.MultiPartFormInputStream;

import jakarta.servlet.http.Part;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;

public class MultipartData {

	private final MultiPartFormInputStream delegate;

	public MultipartData(MultiPartFormInputStream delegate) {
		this.delegate = delegate;
	}

	public Part getPart(String name) throws IOException {
		return delegate.getPart(name);
	}

	public List<Part> getAllParts(String name) throws IOException {
		List<Part> li = Lists.newArrayList();
		for (Part p : delegate.getParts()) {
			if (p.getName().equals(name)) {
				li.add(p);
			}
		}
		return li;
	}

	public String getPartAsString(String name, int limit) throws IOException {
		Part part = getPart(name);
		if (part == null) return null;
		return new String(Partyflow.readWithLimit(part.getInputStream(), limit), Charsets.UTF_8);
	}

}
