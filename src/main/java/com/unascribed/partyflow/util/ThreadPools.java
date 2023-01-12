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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import com.unascribed.partyflow.Partyflow;

public class ThreadPools {

	public static final ExecutorService GENERIC;
	public static final ExecutorService TRANSCODE;
	
	static {
		int nproc = Runtime.getRuntime().availableProcessors();
		GENERIC = Executors.newFixedThreadPool(nproc, namedFactory("Generic pool thread"));
		int maxTranscodes = Partyflow.config.programs.maxTranscodes;
		if (maxTranscodes == 0) maxTranscodes = nproc;
		TRANSCODE = Executors.newFixedThreadPool(maxTranscodes, namedFactory("Transcode pool thread"));
	}

	private static ThreadFactory namedFactory(String name) {
		AtomicInteger counter = new AtomicInteger(0);
		return r -> {
			var t = new Thread(r, name+" #"+counter.getAndIncrement());
			t.setDaemon(true);
			return t;
		};
	}
	
}
