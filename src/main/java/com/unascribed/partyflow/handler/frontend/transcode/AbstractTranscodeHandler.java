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

package com.unascribed.partyflow.handler.frontend.transcode;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.config.TranscodeFormat;
import com.unascribed.partyflow.config.TranscodeFormat.ReplayGainData;
import com.unascribed.partyflow.config.TranscodeFormat.Shortcut;
import com.unascribed.partyflow.config.TranscodeFormat.Usage;
import com.unascribed.partyflow.data.QReleases;
import com.unascribed.partyflow.data.QTranscodes;
import com.unascribed.partyflow.data.QTranscodes.FoundShortcut;
import com.unascribed.partyflow.data.QTranscodes.FoundTranscode;
import com.unascribed.partyflow.data.QTranscodes.TranscodeFindResult;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.UserVisibleException;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.SpecialTrack;
import com.unascribed.partyflow.logic.Transcoder;
import com.unascribed.partyflow.logic.URLs;
import com.unascribed.partyflow.util.Services;

import com.google.common.base.MoreObjects;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.google.common.hash.Hashing;
import com.google.common.net.InetAddresses;

public abstract class AbstractTranscodeHandler extends SimpleHandler implements GetOrHead {

	private static final Logger log = LoggerFactory.getLogger(AbstractTranscodeHandler.class);

	private static final Table<String, TranscodeFormat, Object> mutexes = Tables.synchronizedTable(HashBasedTable.create());
	
	private final String kind, masterQuery;
	
	protected AbstractTranscodeHandler(String kind, String masterQuery) {
		this.kind = kind;
		this.masterQuery = masterQuery;
	}

	@Override
	public void getOrHead(String slug, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		Map<String, String> query = parseQuery(req);
		if (!query.containsKey("format")) {
			throw new UserVisibleException(HTTP_400_BAD_REQUEST, "Format is required");
		}
		boolean prepare = query.containsKey("prepare");
		String formatString = query.get("format");
		TranscodeFormat format = TranscodeFormat.byPublicName(formatString)
				.orElseThrow(() -> new UserVisibleException(HTTP_400_BAD_REQUEST, "Unrecognized format "+formatString));
		var s = SessionHelper.get(req);
		String permissionQuery = (s.isEmpty() ? "false" : "`releases`.`user_id` = ?");
		try (Connection c = Partyflow.sql.getConnection()) {
			String shortcutSource = null;
			Shortcut shortcut = null;
			String master;
			String title;
			String releaseTitle;
			String creator;
			String art;
			String lyrics;
			boolean published;
			Long trackId;
			Long releaseId;
			Integer trackNumber;
			ReplayGainData rgd;
			int year;
			var st = SpecialTrack.BY_SLUG.get(slug);
			if ("track".equals(kind) && st != null) {
				master = slug;
				title = st.title();
				releaseTitle = "Partyflow";
				creator = st.artist();
				art = null;
				lyrics = null;
				year = st.year();
				published = true;
				trackId = null;
				releaseId = null;
				trackNumber = null;
				rgd = st.rg();
			} else {
				try (PreparedStatement ps = c.prepareStatement(masterQuery.replace("{}", permissionQuery))) {
					ps.setString(1, slug);
					if (s.isPresent()) ps.setInt(2, s.userId().getAsInt());
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.first()) {
							master = rs.getString("master");
							title = rs.getString("title");
							releaseTitle = rs.getString("release_title");
							creator = rs.getString("creator");
							published = rs.getBoolean("published");
							releaseId = rs.getLong("release_id");
							long trackIdL = rs.getLong("track_id");
							trackId = rs.wasNull() ? null : trackIdL;
							rgd = new ReplayGainData(rs.getInt("album_loudness")/10D, rs.getInt("track_loudness")/10D,
									rs.getInt("album_peak")/10D, rs.getInt("track_peak")/10D);
							year = rs.getInt("year");
							art = rs.getString("art") == null ? rs.getString("fallback_art") : rs.getString("art");
							lyrics = rs.getString("lyrics");
							int trackNumberI = rs.getInt("track_number");
							trackNumber = rs.wasNull() ? null : trackNumberI;
						} else {
							res.sendError(HTTP_404_NOT_FOUND);
							return;
						}
					}
				}
			}
			if (master == null) {
				res.sendError(HTTP_409_CONFLICT);
				return;
			}
			String etag = "\""+Hashing.sha256().hashUnencodedChars(master)+"\"";
			res.setHeader("ETag", etag);
			if (etag.equals(req.getHeader("If-None-Match"))) {
				res.setStatus(HTTP_304_NOT_MODIFIED);
				res.getOutputStream().close();
				return;
			}
			
