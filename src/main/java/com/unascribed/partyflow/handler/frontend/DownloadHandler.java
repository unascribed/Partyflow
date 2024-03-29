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

package com.unascribed.partyflow.handler.frontend;

import java.io.File;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jclouds.blobstore.domain.BlobMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.config.TranscodeFormat;
import com.unascribed.partyflow.handler.util.MustacheHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.UserVisibleException;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.Storage;
import com.unascribed.partyflow.logic.URLs;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;

public class DownloadHandler extends SimpleHandler implements GetOrHead {

	private static final File WORK_DIR = new File(System.getProperty("java.io.tmpdir"), "partyflow/work");
	private static final Splitter SLASH_SPLITTER = Splitter.on('/').limit(2);
	
	private static final ImmutableMap<String, String> QUERIES_BY_KIND = ImmutableMap.of(
			"release", "SELECT `title`, `subtitle`, `users`.`display_name` AS `creator`, `art`, NULL AS `fallback_art`, `release_id`, NULL as `duration` "
					+ "FROM `releases` "
						+ "JOIN `users` ON `users`.`user_id` = `releases`.`user_id` "
					+ "WHERE `slug` = ? AND (`published` = true OR {});",
			
			"track", "SELECT `tracks`.`title` AS `title`, `tracks`.`subtitle` AS `subtitle`, "
						+ "`users`.`display_name` AS `creator`, `tracks`.`art` AS `art`, `releases`.`art` AS `fallback_art`, "
						+ "`duration`, `master` "
					+ "FROM `tracks` "
						+ "JOIN `users` ON `users`.`user_id` = `releases`.`user_id` "
						+ "JOIN `releases` ON `releases`.`release_id` = `tracks`.`release_id` "
				+ "WHERE `tracks`.`slug` = ? AND (`published` = true OR {});"
		);

	private static final Logger log = LoggerFactory.getLogger(DownloadHandler.class);

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		Iterator<String> split = SLASH_SPLITTER.split(path).iterator();
		String _kind = split.next();
		String _slug = split.next();
		String masterQuery = QUERIES_BY_KIND.get(_kind);
		if (masterQuery == null) {
			throw new UserVisibleException(HTTP_400_BAD_REQUEST, "Kind must be one of "+Joiner.on(", ").join(QUERIES_BY_KIND.keySet()));
		}
		var s = SessionHelper.get(req);
		String _title;
		String _subtitle;
		String _creator;
		String _art;
		long _duration;
		List<String> masters = new ArrayList<>();
		String permissionQuery = (s.isEmpty() ? "false" : "`releases`.`user_id` = ?");
		try (var c = Partyflow.sql.getConnection(); var ps = c.prepareStatement(masterQuery.replace("{}", permissionQuery))) {
			ps.setString(1, _slug);
			if (s.isPresent()) ps.setInt(2, s.userId().getAsInt());
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.first()) {
					_title = rs.getString("title");
					_subtitle = rs.getString("subtitle");
					_creator = rs.getString("creator");
					_art = rs.getString("art") == null ? rs.getString("fallback_art") : rs.getString("art");
					long duration = rs.getLong("duration");
					if (rs.wasNull()) {
						try (PreparedStatement ps2 = c.prepareStatement("SELECT `duration`, `master` FROM `tracks` WHERE `release_id` = ?;")) {
							ps2.setLong(1, rs.getLong("release_id"));
							try (ResultSet rs2 = ps2.executeQuery()) {
								while (rs2.next()) {
									duration += rs2.getLong("duration");
									masters.add(rs2.getString("master"));
								}
							}
						}
					} else {
						masters.add(rs.getString("master"));
					}
					_duration = duration;
				} else {
					res.sendError(HTTP_404_NOT_FOUND);
					return;
				}
			}
		}
		long masterSize = masters.stream()
				.unordered().parallel()
				.map(Storage::blobMetadata)
				.filter(Objects::nonNull)
				.map(BlobMetadata::getSize)
				.filter(Objects::nonNull)
				.reduce((a, b) -> a + b)
				.orElse(0L);
		List<TranscodeFormat> otherFormats = TranscodeFormat.formats.stream()
				.filter(tf -> tf.available() && tf.usage().canDownload())
				.collect(Collectors.toCollection(ArrayList::new));
		String ua = Strings.nullToEmpty(req.getHeader("User-Agent"));
		List<TranscodeFormat> suggestedFormats = otherFormats.stream()
				.filter(tf -> tf.suggested(ua))
				.toList();
		otherFormats.removeAll(suggestedFormats);
		Function<TranscodeFormat, Object> munger = tf -> new Object() {
			String name = tf.publicName();
			String display_name = tf.displayName();
			String subtitle = tf.subtitle();
			String description = tf.description();
			String icon = tf.icon();
			String mimetype = tf.mimeType();
			String size = formatBytes((long)tf.estimateSize(_duration, masterSize));
			String clazz = tf.uncompressed() ? " uncompressed" : tf.lossless() ? " lossless" : "";
		};
		MustacheHandler.serveTemplate(req, res, "download.hbs.html", new Object() {
			String title = _title;
			String subtitle = _subtitle;
			String creator = _creator;
			String kind = _kind;
			String kinds = "release".equals(kind) ? "releases" : "track";
			String slug = _slug;
			String art = URLs.art(_art);
			String download_url = URLs.relative("transcode/"+("release".equals(kind) ? "release-zip" : "track")+"/"+slug);
			List<Object> other_formats = otherFormats.stream().map(munger).toList();
			List<Object> suggested_formats = suggestedFormats.stream().map(munger).toList();
		});
	}
	
	private static String formatBytes(double bytes) {
		String prefixes = " KMGTPE";
		int idx = 0;
		while (bytes > 1024) {
			bytes /= 1024;
			idx++;
			if (idx >= prefixes.length()-1) break;
		}
		if (idx == 0) return String.format("%.0f <small>bytes</small>", bytes);
		return String.format("%.1f <small>%ciB</small>", bytes, prefixes.charAt(idx));
	}

}
