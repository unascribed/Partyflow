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

package com.unascribed.partyflow.handler.frontend.release;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.Part;

import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.options.PutOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Commands;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.data.Queries;
import com.unascribed.partyflow.handler.frontend.TranscodeHandler;
import com.unascribed.partyflow.handler.util.MultipartData;
import com.unascribed.partyflow.handler.util.MustacheHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.handler.util.SimpleHandler.MultipartPost;
import com.unascribed.partyflow.ThreadPools;
import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Ints;

public class ReleaseAddTrackHandler extends SimpleHandler implements GetOrHead, MultipartPost {

	private static final Logger log = LoggerFactory.getLogger(ReleaseAddTrackHandler.class);

	private static final File WORK_DIR = new File(System.getProperty("java.io.tmpdir"), "partyflow/work");

	private static final Pattern DURATION_FFPROBE_PATTERN = Pattern.compile("^format.duration=\"?(.*?)\"?$", Pattern.MULTILINE);
	private static final Pattern TITLE_FFPROBE_PATTERN = Pattern.compile("^format.tags.(?:title|TITLE)=\"?(.*?)\"?$", Pattern.MULTILINE);
	private static final Pattern TRACK_FFPROBE_PATTERN = Pattern.compile("^format.tags.(?:track|TRACK)=\"?(.*?)\"?$", Pattern.MULTILINE);
	private static final Pattern LYRICS_FFPROBE_PATTERN = Pattern.compile("^format.tags.(?:unsynced|UNSYNCED)?(?:lyrics|LYRICS)=\"?(.*?)\"?$", Pattern.MULTILINE);
	
	private static final Pattern LOUDNESS_FFMPEG_PATTERN = Pattern.compile("^\\s+I:\\s+(-?\\d+\\.\\d+) LUFS$", Pattern.MULTILINE);
	private static final Pattern PEAK_FFMPEG_PATTERN = Pattern.compile("^\\s+Peak:\\s+(-?(?:\\d+\\.\\d+|inf)) dBFS$", Pattern.MULTILINE);
	
	private static final BigDecimal TO_SAMPLES = new BigDecimal(48000);

