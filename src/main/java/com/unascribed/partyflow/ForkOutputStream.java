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
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An OutputStream that writes synchronously to its "left" stream, then asynchronously to its "right"
 * stream. Errors thrown by the "right" stream will collapse the fork to just a passthrough to the
 * "left" and stop writing to the "right" stream.
 */
public class ForkOutputStream extends OutputStream {

	private static final Logger log = LoggerFactory.getLogger(ForkOutputStream.class);
	
	private final ExecutorService exec = new SimpleSingleThreadExecutor(toString());
	
	private final OutputStream left, right;
	private volatile boolean rightForkDead = false;

	public ForkOutputStream(OutputStream left, OutputStream right) {
		this.left = left;
		this.right = right;
	}

	@Override
	public void write(int b) throws IOException {
		left.write(b);
		if (!rightForkDead) {
			exec.execute(() -> {
				if (rightForkDead) return;
				try {
					right.write(b);
				} catch (Throwable e) {
					rightForkDead = true;
					exec.shutdown();
					log.debug("Error while writing to right fork {}", right, e);
				}
			});
		}
	}

	@Override
	public void write(byte[] b) throws IOException {
		left.write(b);
		if (!rightForkDead) {
			byte[] bc = b.clone();
			exec.execute(() -> {
				if (rightForkDead) return;
				try {
					right.write(bc);
				} catch (Throwable e) {
					rightForkDead = true;
					exec.shutdown();
					log.debug("Error while writing to right fork {}", right, e);
				}
			});
		}
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		left.write(b, off, len);
		if (!rightForkDead) {
			byte[] bc = Arrays.copyOfRange(b, off, off+len);
			exec.execute(() -> {
				if (rightForkDead) return;
				try {
					right.write(bc);
				} catch (Throwable e) {
					rightForkDead = true;
					exec.shutdown();
					log.debug("Error while writing to right fork {}", right, e);
				}
			});
		}
	}

	@Override
	public void flush() throws IOException {
		left.flush();
		if (!rightForkDead) {
			exec.execute(() -> {
				if (rightForkDead) return;
				try {
					right.flush();
				} catch (Throwable e) {
					rightForkDead = true;
					exec.shutdown();
					log.debug("Error while flushing right fork {}", right, e);
				}
			});
		}
	}

	@Override
	public void close() throws IOException {
		left.close();
		try {
			if (!exec.isTerminated()) exec.shutdownNow().forEach(Runnable::run);
			right.close();
		} catch (Throwable e) {
			log.debug("Error while closing right fork {}", right, e);
		}
	}

}
