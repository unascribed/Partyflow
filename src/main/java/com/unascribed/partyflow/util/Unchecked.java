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
import java.io.UncheckedIOException;
import java.sql.SQLException;
import java.util.concurrent.Callable;

import com.google.common.util.concurrent.UncheckedExecutionException;

public class Unchecked {

	public interface ExceptableRunnable {
		void run() throws Exception;
	}
	
	public static void run(ExceptableRunnable r) {
		try {
			r.run();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}
	
	public static <T> T call(Callable<T> c) {
		try {
			return c.call();
		} catch (Throwable t) {
			throw rethrow(t);
		}
	}

	public static RuntimeException rethrow(Throwable t) {
		if (t instanceof RuntimeException e) {
			throw e;
		} else if (t instanceof Error e) {
			throw e;
		} else if (t instanceof SQLException e) {
			throw new UncheckedSQLException(e);
		} else if (t instanceof IOException e) {
			throw new UncheckedIOException(e);
		} else {
			throw new UncheckedExecutionException(t);
		}
	}
	
}
