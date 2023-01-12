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
import java.io.UncheckedIOException;
import java.sql.SQLException;

public class Unchecked {

	public interface CheckedRunnable {
		void run() throws SQLException, IOException;
	}
	public interface CheckedCallable<T> {
		T call() throws SQLException, IOException;
	}
	
	public static void run(CheckedRunnable r) {
		try {
			r.run();
		} catch (SQLException e) {
			throw new UncheckedSQLException(e);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
	public static <T> T call(CheckedCallable<T> c) {
		try {
			return c.call();
		} catch (SQLException e) {
			throw new UncheckedSQLException(e);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
	
}
