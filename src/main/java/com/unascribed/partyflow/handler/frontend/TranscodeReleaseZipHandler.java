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
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.ThreadPools;
import com.unascribed.partyflow.TranscodeFormat;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.TranscodeFormat.ReplayGainData;
import com.unascribed.partyflow.TranscodeFormat.Shortcut;
import com.unascribed.partyflow.Transcoder;
import com.unascribed.partyflow.Transcoder.TranscodeResult;
import com.unascribed.partyflow.data.QReleases;
import com.unascribed.partyflow.data.QTranscodes;
import com.unascribed.partyflow.data.QTranscodes.FoundShortcut;
import com.unascribed.partyflow.data.QTranscodes.FoundTranscode;
import com.unascribed.partyflow.data.QTranscodes.TranscodeFindResult;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.google.common.base.Charsets;
import com.google.common.base.MoreObjects;
import com.google.common.net.InetAddresses;

public class TranscodeReleaseZipHandler extends SimpleHandler implements GetOrHead {

	private static final Logger log = LoggerFactory.getLogger(TranscodeReleaseZipHandler.class);

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
		Session s = SessionHelper.getSession(req);
		String permissionQuery = (s == null ? "false" : "`releases`.`user_id` = ?");
		try (Connection c = Partyflow.sql.getConnection()) {
			if (!format.usage().canDownload()) {
				throw new UserVisibleException(HTTP_400_BAD_REQUEST, "Format "+formatString+" is not valid for ZIP download");
			}
			if (prepare && !format.cache()) {
				res.setStatus(HTTP_204_NO_CONTENT);
				res.setHeader("Transcode-Status", "DIRECT");
				res.getOutputStream().close();
				return;
			}
			record CollectResult(TranscodeResult tr, boolean isNew, long trackId, String master, File directFile) {}
			List<File> tmpFiles = Collections.synchronizedList(new ArrayList<>());
			try {
				List<Future<CollectResult>> futures = new ArrayList<>();
				String releaseArt;
				String releaseTitle;
				String creator;
				long releaseId;
				double albumLoudness;
				double albumPeak;
				boolean published;
				try (PreparedStatement ps = c.prepareStatement("SELECT `art`, `title`, `peak`, `loudness`, `release_id`, `users`.`display_name`, `published` "
						+ "FROM `releases` JOIN `users` ON `releases`.`user_id` = `users`.`user_id` "
						+ "WHERE `releases`.`slug` = ? AND (`releases`.`published` = true OR "+permissionQuery+");")) {
					ps.setString(1, slug);
					if (s != null) ps.setInt(2, s.userId());
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.first()) {
							releaseArt = rs.getString("art");
							releaseTitle = rs.getString("title");
							creator = rs.getString("users.display_name");
							releaseId = rs.getLong("release_id");
							albumLoudness = rs.getInt("loudness")/10D;
							albumPeak = rs.getInt("peak")/10D;
							published = rs.getBoolean("published");
						} else {
							res.sendError(HTTP_404_NOT_FOUND);
							return;
						}
					}
				}
				boolean allCached = true;
				try (PreparedStatement ps = c.prepareStatement("SELECT "
							+ "`master`, `title`, "
							+ "`track_id`, `loudness`, `peak`, `art`, "
							+ "`slug`, `track_number`, "
							+ "EXTRACT(YEAR FROM `created_at`) AS `year`, `lyrics` "
						+ "FROM `tracks` "
						+ "WHERE `release_id` = ?;")) {
					ps.setLong(1, releaseId);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							String master = rs.getString("master");
							String title = rs.getString("title");
							String art = MoreObjects.firstNonNull(rs.getString("art"), releaseArt);
							String lyrics = rs.getString("lyrics");
							long trackId = rs.getLong("track_id");
							var rgd = new ReplayGainData(albumLoudness, rs.getInt("loudness")/10D,
									albumPeak, rs.getInt("peak")/10D);
							int year = rs.getInt("year");
							int trackNumber = rs.getInt("track_number");
							TranscodeFindResult fr = QTranscodes.findExistingTranscode(c, true, "track", rs.getString("slug"), format, master);
							if (fr instanceof FoundTranscode ft) {
								futures.add(ThreadPools.GENERIC.submit(() -> {
									BlobMetadata meta = Partyflow.storage.blobMetadata(Partyflow.storageContainer, ft.blob());
									String filename;
									if (meta.getContentMetadata().getContentDisposition() != null) {
										String disp = meta.getContentMetadata().getContentDisposition();
										String pfx = "filename*=utf-8''";
										int idx = disp.indexOf(pfx);
										if (idx != -1) {
											filename = URLDecoder.decode(disp.substring(idx+pfx.length()), Charsets.UTF_8);
										} else {
											filename = meta.getName();
										}
									} else {
										filename = meta.getName();
									}
									return new CollectResult(new TranscodeResult(ft.blob(), meta.getSize() == null ? -1 : meta.getSize(), filename),
											false, trackId, master, null);
								}));
							} else {
								allCached = false;
								String shortcutSource;
								Shortcut shortcut;
								if (fr instanceof FoundShortcut fs) {
									shortcutSource = fs.srcBlob();
									shortcut = fs.shortcut();
								} else {
									shortcutSource = null;
									shortcut = null;
								}
								futures.add(ThreadPools.TRANSCODE.submit(() -> {
									if (format.direct()) {
										File tmp = File.createTempFile("releasezip-", ".dat", Transcoder.WORK_DIR);
										tmpFiles.add(tmp);
										return new CollectResult(Transcoder.performTranscode(format, "release-zip", slug, MoreObjects.firstNonNull(shortcutSource, master),
													title, releaseTitle, creator, art, lyrics, year, trackNumber, rgd,
													false, published, shortcut, (fname) -> new FileOutputStream(tmp)),
												true, trackId, master, tmp);
									}
									return new CollectResult(Transcoder.performTranscode(format, "release-zip", slug, MoreObjects.firstNonNull(shortcutSource, master),
												title, releaseTitle, creator, art, lyrics, year, trackNumber, rgd,
												true, published, shortcut, null),
											true, trackId, master, null);
								}));
							}
						}
					}
				}
				List<CollectResult> results = new ArrayList<>();
				for (var f : futures) {
					CollectResult cr;
					try {
						cr = f.get();
					} catch (InterruptedException | ExecutionException e) {
						throw new ServletException(e);
					}
					if (cr.isNew() && !format.direct()) {
						try (PreparedStatement ps = c.prepareStatement("INSERT INTO `transcodes` (`master`, `format`, `file`, `track_id`, `release_id`, `created_at`, `last_downloaded`) "
								+ "VALUES (?, ?, ?, ?, ?, NOW(), NOW());")) {
							ps.setString(1, cr.master());
							ps.setString(2, format.name());
							ps.setString(3, cr.tr().blob());
							ps.setLong(4, cr.trackId());
							ps.setLong(5, releaseId);
							ps.execute();
						}
					}
					results.add(cr);
				}
				
				if (prepare) {
					res.setHeader("Transcode-Status", allCached ? "CACHED" : "FRESH");
					res.setStatus(HTTP_204_NO_CONTENT);
					res.getOutputStream().close();
					return;
				}

				try {
					var addr = InetAddresses.forString(req.getRemoteAddr());
					QReleases.maybeRecordDownload(slug, addr);
				} catch (IllegalArgumentException e) {}
				
				String filename = creator+" - "+releaseTitle+".zip";
				res.setHeader("Content-Type", "application/zip");
				res.setHeader("Content-Disposition", "attachment; filename="+filename+"; filename*=utf-8''"+filename);
				res.setStatus(HTTP_200_OK);
				ZipOutputStream zos = new ZipOutputStream(res.getOutputStream());
				
				int lastArtDot = releaseArt.lastIndexOf('.');
				if (lastArtDot != -1) {
					String artFname = "cover"+releaseArt.substring(lastArtDot);
					Blob b = Partyflow.storage.getBlob(Partyflow.storageContainer, releaseArt);
					ZipEntry ze = new ZipEntry(artFname);
					zos.putNextEntry(ze);
					try (var in = b.getPayload().openStream()) {
						in.transferTo(zos);
					}
					zos.closeEntry();
				}
				
				for (CollectResult cr : results) {
					ZipEntry ze = new ZipEntry(cr.tr().filename());
					zos.putNextEntry(ze);
					try (var in = cr.directFile() == null
							? Partyflow.storage.getBlob(Partyflow.storageContainer, cr.tr().blob()).getPayload().openStream()
							: new FileInputStream(cr.directFile())) {
						in.transferTo(zos);
					}
					zos.closeEntry();
				}
				zos.close();
			} finally {
				tmpFiles.forEach(File::delete);
			}
		}
	}

}