	@Override
	public void getOrHead(String slugs, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException, SQLException {
		Map<String, String> query = parseQuery(req);
		Session s = SessionHelper.getSessionOrThrow(req, null);
		try (Connection c = Partyflow.sql.getConnection()) {
			try (PreparedStatement ps = c.prepareStatement(
					"SELECT `title`, `subtitle`, `published`, `art` FROM `releases` "
					+ "WHERE `slug` = ? AND `user_id` = ?;")) {
				ps.setString(1, slugs);
				ps.setInt(2, s.userId());
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.first()) {
						MustacheHandler.serveTemplate(req, res, "add-track.hbs.html", new Object() {
							Object release = new Object() {
								String slug = slugs;
								String title = rs.getString("title");
								String subtitle = rs.getString("subtitle");
								boolean published = rs.getBoolean("published");
								String art = Partyflow.resolveArt(rs.getString("art"));
								String error = query.get("error");
							};
						});
					} else {
						res.sendError(HTTP_404_NOT_FOUND);
					}
				}
			}
		} catch (SQLException e) {
			throw new ServletException(e);
		}
	}

	@Override
	public void multipartPost(String slugs, HttpServletRequest req, HttpServletResponse res, MultipartData data)
			throws IOException, ServletException, SQLException {
		Session s = SessionHelper.getSessionOrThrow(req, data.getPartAsString("csrf", 64));
		List<Part> masters = data.getAllParts("master");
		if (masters.isEmpty()) {
			res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(slugs)+"/add-track?error=At least one master is required");
			return;
		}
		String lastSlug = null;
		boolean committed = false;
		try (Connection c = Partyflow.sql.getConnection()) {
			try {
				c.setAutoCommit(false);
				int releaseId;
				try (PreparedStatement ps = c.prepareStatement("SELECT `release_id` FROM `releases` WHERE `slug` = ? AND `user_id` = ?;")) {
					ps.setString(1, slugs);
					ps.setInt(2, s.userId());
					try (ResultSet rs = ps.executeQuery()) {
						// slug is UNIQUE, we don't need to handle more than one row
						if (rs.first()) {
							releaseId = rs.getInt("release_id");
						} else {
							res.sendError(HTTP_404_NOT_FOUND);
							return;
						}
					}
				}
				WORK_DIR.mkdirs();
				record TrackData(String title, int trackNumber, String blobName, long duration, double loudness, double peak, String lyrics) {}
				List<Future<TrackData>> futures = new ArrayList<>();
				for (Part master : masters) {
					futures.add(ThreadPools.TRANSCODE.submit(() -> {
						File tmpFile = File.createTempFile("transcode-", ".flac", WORK_DIR);
						try {
							log.debug("Processing master {}...", master.getSubmittedFileName());
							Process p = Commands.ffmpeg("-v", "info", "-nostats",
									"-i", "-", "-map", "a",
									"-af", "ebur128=framelog=verbose:peak=true",
									"-dither_method", "improved_e_weighted",
									"-filter_type", "kaiser",
									"-sample_fmt", "s16",
									"-ar", "48k",
									"-map_metadata", "-1",
									"-metadata", "comment=Generated by Partyflow v"+Version.FULL+" hosted at "+Partyflow.publicUri.getHost(),
									"-f", "flac",
									"-y", tmpFile.getAbsolutePath()).start();
							try (InputStream in = master.getInputStream()) {
								ByteStreams.copy(in, p.getOutputStream());
								p.getOutputStream().close();
							} catch (IOException e) {
								if (!"Broken pipe".equals(e.getMessage())) {
									throw e;
								}
							}
							while (p.isAlive()) {
								try {
									p.waitFor();
								} catch (InterruptedException e) {
								}
							}
							String mpegErr = new String(ByteStreams.toByteArray(p.getErrorStream()), Charsets.UTF_8);
							if (p.exitValue() != 0) {
								log.warn("Failed to process audio with FFmpeg, output:\n{}", mpegErr);
								throw new ServletException("Failed to transcode; FFmpeg exited with code "+p.exitValue());
							}
							double loudness = find(LOUDNESS_FFMPEG_PATTERN, mpegErr)
									.map(Doubles::tryParse)
									.orElse(0D);
							double peak = find(PEAK_FFMPEG_PATTERN, mpegErr)
									.map(Doubles::tryParse)
									.orElse(0D);
							String blobName;
							do {
								String rand = Partyflow.randomString(16);
								blobName = "masters/"+rand.substring(0, 3)+"/"+rand+".flac";
							} while (Partyflow.storage.blobExists(Partyflow.storageContainer, blobName));
							String sfm = master.getSubmittedFileName();
							if (sfm.contains(".")) {
								sfm = sfm.substring(0, sfm.lastIndexOf('.'))+".flac";
							}
							String filename = TranscodeHandler.encodeFilename(sfm);
							Blob blob = Partyflow.storage.blobBuilder(blobName)
									.payload(tmpFile)
									.cacheControl("private")
									.contentLength(tmpFile.length())
									.contentDisposition("attachment; filename="+filename+"; filename*=utf-8''"+filename)
									.contentType("audio/flac")
									.build();
							Partyflow.storage.putBlob(Partyflow.storageContainer, blob, new PutOptions().multipart().setBlobAccess(BlobAccess.PRIVATE));
							String titleFromFilename = sfm.contains(".") ? sfm.substring(0, sfm.lastIndexOf('.')) : sfm;
							Process probe = Commands.ffprobe("-v", "error", "-print_format", "flat", "-show_format", "-").start();
							try (InputStream in = master.getInputStream()) {
								ByteStreams.copy(in, probe.getOutputStream());
								probe.getOutputStream().close();
							} catch (IOException e) {
								if (!"Broken pipe".equals(e.getMessage())) {
									throw e;
								}
							}
							while (probe.isAlive()) {
								try {
									probe.waitFor();
								} catch (InterruptedException e) {
								}
							}
							String title;
							int trackNumber = -1;
							long duration;
							String lyrics;
							if (probe.exitValue() != 0) {
								String str = new String(ByteStreams.toByteArray(probe.getErrorStream()), Charsets.UTF_8);
								log.warn("Failed to probe master with FFprobe:\n{}", str);
								title = titleFromFilename;
								duration = 0;
								lyrics = null;
							} else {
								String probeOut = new String(ByteStreams.toByteArray(probe.getInputStream()), Charsets.UTF_8);
								title = find(TITLE_FFPROBE_PATTERN, probeOut)
										.orElse(titleFromFilename);
								trackNumber = find(TRACK_FFPROBE_PATTERN, probeOut)
										.map(Ints::tryParse)
										.filter(i -> i >= 1)
										.orElse(-1);
								duration = find(DURATION_FFPROBE_PATTERN, probeOut)
										.map(this::tryParseBigDecimal)
										.map(bd -> bd.multiply(TO_SAMPLES))
										.map(BigDecimal::longValue)
										.orElseGet(() -> {
											log.warn("Couldn't parse duration from FFprobe output:\n{}", probeOut);
											return 0L;
										});
								lyrics = find(LYRICS_FFPROBE_PATTERN, probeOut)
										.map(str -> str.replace("\\n", "\n").replace("\\r", ""))
										.orElse(null);
							}
							log.debug("Processed master {}. {} successfully.\nDuration: {}ms, loudness: {}LUFS, peak: {}dBFS", trackNumber, title, duration/48, loudness, peak);
							return new TrackData(title, trackNumber, blobName, duration, loudness, peak, lyrics);
						} finally {
							tmpFile.delete();
						}
					}));
				}
				for (var f : futures) {
					TrackData td;
					try {
						td = f.get();
					} catch (InterruptedException | ExecutionException e) {
						throw new ServletException(e);
					}
					int trackNumber = td.trackNumber;
					if (trackNumber == -1) {
						try (PreparedStatement ps = c.prepareStatement("SELECT MAX(`track_number`)+1 AS `next` FROM `tracks` WHERE `release_id` = ?;")) {
							ps.setInt(1, releaseId);
							try (ResultSet rs = ps.executeQuery()) {
								if (rs.first()) {
									if (rs.getObject("next") == null) {
										trackNumber = 1;
									} else {
										trackNumber = rs.getInt("next");
									}
								} else {
									trackNumber = 1;
								}
							}
						}
					}
					String slug = Queries.findSlug("tracks", Partyflow.sanitizeSlug(td.title));
					lastSlug = slug;
					try (PreparedStatement ps = c.prepareStatement(
							"INSERT INTO `tracks` (`release_id`, `title`, `subtitle`, `slug`, `master`, `description`, `track_number`, `loudness`, `peak`, `duration`, `lyrics`, `created_at`, `last_updated`) "
							+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW());")) {
						ps.setInt(1, releaseId);
						ps.setString(2, td.title);
						ps.setString(3, "");
						ps.setString(4, slug);
						ps.setString(5, td.blobName);
						ps.setString(6, "");
						ps.setInt(7, trackNumber);
						ps.setInt(8, (int)(td.loudness*10));
						ps.setInt(9, (int)(td.peak*10));
						ps.setLong(10, td.duration);
						ps.setString(11, td.lyrics);
						ps.execute();
					}
				}
				c.commit();
				committed = true;
				regenerateAlbumFile(releaseId);
			} finally {
				if (!committed) {
					c.rollback();
				}
				c.setAutoCommit(true);
			}
		} catch (SQLException e) {
			throw new ServletException(e);
		}
		if (masters.size() == 1) {
			res.sendRedirect(Partyflow.config.http.path+"track/"+escPathSeg(lastSlug));
		} else {
			res.sendRedirect(Partyflow.config.http.path+"releases/"+escPathSeg(slugs));
		}
	}
	
	private BigDecimal tryParseBigDecimal(String s) {
		try {
			return new BigDecimal(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
	
	public static void regenerateAlbumFile(long releaseId) {
		ThreadPools.GENERIC.execute(() -> {
			List<File> tmpFiles = new ArrayList<>();
			try (Connection c = Partyflow.sql.getConnection()) {
				List<String> concatLines = new ArrayList<>();
				concatLines.add("ffconcat version 1.0");
				log.debug("Regenerating gapless album file for release ID {}", releaseId);
				List<String> masters = new ArrayList<>();
				double loudness = -70;
				double peak = 0;
				try (PreparedStatement ps = c.prepareStatement("SELECT `master`, `loudness`, `peak` FROM `tracks` WHERE `release_id` = ? ORDER BY `track_number` ASC;")) {
					ps.setLong(1, releaseId);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) {
							loudness = rs.getInt("loudness")/10D;
							peak = rs.getInt("peak")/10D;
							masters.add(rs.getString("master"));
						}
						
					}
				}
				if (masters.isEmpty()) {
					log.debug("Trivial case: No tracks. Empty concat.");
					updateConcatMaster(c, releaseId, -70, 0, null);
					return;
				}
				if (masters.size() == 1) {
					log.debug("Trivial case: One track. Concat = sole track.");
					updateConcatMaster(c, releaseId, loudness, peak, masters.get(0));
					return;
				}
				for (String m : masters) {
					File tmpFile = File.createTempFile("concat-", ".flac", WORK_DIR);
					tmpFiles.add(tmpFile);
					try (InputStream in = Partyflow.storage.getBlob(Partyflow.storageContainer, m).getPayload().openStream()) {
						try (OutputStream out = new FileOutputStream(tmpFile)) {
							ByteStreams.copy(in, out);
						}
					}
					concatLines.add("file "+tmpFile.getName());
				}
				File concatFile = File.createTempFile("concat-", ".txt", WORK_DIR);
				tmpFiles.add(concatFile);
				Files.asCharSink(concatFile, Charsets.UTF_8).writeLines(concatLines);
				File outFile = File.createTempFile("concat-", ".flac", WORK_DIR);
				tmpFiles.add(outFile);
				Process p = Commands.ffmpeg("-v", "info", "-nostdin", "-nostats",
						"-i", concatFile.getAbsolutePath(), "-map", "a",
						"-af", "ebur128=framelog=verbose:peak=true",
						"-sample_fmt", "s16",
						"-dither_method", "improved_e_weighted",
						"-map_metadata", "-1",
						"-metadata", "comment=Generated by Partyflow v"+Version.FULL+" hosted at "+Partyflow.publicUri.getHost(),
						"-f", "flac",
						"-y", outFile.getAbsolutePath()).start();
				p.getOutputStream().close();
				while (p.isAlive()) {
					try {
						p.waitFor();
					} catch (InterruptedException e) {
					}
				}
				String mpegErr = new String(ByteStreams.toByteArray(p.getErrorStream()), Charsets.UTF_8);
				if (p.exitValue() != 0) {
					log.warn("Failed to process audio with FFmpeg, output:\n{}", mpegErr);
					return;
				}
				loudness = find(LOUDNESS_FFMPEG_PATTERN, mpegErr)
						.map(Doubles::tryParse)
						.orElse(0D);
				peak = find(PEAK_FFMPEG_PATTERN, mpegErr)
						.map(Doubles::tryParse)
						.orElse(0D);
				String blobName;
				do {
					String rand = Partyflow.randomString(16);
					blobName = "concats/"+rand.substring(0, 3)+"/"+rand+".flac";
				} while (Partyflow.storage.blobExists(Partyflow.storageContainer, blobName));
				Blob blob = Partyflow.storage.blobBuilder(blobName)
						.payload(outFile)
						.cacheControl("private")
						.contentLength(outFile.length())
						.contentDisposition("attachment")
						.contentType("audio/flac")
						.build();
				Partyflow.storage.putBlob(Partyflow.storageContainer, blob, new PutOptions().multipart().setBlobAccess(BlobAccess.PRIVATE));
				updateConcatMaster(c, releaseId, loudness, peak, blobName);
			} catch (Throwable e) {
				log.warn("Failed to regenerate album file for release ID {}", releaseId, e);
			} finally {
				tmpFiles.forEach(File::delete);
			}
		});
	}

	private static void updateConcatMaster(Connection c, long releaseId, double loudness, double peak, String blobName) throws SQLException {
		try {
			c.setAutoCommit(false);
			boolean committed = false;
			String oldBlob = null;
			try {
				try (PreparedStatement ps = c.prepareStatement("SELECT `concat_master` FROM `releases` WHERE `release_id` = ?;")) {
					ps.setLong(1, releaseId);
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.first()) {
							oldBlob = rs.getString("concat_master");
						}
					}
				}
				boolean success = false;
				try (PreparedStatement ps = c.prepareStatement("UPDATE `releases` SET `concat_master` = ?, `loudness` = ?, `peak` = ? WHERE `release_id` = ?;")) {
					ps.setString(1, blobName);
					ps.setInt(2, (int)(loudness*10));
					ps.setInt(3, (int)(peak*10));
					ps.setLong(4, releaseId);
					success = (ps.executeUpdate() > 0);
				}
				c.commit();
				committed = true;
				log.debug("Processed concatenation successfully.\nAlbum loudness: {}LUFS, album peak: {}dBFS", loudness, peak);
				if (success && oldBlob != null) {
					log.trace("Deleting {}", oldBlob);
					Partyflow.storage.removeBlob(Partyflow.storageContainer, oldBlob);
				}
			} finally {
				if (!committed) {
					c.rollback();
				}
			}
		} finally {
			c.setAutoCommit(true);
		}
	}

	private static Optional<String> find(Pattern pattern, String haystack) {
		Matcher m = pattern.matcher(haystack);
		if (m.find()) {
			return Optional.of(m.group(1));
		} else {
			return Optional.empty();
		}
	}

}
