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

package com.unascribed.partyflow.data;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.data.util.Artful;
import com.unascribed.partyflow.data.util.Queries;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.PrimitiveSink;

import com.unascribed.partyflow.logic.SessionHelper.Session;
import com.unascribed.partyflow.util.UncheckedSQLException;
import com.unascribed.partyflow.util.Yap;

public class QReleases extends Queries {
	
	private static final Logger log = LoggerFactory.getLogger(QReleases.class);
	
	public record Release(
			String slug, long userId, String title, String subtitle, String description,
			boolean published, @SubstNull("NOW()") Date createdAt, @SubstNull("NOW()") Date lastUpdated) {}
	
	public record FullRelease(
			String slug, long userId, String title, String subtitle, String description,
			boolean published, Date createdAt, Date lastUpdated, int downloads,

			@Column("art") String artId, Date publishedAt, String concatMaster, OptionalInt peak, OptionalInt loudness,
			
			@Column("users.username") String creatorUsername, @Column("users.display_name") String creator, long releaseId) implements Artful {}
	
	private static final String COLUMNS = columnsForRecord("releases", FullRelease.class);

	public static void create(Release r) throws SQLException {
		update("INSERT INTO `releases` ({columns}) VALUES ({values});", r);
	}

	public static boolean publish(String slug, int userId, boolean published) throws SQLException {
		return update("UPDATE `releases` SET `published` = ?"+(published ? ", published_at = NOW()" : "")+" WHERE `slug` = ? AND `user_id` = ?;",
				published, slug, userId) > 0;
	}
	
	public static Optional<FullRelease> get(Session s, String slug) throws SQLException {
		Object[] args = {slug};
		String or = "";
		if (s.isPresent()) {
			or = " OR `releases`.`user_id` = ?";
			args = new Object[] {slug, s.userId()};
		}
		try (var rs = select("SELECT "+COLUMNS+" FROM `releases` "
				+ "JOIN `users` ON `users`.`user_id` = `releases`.`user_id` "
				+ "WHERE `releases`.`slug` = ? AND (`releases`.`published` = true"+or+");",
				args)) {
			if (rs.first()) {
				return Optional.of(unpackOne(FullRelease.class, rs, slug));
			} else {
				return Optional.empty();
			}
		}
	}
	
	private static List<FullRelease> get(Session s, String cols, String join, String order) throws SQLException {
		var query = new StringBuilder("SELECT `users`.`display_name`, ");
		query.append(COLUMNS);
		query.append(cols);
		query.append(" FROM `releases` JOIN `users` ON `users`.`user_id` = `releases`.`user_id` ");
		query.append(join);
		query.append("WHERE `published` = true");
		var args = new Object[0];
		if (s.isPresent()) {
			query.append(" OR `releases`.`user_id` = ?");
			args = new Object[] { s.userId().getAsInt() };
		}
		query.append(order);
		query.append(";");
		try (var stream = unpack(FullRelease.class, select(query.toString(), args))) {
			return stream.toList();
		} catch (UncheckedSQLException e) {
			throw e.getCause();
		}
	}
	
	public static List<FullRelease> getAll(Session s, int limit, int page) throws SQLException {
		return get(s, ", ", "", " ORDER BY `release_id` ASC LIMIT "+(limit+1)+" OFFSET "+((page-1)*limit));
	}
	
	public static List<FullRelease> get5MostDownloaded(Session s) throws SQLException {
		return get(s, "", "", " ORDER BY `downloads` DESC LIMIT 5");
	}
	
	public static List<FullRelease> get5Newest(Session s) throws SQLException {
		return get(s, "", "", " ORDER BY `created_at` DESC LIMIT 5");
	}
	
	public static void maybeRecordDownload(String slug, InetAddress addr) throws SQLException {
		_maybeRecordDownload("slug", slug, addr);
	}
	

	public static void maybeRecordDownload(long releaseId, InetAddress addr) throws SQLException {
		_maybeRecordDownload("release_id", releaseId, addr);
	}
	

	private static void _maybeRecordDownload(String col, Object param, InetAddress addr) throws SQLException {
		if (Partyflow.config.database.expectedTraffic == 0) {
			update("UPDATE `releases` (`downloads`) VALUES (`downloads` + 1);");
			return;
		}
		// uses a bloom filter for privacy
		if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
			Yap.BAD_PROXY.warn();
		}
		try (var rs = select("SELECT `dl_bloom` FROM `releases` WHERE `"+col+"` = ?;", param)) {
			if (rs.first()) {
				byte[] data = rs.getBytes("dl_bloom");
				BloomFilter<InetAddress> filter;
				if (data != null) {
					filter = BloomFilter.readFrom(new ByteArrayInputStream(data), QReleases::inetFunnel);
				} else {
					filter = BloomFilter.create(QReleases::inetFunnel, Partyflow.config.database.expectedTraffic);
				}
				if (filter.expectedFpp() > 0.4) {
					boolean needsReplacement;
					try {
						needsReplacement = filter.approximateElementCount() < Partyflow.config.database.expectedTraffic;
					} catch (ArithmeticException e) {
						// when fully saturated, approximateElementCount throws `ArithmeticException: input is infinite or NaN`
						needsReplacement = true;
					}
					if (needsReplacement) {
						log.debug("Reallocating saturated downloads filter for {} {}", col, param);
						filter = BloomFilter.create(QReleases::inetFunnel, Partyflow.config.database.expectedTraffic);
					} else {
						Yap.SATURATED_FILTER.warn(Partyflow.config.database.expectedTraffic);
					}
				}
				if (filter.put(addr)) {
					var baos = new ByteArrayOutputStream();
					filter.writeTo(baos);
					update("UPDATE `releases` SET `downloads` = `downloads` + 1, `dl_bloom` = ? WHERE `"+col+"` = ?;",
							baos.toByteArray(), param);
				}
			}
		} catch (IOException e) {
			throw new SQLException(e);
		}
	}
	
	private static void inetFunnel(InetAddress a, PrimitiveSink into) {
		into.putBytes(a.getAddress());
	}

}
