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

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.Date;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLType;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Spliterator;
import java.util.Spliterators.AbstractSpliterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.annotation.WillCloseWhenClosed;

import org.h2.jdbc.JdbcClob;

import com.google.errorprone.annotations.MustBeClosed;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.util.UncheckedSQLException;

import com.google.common.io.CharStreams;

public class QBase {
	
	@Documented @Target(ElementType.RECORD_COMPONENT) @Retention(RetentionPolicy.RUNTIME)
	public @interface Column { String value(); }
	
	@Documented @Target(ElementType.RECORD_COMPONENT) @Retention(RetentionPolicy.RUNTIME)
	public @interface SubstNull { String value(); }

	protected static int update(String query) throws SQLException {
		try (var c = conn(); var s = c.createStatement()) {
			return s.executeUpdate(query);
		}
	}

	protected static int update(String query, Object... values) throws SQLException {
		try (var c = conn(); var s = c.prepareStatement(query)) {
			for (int i = 0; i < values.length; i++) {
				setObjectExt(s, i+1, values[i]);
			};
			return s.executeUpdate();
		}
	}

	protected static int update(String query, Record values) throws SQLException {
		try (var c = conn(); var s = prepare(c, query, values)) {
			return s.executeUpdate();
		}
	}
	

	@MustBeClosed
	protected static ResultSet select(String query) throws SQLException {
		var c = conn(); var s = c.createStatement();
		return new CascadingResultSet(s.executeQuery(query), s, c);
	}

	@MustBeClosed
	protected static ResultSet select(String query, Object... values) throws SQLException {
		var c = conn(); var s = c.prepareStatement(query);
		for (int i = 0; i < values.length; i++) {
			setObjectExt(s, i+1, values[i]);
		};
		return new CascadingResultSet(s.executeQuery(), s, c);
	}
	
