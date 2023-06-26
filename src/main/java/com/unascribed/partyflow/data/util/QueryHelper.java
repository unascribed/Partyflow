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

package com.unascribed.partyflow.data.util;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.stream.Stream;

public class QueryHelper extends QBase {

	public static int update(String query) throws SQLException {
		return QBase.update(query);
	}

	public static int update(String query, Object... values) throws SQLException {
		return QBase.update(query, values);
	}

	public static int update(String query, Record values) throws SQLException {
		return QBase.update(query, values);
	}

	public static ResultSet select(String query) throws SQLException {
		return QBase.select(query);
	}

	public static ResultSet select(String query, Object... values) throws SQLException {
		return QBase.select(query, values);
	}

	public static PreparedStatement prepare(Connection c, String query, Record r) throws SQLException {
		return QBase.prepare(c, query, r);
	}

	public static String columnsForRecord(String table, Class<? extends Record> type) {
		return QBase.columnsForRecord(table, type);
	}

	public static void setObjectExt(PreparedStatement s, int idx, Object v) throws SQLException {
		QBase.setObjectExt(s, idx, v);
	}

	public static <R extends Record> Stream<R> unpack(Class<R> type, ResultSet rs) {
		return QBase.unpack(type, rs);
	}

	public static <R extends Record> R unpackOne(Class<R> type, ResultSet rs) {
		return QBase.unpackOne(type, rs);
	}

	public static <R extends Record> R unpackOne(Class<R> type, ResultSet rs, Object... preface) {
		return QBase.unpackOne(type, rs, preface);
	}

	public static Connection conn() throws SQLException {
		return QBase.conn();
	}
	
}