			if (format.usage() == Usage.DOWNLOAD && !prepare && releaseId != null) {
				try {
					var addr = InetAddresses.forString(req.getRemoteAddr().replace("[", "").replace("]", ""));
					QReleases.maybeRecordDownload(releaseId, addr);
				} catch (IllegalArgumentException e) {
					log.warn("Exception recording download", e);
				}
			}
			
			TranscodeFindResult findRes = QTranscodes.findExistingTranscode(c, !head, kind, slug, format, master);
			if (findRes instanceof FoundTranscode ft) {
				res.setHeader("Transcode-Status", "CACHED");
				if (prepare) {
					res.setStatus(HTTP_204_NO_CONTENT);
					res.getOutputStream().close();
					res.setHeader("Transcode-Result", URLs.blob(ft.blob()));
				} else {
					res.sendRedirect(URLs.blob(ft.blob()));
				}
				return;
			} else if (findRes instanceof FoundShortcut fs) {
				shortcut = fs.shortcut();
				shortcutSource = fs.srcBlob();
			}
			if (head && !prepare) {
				res.setStatus(HTTP_204_NO_CONTENT);
				res.setHeader("Transcode-Status", "UNAVAILABLE");
				res.setHeader("Comment", "Transcodes are not performed in response to HEAD requests");
				res.getOutputStream().close();
				return;
			}
			boolean direct = !prepare && format.direct();
			boolean cache = format.cache();
			if (prepare && !cache) {
				res.setStatus(HTTP_204_NO_CONTENT);
				res.setHeader("Transcode-Status", "DIRECT");
				res.getOutputStream().close();
				return;
			}
			if (direct) {
				log.debug("Streaming {} from master...", format, st == null ? "master" : "built-in track \""+st.title()+"\"");
			} else if (shortcut == null) {
				log.debug("Transcoding to {} from {}...", format, st == null ? "master" : "built-in track \""+st.title()+"\"");
			} else {
				log.debug("Remuxing to {} from {}...", format, shortcut.source());
			}
			
			if (cache) {
				Object mutex = mutexes.get(master, format);
				if (mutex != null) {
					while (mutexes.get(master, format) == mutex) {
						synchronized (mutex) {
							mutex.wait();
						}
					}
				}
			}
			
			Object mutex = new Object();
			if (cache) mutexes.put(master, format, mutex);
			final Shortcut fshortcut = shortcut;
			final String fshortcutSource = shortcutSource;
			
			Callable<String> transcoder = () -> {
				return Transcoder.performTranscode(format, kind, slug, MoreObjects.firstNonNull(fshortcutSource, master), title, releaseTitle, creator, art, lyrics, year,
						trackNumber == null ? -1 : trackNumber, rgd, cache, published, fshortcut, direct ? (filename) -> {
					res.setHeader("Transcode-Status", "DIRECT"+(cache ? ", WILL-CACHE" : ""));
					res.setHeader("Content-Type", format.mimeType());
					res.setHeader("Content-Disposition", "attachment; filename="+filename+"; filename*=utf-8''"+filename);
					res.setStatus(HTTP_200_OK);
					return res.getOutputStream();
				} : null).blob();
			};

			String blobNameRes;
			synchronized (mutex) {
				if (direct) {
					try {
						blobNameRes = transcoder.call();
					} catch (Exception e) {
						throw new ServletException(e);
					}
					if (!cache) return;
				} else {
					blobNameRes = Services.transcodePool.submit(transcoder).get();
				}
				try (PreparedStatement ps = c.prepareStatement("INSERT INTO `transcodes` "
						+ "(`master`, `format`, `file`, `track_id`, `release_id`, `created_at`, `last_downloaded`) "
						+ "VALUES (?, ?, ?, ?, ?, NOW(), NOW());")) {
					ps.setString(1, master);
					ps.setString(2, format.name());
					ps.setString(3, blobNameRes);
					if (trackId == null) {
						ps.setNull(4, Types.BIGINT);
					} else {
						ps.setLong(4, trackId);
					}
					if (releaseId == null) {
						ps.setNull(5, Types.BIGINT);
					} else {
						ps.setLong(5, releaseId);
					}
					ps.execute();
				}
				mutexes.row(master).remove(format, mutex);
				mutex.notifyAll();
			}
			if (!direct) {
				res.setHeader("Transcode-Status", fshortcut != null ? "SHORTCUT" : "FRESH");
				if (prepare) {
					res.setStatus(HTTP_204_NO_CONTENT);
					res.getOutputStream().close();
					res.setHeader("Transcode-Result", URLs.blob(blobNameRes));
				} else {
					res.sendRedirect(URLs.blob(blobNameRes));
				}
			}
		} catch (SQLException | InterruptedException | ExecutionException e) {
			throw new ServletException(e);
		}
	}

}