	@MustBeClosed
	protected static PreparedStatement prepare(Connection c, String query, Record r) throws SQLException {
		var type = r.getClass();
		var cmps = type.getRecordComponents();
		if (query.contains("{columns}")) {
			query = query.replace("{columns}", columnsForRecord("", r.getClass()));
		}
		List<Consumer<PreparedStatement>> preparer = List.of();
		int valuesIdx = query.indexOf("{values}");
		if (valuesIdx != -1) {
			int firstIdx = (int)query.substring(valuesIdx+1).chars()
					.filter(i -> i == '?')
					.count();
			var values = new StringBuilder();
			preparer = new ArrayList<Consumer<PreparedStatement>>();
			for (int i = 0; i < cmps.length; i++) {
				var cmp = cmps[i];
				Object v;
				try {
					v = cmp.getAccessor().invoke(r);
				} catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
					throw new AssertionError(e);
				}
				if (i != 0) {
					values.append(",");
				}
				SubstNull subst;
				if (v == null && (subst = cmp.getAnnotation(SubstNull.class)) != null) {
					values.append(subst.value());
				} else {
					int idx = firstIdx+i+1;
					preparer.add(s -> {
						try {
							setObjectExt(s, idx, v);
						} catch (SQLException e) {
							throw new UncheckedSQLException(e);
						}
					});
					values.append("?");
				}
			};
			query = query.replace("{values}", values.toString());
		}
		var s = c.prepareStatement(query);
		preparer.forEach(p -> p.accept(s));
		return s;
	}

	protected static String columnsForRecord(String table, Class<? extends Record> type) {
		return Arrays.stream(type.getRecordComponents())
				.map(t -> getColumnName(table, t, true))
				.collect(Collectors.joining(","));
	}
	
	protected static void setObjectExt(PreparedStatement s, int idx, Object v) throws SQLException {
		if (v instanceof OptionalInt oi) {
			if (oi.isPresent()) {
				s.setInt(idx, oi.getAsInt());
			} else {
				s.setNull(idx, Types.INTEGER);
			}
		} else {
			s.setObject(idx, v);
		}
	}

	@MustBeClosed
	protected static <R extends Record> Stream<R> unpack(Class<R> type, @WillCloseWhenClosed ResultSet rs) {
		return stream(rs).map(it -> unpackOne(type, it));
	}
	
	private static final Pattern CAMEL_CASE_PATTERN = Pattern.compile("([a-z])([A-Z])");
	
	protected static <R extends Record> R unpackOne(Class<R> type, ResultSet rs) {
		return unpackOne(type, rs, (Object[])null);
	}
	
	@SuppressWarnings("unchecked") // final cast is safe
	protected static <R extends Record> R unpackOne(Class<R> type, ResultSet rs, Object... preface) {
		var cmps = type.getRecordComponents();
		var args = new Object[cmps.length];
		for (int i = 0; i < cmps.length; i++) {
			var cmp = cmps[i];
			var colName = getColumnName("", cmp, false);
			var cmpt = cmp.getType();
			Object o;
			if (preface != null && preface.length > i) {
				o = preface[i];
			} else {
				try {
					o = rs.getObject(colName);
					if (cmpt == OptionalInt.class) {
						if (o == null) {
							o = OptionalInt.empty();
						} else if (o instanceof Number n) {
							o = OptionalInt.of(n.intValue());
						} else {
							throw new ClassCastException("Column `"+colName+"` was of type "+o.getClass().getSimpleName()+", but a number was expected");
						}
					} else if (cmpt == OptionalLong.class) {
						if (o == null) {
							o = OptionalLong.empty();
						} else if (o instanceof Number n) {
							o = OptionalLong.of(n.longValue());
						} else {
							throw new ClassCastException("Column `"+colName+"` was of type "+o.getClass().getSimpleName()+", but a number was expected");
						}
					} else if (o instanceof Number n && (Number.class.isAssignableFrom(cmpt) || (cmpt != boolean.class && cmpt.isPrimitive()))) {
						if (cmpt == byte.class || cmpt == Byte.class) {
							o = n.byteValue();
						} else if (cmpt == short.class || cmpt == Short.class) {
							o = n.shortValue();
						} else if (cmpt == int.class || cmpt == Integer.class) {
							o = n.intValue();
						} else if (cmpt == long.class || cmpt == Long.class) {
							o = n.longValue();
						} else if (cmpt == float.class || cmpt == Float.class) {
							o = n.floatValue();
						} else if (cmpt == double.class || cmpt == Double.class) {
							o = n.doubleValue();
						} else if (!cmpt.isInstance(o)) {
							throw new ClassCastException("Column `"+colName+"` was of type "+o.getClass().getSimpleName()+", but "+cmpt.getSimpleName()+" was expected");
						}
					} else if (o instanceof JdbcClob clob && CharSequence.class.isAssignableFrom(cmpt)) {
						try (var r = clob.getCharacterStream()) {
							o = CharStreams.toString(r);
						}
					} else if (o != null && !(o instanceof Boolean && cmpt == boolean.class) && !cmpt.isInstance(o)) {
						throw new ClassCastException("Column `"+colName+"` was of type "+o.getClass().getSimpleName()+", but "+cmpt.getSimpleName()+" was expected");
					} else if (o == null && cmpt.isPrimitive()) {
						throw new NullPointerException("Column `"+colName+"` was null, but "+cmpt.getSimpleName()+" was expected");
					}
				} catch (SQLException e) {
					throw new UncheckedSQLException(e);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			}
			args[i] = o;
		}
		try {
			return (R)getCanonicalConstructor(type).invokeWithArguments(args);
		} catch (Throwable e) {
			throw new AssertionError(e);
		}
	}

	private static String getColumnName(String table, RecordComponent cmp, boolean ticks) {
		String t = ticks ? "`" : "";
		var col = cmp.getAnnotation(Column.class);
		String name;
		if (col != null) {
			name = col.value();
		} else {
			name = CAMEL_CASE_PATTERN.matcher(cmp.getName())
					.replaceAll("$1_$2").replace("$", ".").toLowerCase(Locale.ROOT);
		}
		if (name.contains(".")) return t+name.replace(".", t+"."+t)+t;
		return (table.isEmpty() ? "" : t+table+t+".") + t+name+t;
	}

	private static final Map<Class<? extends Record>, MethodHandle> canonicalConstructors = new ConcurrentHashMap<>();
	
	private static MethodHandle getCanonicalConstructor(Class<? extends Record> type) {
		return canonicalConstructors.computeIfAbsent(type, QBase::computeCanonicalConstructor);
	}
	
	private static MethodHandle computeCanonicalConstructor(Class<? extends Record> type) {
		try {
			return MethodHandles.publicLookup().findConstructor(type,
						MethodType.methodType(void.class, Arrays.stream(type.getRecordComponents())
							.map(RecordComponent::getType)
							.toArray(Class<?>[]::new)));
		} catch (NoSuchMethodException | IllegalAccessException e) {
			throw new AssertionError(e);
		}
	}

	private static Stream<ResultSet> stream(ResultSet rs) {
		return StreamSupport.stream(new AbstractSpliterator<ResultSet>(Long.MAX_VALUE, Spliterator.ORDERED) {
			@Override
			public boolean tryAdvance(Consumer<? super ResultSet> action) {
				try {
					boolean n = rs.next();
					if (n) action.accept(rs);
					return n;
				} catch (SQLException e) {
					throw new UncheckedSQLException(e);
				}
			}
		}, false).onClose(unchecked(rs::close));
	}

	private interface SQLRunnable {
		void run() throws SQLException;
	}
	
	private static Runnable unchecked(SQLRunnable r) {
		return () -> {
			try {
				r.run();
			} catch (SQLException e) {
				throw new UncheckedSQLException(e);
			}
		};
	}
	
	private static final ThreadLocal<Connection> keptConnection = new ThreadLocal<>();
	
	public static Connection begin() throws SQLException {
		var conn = Partyflow.sql.getConnection();
		keptConnection.set(conn);
		return conn;
	}

	// UTILITY //
	
	protected static Connection conn() throws SQLException {
		var kept = keptConnection.get();
		if (kept != null && !kept.isClosed()) return kept;
		keptConnection.set(null);
		return Partyflow.sql.getConnection();
	}
	
	@SuppressWarnings("deprecation") // overriding and delegating deprecated methods
	private static class CascadingResultSet implements ResultSet {
		
		private final ResultSet delegate;
		private final AutoCloseable[] others;

		public CascadingResultSet(@WillCloseWhenClosed ResultSet delegate, @WillCloseWhenClosed AutoCloseable... others) {
			this.delegate = delegate;
			this.others = others;
		}

		@Override
		public void close() throws SQLException {
			delegate.close();
			for (var ac : others)
				try {
					ac.close();
				} catch (SQLException e) {
					throw e;
				} catch (Exception e) {
					throw new SQLException(e);
				}
		}

		// AUTOGENERATED
		
		@Override
		public <T> T unwrap(Class<T> iface) throws SQLException {
			return delegate.unwrap(iface);
		}

		@Override
		public boolean isWrapperFor(Class<?> iface) throws SQLException {
			return delegate.isWrapperFor(iface);
		}

		@Override
		public boolean next() throws SQLException {
			return delegate.next();
		}

		@Override
		public boolean wasNull() throws SQLException {
			return delegate.wasNull();
		}

		@Override
		public String getString(int columnIndex) throws SQLException {
			return delegate.getString(columnIndex);
		}

		@Override
		public boolean getBoolean(int columnIndex) throws SQLException {
			return delegate.getBoolean(columnIndex);
		}

		@Override
		public byte getByte(int columnIndex) throws SQLException {
			return delegate.getByte(columnIndex);
		}

		@Override
		public short getShort(int columnIndex) throws SQLException {
			return delegate.getShort(columnIndex);
		}

		@Override
		public int getInt(int columnIndex) throws SQLException {
			return delegate.getInt(columnIndex);
		}

		@Override
		public long getLong(int columnIndex) throws SQLException {
			return delegate.getLong(columnIndex);
		}

		@Override
		public float getFloat(int columnIndex) throws SQLException {
			return delegate.getFloat(columnIndex);
		}

		@Override
		public double getDouble(int columnIndex) throws SQLException {
			return delegate.getDouble(columnIndex);
		}

		@Override
		public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
			return delegate.getBigDecimal(columnIndex, scale);
		}

		@Override
		public byte[] getBytes(int columnIndex) throws SQLException {
			return delegate.getBytes(columnIndex);
		}

		@Override
		public Date getDate(int columnIndex) throws SQLException {
			return delegate.getDate(columnIndex);
		}

		@Override
		public Time getTime(int columnIndex) throws SQLException {
			return delegate.getTime(columnIndex);
		}

		@Override
		public Timestamp getTimestamp(int columnIndex) throws SQLException {
			return delegate.getTimestamp(columnIndex);
		}

		@Override
		public InputStream getAsciiStream(int columnIndex) throws SQLException {
			return delegate.getAsciiStream(columnIndex);
		}

		@Override
		public InputStream getUnicodeStream(int columnIndex) throws SQLException {
			return delegate.getUnicodeStream(columnIndex);
		}

		@Override
		public InputStream getBinaryStream(int columnIndex) throws SQLException {
			return delegate.getBinaryStream(columnIndex);
		}

		@Override
		public String getString(String columnLabel) throws SQLException {
			return delegate.getString(columnLabel);
		}

		@Override
		public boolean getBoolean(String columnLabel) throws SQLException {
			return delegate.getBoolean(columnLabel);
		}

		@Override
		public byte getByte(String columnLabel) throws SQLException {
			return delegate.getByte(columnLabel);
		}

		@Override
		public short getShort(String columnLabel) throws SQLException {
			return delegate.getShort(columnLabel);
		}

		@Override
		public int getInt(String columnLabel) throws SQLException {
			return delegate.getInt(columnLabel);
		}

		@Override
		public long getLong(String columnLabel) throws SQLException {
			return delegate.getLong(columnLabel);
		}

		@Override
		public float getFloat(String columnLabel) throws SQLException {
			return delegate.getFloat(columnLabel);
		}

		@Override
		public double getDouble(String columnLabel) throws SQLException {
			return delegate.getDouble(columnLabel);
		}

		@Override
		public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
			return delegate.getBigDecimal(columnLabel, scale);
		}

		@Override
		public byte[] getBytes(String columnLabel) throws SQLException {
			return delegate.getBytes(columnLabel);
		}

		@Override
		public Date getDate(String columnLabel) throws SQLException {
			return delegate.getDate(columnLabel);
		}

		@Override
		public Time getTime(String columnLabel) throws SQLException {
			return delegate.getTime(columnLabel);
		}

		@Override
		public Timestamp getTimestamp(String columnLabel) throws SQLException {
			return delegate.getTimestamp(columnLabel);
		}

		@Override
		public InputStream getAsciiStream(String columnLabel) throws SQLException {
			return delegate.getAsciiStream(columnLabel);
		}

		@Override
		public InputStream getUnicodeStream(String columnLabel) throws SQLException {
			return delegate.getUnicodeStream(columnLabel);
		}

		@Override
		public InputStream getBinaryStream(String columnLabel) throws SQLException {
			return delegate.getBinaryStream(columnLabel);
		}

		@Override
		public SQLWarning getWarnings() throws SQLException {
			return delegate.getWarnings();
		}

		@Override
		public void clearWarnings() throws SQLException {
			delegate.clearWarnings();
		}

		@Override
		public String getCursorName() throws SQLException {
			return delegate.getCursorName();
		}

		@Override
		public ResultSetMetaData getMetaData() throws SQLException {
			return delegate.getMetaData();
		}

		@Override
		public Object getObject(int columnIndex) throws SQLException {
			return delegate.getObject(columnIndex);
		}

		@Override
		public Object getObject(String columnLabel) throws SQLException {
			return delegate.getObject(columnLabel);
		}

		@Override
		public int findColumn(String columnLabel) throws SQLException {
			return delegate.findColumn(columnLabel);
		}

		@Override
		public Reader getCharacterStream(int columnIndex) throws SQLException {
			return delegate.getCharacterStream(columnIndex);
		}

		@Override
		public Reader getCharacterStream(String columnLabel) throws SQLException {
			return delegate.getCharacterStream(columnLabel);
		}

		@Override
		public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
			return delegate.getBigDecimal(columnIndex);
		}

		@Override
		public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
			return delegate.getBigDecimal(columnLabel);
		}

		@Override
		public boolean isBeforeFirst() throws SQLException {
			return delegate.isBeforeFirst();
		}

		@Override
		public boolean isAfterLast() throws SQLException {
			return delegate.isAfterLast();
		}

		@Override
		public boolean isFirst() throws SQLException {
			return delegate.isFirst();
		}

		@Override
		public boolean isLast() throws SQLException {
			return delegate.isLast();
		}

		@Override
		public void beforeFirst() throws SQLException {
			delegate.beforeFirst();
		}

		@Override
		public void afterLast() throws SQLException {
			delegate.afterLast();
		}

		@Override
		public boolean first() throws SQLException {
			return delegate.first();
		}

		@Override
		public boolean last() throws SQLException {
			return delegate.last();
		}

		@Override
		public int getRow() throws SQLException {
			return delegate.getRow();
		}

		@Override
		public boolean absolute(int row) throws SQLException {
			return delegate.absolute(row);
		}

		@Override
		public boolean relative(int rows) throws SQLException {
			return delegate.relative(rows);
		}

		@Override
		public boolean previous() throws SQLException {
			return delegate.previous();
		}

		@Override
		public void setFetchDirection(int direction) throws SQLException {
			delegate.setFetchDirection(direction);
		}

		@Override
		public int getFetchDirection() throws SQLException {
			return delegate.getFetchDirection();
		}

		@Override
		public void setFetchSize(int rows) throws SQLException {
			delegate.setFetchSize(rows);
		}

		@Override
		public int getFetchSize() throws SQLException {
			return delegate.getFetchSize();
		}

		@Override
		public int getType() throws SQLException {
			return delegate.getType();
		}

		@Override
		public int getConcurrency() throws SQLException {
			return delegate.getConcurrency();
		}

		@Override
		public boolean rowUpdated() throws SQLException {
			return delegate.rowUpdated();
		}

		@Override
		public boolean rowInserted() throws SQLException {
			return delegate.rowInserted();
		}

		@Override
		public boolean rowDeleted() throws SQLException {
			return delegate.rowDeleted();
		}

		@Override
		public void updateNull(int columnIndex) throws SQLException {
			delegate.updateNull(columnIndex);
		}

		@Override
		public void updateBoolean(int columnIndex, boolean x) throws SQLException {
			delegate.updateBoolean(columnIndex, x);
		}

		@Override
		public void updateByte(int columnIndex, byte x) throws SQLException {
			delegate.updateByte(columnIndex, x);
		}

		@Override
		public void updateShort(int columnIndex, short x) throws SQLException {
			delegate.updateShort(columnIndex, x);
		}

		@Override
		public void updateInt(int columnIndex, int x) throws SQLException {
			delegate.updateInt(columnIndex, x);
		}

		@Override
		public void updateLong(int columnIndex, long x) throws SQLException {
			delegate.updateLong(columnIndex, x);
		}

		@Override
		public void updateFloat(int columnIndex, float x) throws SQLException {
			delegate.updateFloat(columnIndex, x);
		}

		@Override
		public void updateDouble(int columnIndex, double x) throws SQLException {
			delegate.updateDouble(columnIndex, x);
		}

		@Override
		public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException {
			delegate.updateBigDecimal(columnIndex, x);
		}

		@Override
		public void updateString(int columnIndex, String x) throws SQLException {
			delegate.updateString(columnIndex, x);
		}

		@Override
		public void updateBytes(int columnIndex, byte[] x) throws SQLException {
			delegate.updateBytes(columnIndex, x);
		}

		@Override
		public void updateDate(int columnIndex, Date x) throws SQLException {
			delegate.updateDate(columnIndex, x);
		}

		@Override
		public void updateTime(int columnIndex, Time x) throws SQLException {
			delegate.updateTime(columnIndex, x);
		}

		@Override
		public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException {
			delegate.updateTimestamp(columnIndex, x);
		}

		@Override
		public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException {
			delegate.updateAsciiStream(columnIndex, x, length);
		}

		@Override
		public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException {
			delegate.updateBinaryStream(columnIndex, x, length);
		}

		@Override
		public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException {
			delegate.updateCharacterStream(columnIndex, x, length);
		}

		@Override
		public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException {
			delegate.updateObject(columnIndex, x, scaleOrLength);
		}

		@Override
		public void updateObject(int columnIndex, Object x) throws SQLException {
			delegate.updateObject(columnIndex, x);
		}

		@Override
		public void updateNull(String columnLabel) throws SQLException {
			delegate.updateNull(columnLabel);
		}

		@Override
		public void updateBoolean(String columnLabel, boolean x) throws SQLException {
			delegate.updateBoolean(columnLabel, x);
		}

		@Override
		public void updateByte(String columnLabel, byte x) throws SQLException {
			delegate.updateByte(columnLabel, x);
		}

		@Override
		public void updateShort(String columnLabel, short x) throws SQLException {
			delegate.updateShort(columnLabel, x);
		}

		@Override
		public void updateInt(String columnLabel, int x) throws SQLException {
			delegate.updateInt(columnLabel, x);
		}

		@Override
		public void updateLong(String columnLabel, long x) throws SQLException {
			delegate.updateLong(columnLabel, x);
		}

		@Override
		public void updateFloat(String columnLabel, float x) throws SQLException {
			delegate.updateFloat(columnLabel, x);
		}

		@Override
		public void updateDouble(String columnLabel, double x) throws SQLException {
			delegate.updateDouble(columnLabel, x);
		}

		@Override
		public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException {
			delegate.updateBigDecimal(columnLabel, x);
		}

		@Override
		public void updateString(String columnLabel, String x) throws SQLException {
			delegate.updateString(columnLabel, x);
		}

		@Override
		public void updateBytes(String columnLabel, byte[] x) throws SQLException {
			delegate.updateBytes(columnLabel, x);
		}

		@Override
		public void updateDate(String columnLabel, Date x) throws SQLException {
			delegate.updateDate(columnLabel, x);
		}

		@Override
		public void updateTime(String columnLabel, Time x) throws SQLException {
			delegate.updateTime(columnLabel, x);
		}

		@Override
		public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException {
			delegate.updateTimestamp(columnLabel, x);
		}

		@Override
		public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException {
			delegate.updateAsciiStream(columnLabel, x, length);
		}

		@Override
		public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException {
			delegate.updateBinaryStream(columnLabel, x, length);
		}

		@Override
		public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException {
			delegate.updateCharacterStream(columnLabel, reader, length);
		}

		@Override
		public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException {
			delegate.updateObject(columnLabel, x, scaleOrLength);
		}

		@Override
		public void updateObject(String columnLabel, Object x) throws SQLException {
			delegate.updateObject(columnLabel, x);
		}

		@Override
		public void insertRow() throws SQLException {
			delegate.insertRow();
		}

		@Override
		public void updateRow() throws SQLException {
			delegate.updateRow();
		}

		@Override
		public void deleteRow() throws SQLException {
			delegate.deleteRow();
		}

		@Override
		public void refreshRow() throws SQLException {
			delegate.refreshRow();
		}

		@Override
		public void cancelRowUpdates() throws SQLException {
			delegate.cancelRowUpdates();
		}

		@Override
		public void moveToInsertRow() throws SQLException {
			delegate.moveToInsertRow();
		}

		@Override
		public void moveToCurrentRow() throws SQLException {
			delegate.moveToCurrentRow();
		}

		@Override
		public Statement getStatement() throws SQLException {
			return delegate.getStatement();
		}

		@Override
		public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException {
			return delegate.getObject(columnIndex, map);
		}

		@Override
		public Ref getRef(int columnIndex) throws SQLException {
			return delegate.getRef(columnIndex);
		}

		@Override
		public Blob getBlob(int columnIndex) throws SQLException {
			return delegate.getBlob(columnIndex);
		}

		@Override
		public Clob getClob(int columnIndex) throws SQLException {
			return delegate.getClob(columnIndex);
		}

		@Override
		public Array getArray(int columnIndex) throws SQLException {
			return delegate.getArray(columnIndex);
		}

		@Override
		public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException {
			return delegate.getObject(columnLabel, map);
		}

		@Override
		public Ref getRef(String columnLabel) throws SQLException {
			return delegate.getRef(columnLabel);
		}

		@Override
		public Blob getBlob(String columnLabel) throws SQLException {
			return delegate.getBlob(columnLabel);
		}

		@Override
		public Clob getClob(String columnLabel) throws SQLException {
			return delegate.getClob(columnLabel);
		}

		@Override
		public Array getArray(String columnLabel) throws SQLException {
			return delegate.getArray(columnLabel);
		}

		@Override
		public Date getDate(int columnIndex, Calendar cal) throws SQLException {
			return delegate.getDate(columnIndex, cal);
		}

		@Override
		public Date getDate(String columnLabel, Calendar cal) throws SQLException {
			return delegate.getDate(columnLabel, cal);
		}

		@Override
		public Time getTime(int columnIndex, Calendar cal) throws SQLException {
			return delegate.getTime(columnIndex, cal);
		}

		@Override
		public Time getTime(String columnLabel, Calendar cal) throws SQLException {
			return delegate.getTime(columnLabel, cal);
		}

		@Override
		public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException {
			return delegate.getTimestamp(columnIndex, cal);
		}

		@Override
		public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException {
			return delegate.getTimestamp(columnLabel, cal);
		}

		@Override
		public URL getURL(int columnIndex) throws SQLException {
			return delegate.getURL(columnIndex);
		}

		@Override
		public URL getURL(String columnLabel) throws SQLException {
			return delegate.getURL(columnLabel);
		}

		@Override
		public void updateRef(int columnIndex, Ref x) throws SQLException {
			delegate.updateRef(columnIndex, x);
		}

		@Override
		public void updateRef(String columnLabel, Ref x) throws SQLException {
			delegate.updateRef(columnLabel, x);
		}

		@Override
		public void updateBlob(int columnIndex, Blob x) throws SQLException {
			delegate.updateBlob(columnIndex, x);
		}

		@Override
		public void updateBlob(String columnLabel, Blob x) throws SQLException {
			delegate.updateBlob(columnLabel, x);
		}

		@Override
		public void updateClob(int columnIndex, Clob x) throws SQLException {
			delegate.updateClob(columnIndex, x);
		}

		@Override
		public void updateClob(String columnLabel, Clob x) throws SQLException {
			delegate.updateClob(columnLabel, x);
		}

		@Override
		public void updateArray(int columnIndex, Array x) throws SQLException {
			delegate.updateArray(columnIndex, x);
		}

		@Override
		public void updateArray(String columnLabel, Array x) throws SQLException {
			delegate.updateArray(columnLabel, x);
		}

		@Override
		public RowId getRowId(int columnIndex) throws SQLException {
			return delegate.getRowId(columnIndex);
		}

		@Override
		public RowId getRowId(String columnLabel) throws SQLException {
			return delegate.getRowId(columnLabel);
		}

		@Override
		public void updateRowId(int columnIndex, RowId x) throws SQLException {
			delegate.updateRowId(columnIndex, x);
		}

		@Override
		public void updateRowId(String columnLabel, RowId x) throws SQLException {
			delegate.updateRowId(columnLabel, x);
		}

		@Override
		public int getHoldability() throws SQLException {
			return delegate.getHoldability();
		}

		@Override
		public boolean isClosed() throws SQLException {
			return delegate.isClosed();
		}

		@Override
		public void updateNString(int columnIndex, String nString) throws SQLException {
			delegate.updateNString(columnIndex, nString);
		}

		@Override
		public void updateNString(String columnLabel, String nString) throws SQLException {
			delegate.updateNString(columnLabel, nString);
		}

		@Override
		public void updateNClob(int columnIndex, NClob nClob) throws SQLException {
			delegate.updateNClob(columnIndex, nClob);
		}

		@Override
		public void updateNClob(String columnLabel, NClob nClob) throws SQLException {
			delegate.updateNClob(columnLabel, nClob);
		}

		@Override
		public NClob getNClob(int columnIndex) throws SQLException {
			return delegate.getNClob(columnIndex);
		}

		@Override
		public NClob getNClob(String columnLabel) throws SQLException {
			return delegate.getNClob(columnLabel);
		}

		@Override
		public SQLXML getSQLXML(int columnIndex) throws SQLException {
			return delegate.getSQLXML(columnIndex);
		}

		@Override
		public SQLXML getSQLXML(String columnLabel) throws SQLException {
			return delegate.getSQLXML(columnLabel);
		}

		@Override
		public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException {
			delegate.updateSQLXML(columnIndex, xmlObject);
		}

		@Override
		public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException {
			delegate.updateSQLXML(columnLabel, xmlObject);
		}

		@Override
		public String getNString(int columnIndex) throws SQLException {
			return delegate.getNString(columnIndex);
		}

		@Override
		public String getNString(String columnLabel) throws SQLException {
			return delegate.getNString(columnLabel);
		}

		@Override
		public Reader getNCharacterStream(int columnIndex) throws SQLException {
			return delegate.getNCharacterStream(columnIndex);
		}

		@Override
		public Reader getNCharacterStream(String columnLabel) throws SQLException {
			return delegate.getNCharacterStream(columnLabel);
		}

		@Override
		public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
			delegate.updateNCharacterStream(columnIndex, x, length);
		}

		@Override
		public void updateNCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
			delegate.updateNCharacterStream(columnLabel, reader, length);
		}

		@Override
		public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException {
			delegate.updateAsciiStream(columnIndex, x, length);
		}

		@Override
		public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException {
			delegate.updateBinaryStream(columnIndex, x, length);
		}

		@Override
		public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException {
			delegate.updateCharacterStream(columnIndex, x, length);
		}

		@Override
		public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException {
			delegate.updateAsciiStream(columnLabel, x, length);
		}

		@Override
		public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException {
			delegate.updateBinaryStream(columnLabel, x, length);
		}

		@Override
		public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException {
			delegate.updateCharacterStream(columnLabel, reader, length);
		}

		@Override
		public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException {
			delegate.updateBlob(columnIndex, inputStream, length);
		}

		@Override
		public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException {
			delegate.updateBlob(columnLabel, inputStream, length);
		}

		@Override
		public void updateClob(int columnIndex, Reader reader, long length) throws SQLException {
			delegate.updateClob(columnIndex, reader, length);
		}

		@Override
		public void updateClob(String columnLabel, Reader reader, long length) throws SQLException {
			delegate.updateClob(columnLabel, reader, length);
		}

		@Override
		public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException {
			delegate.updateNClob(columnIndex, reader, length);
		}

		@Override
		public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException {
			delegate.updateNClob(columnLabel, reader, length);
		}

		@Override
		public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException {
			delegate.updateNCharacterStream(columnIndex, x);
		}

		@Override
		public void updateNCharacterStream(String columnLabel, Reader reader) throws SQLException {
			delegate.updateNCharacterStream(columnLabel, reader);
		}

		@Override
		public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException {
			delegate.updateAsciiStream(columnIndex, x);
		}

		@Override
		public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException {
			delegate.updateBinaryStream(columnIndex, x);
		}

		@Override
		public void updateCharacterStream(int columnIndex, Reader x) throws SQLException {
			delegate.updateCharacterStream(columnIndex, x);
		}

		@Override
		public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException {
			delegate.updateAsciiStream(columnLabel, x);
		}

		@Override
		public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException {
			delegate.updateBinaryStream(columnLabel, x);
		}

		@Override
		public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException {
			delegate.updateCharacterStream(columnLabel, reader);
		}

		@Override
		public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException {
			delegate.updateBlob(columnIndex, inputStream);
		}

		@Override
		public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException {
			delegate.updateBlob(columnLabel, inputStream);
		}

		@Override
		public void updateClob(int columnIndex, Reader reader) throws SQLException {
			delegate.updateClob(columnIndex, reader);
		}

		@Override
		public void updateClob(String columnLabel, Reader reader) throws SQLException {
			delegate.updateClob(columnLabel, reader);
		}

		@Override
		public void updateNClob(int columnIndex, Reader reader) throws SQLException {
			delegate.updateNClob(columnIndex, reader);
		}

		@Override
		public void updateNClob(String columnLabel, Reader reader) throws SQLException {
			delegate.updateNClob(columnLabel, reader);
		}

		@Override
		public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
			return delegate.getObject(columnIndex, type);
		}

		@Override
		public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
			return delegate.getObject(columnLabel, type);
		}

		@Override
		public void updateObject(int columnIndex, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
			delegate.updateObject(columnIndex, x, targetSqlType, scaleOrLength);
		}

		@Override
		public void updateObject(String columnLabel, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException {
			delegate.updateObject(columnLabel, x, targetSqlType, scaleOrLength);
		}

		@Override
		public void updateObject(int columnIndex, Object x, SQLType targetSqlType) throws SQLException {
			delegate.updateObject(columnIndex, x, targetSqlType);
		}

		@Override
		public void updateObject(String columnLabel, Object x, SQLType targetSqlType) throws SQLException {
			delegate.updateObject(columnLabel, x, targetSqlType);
		}
		
	}
	
}
