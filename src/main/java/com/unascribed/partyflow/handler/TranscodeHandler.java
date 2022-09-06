package com.unascribed.partyflow.handler;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.domain.BlobMetadata;
import org.jclouds.blobstore.options.PutOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.ForkOutputStream;
import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.ThreadPools;
import com.unascribed.partyflow.TranscodeFormat;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.TranscodeFormat.ReplayGainData;
import com.unascribed.partyflow.TranscodeFormat.Shortcut;
import com.unascribed.partyflow.TranscodeFormat.Usage;
import com.unascribed.partyflow.Version;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;
import com.google.common.collect.Tables;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.net.UrlEscapers;

public class TranscodeHandler extends SimpleHandler implements GetOrHead {

	private static final File WORK_DIR = new File(System.getProperty("java.io.tmpdir"), "partyflow/work");
	private static final Splitter SLASH_SPLITTER = Splitter.on('/').limit(2);
	
	private static final Table<String, TranscodeFormat, Object> mutexes = Tables.synchronizedTable(HashBasedTable.create());
	
	private static final ImmutableMap<String, String> QUERIES_BY_KIND = ImmutableMap.of(
			"release", "SELECT `concat_master` AS `master`, `title`, NULL as `release_title`, `users`.`display_name` AS `creator`, "
						+ "`releases`.`published` AS `published`, `release_id`, NULL as `track_id`, "
						+ "`releases`.`peak` AS `album_peak`, `releases`.`loudness` AS `album_loudness`, "
						+ "`releases`.`peak` AS `track_peak`, `releases`.`loudness` AS `track_loudness`, "
						+ "`art`, NULL AS `fallback_art`, NULL AS `track_number`, NULL AS `lyrics`, "
						+ "EXTRACT(YEAR FROM `releases`.`created_at`) AS `year` "
					+ "FROM `releases` "
						+ "JOIN `users` ON `users`.`user_id` = `releases`.`user_id` "
					+ "WHERE `slug` = ? AND (`published` = true OR {});",
			
			"track", "SELECT `tracks`.`master` AS `master`, `tracks`.`title` AS `title`, `releases`.`title` AS `release_title`, "
							+ "`users`.`display_name` AS `creator`, `releases`.`published` AS `published`, `releases`.`release_id` AS `release_id`, "
							+ "`track_id`, `releases`.`peak` AS `album_peak`, `releases`.`loudness` AS `album_loudness`, "
							+ "`tracks`.`loudness` AS `track_loudness`, `tracks`.`peak` AS `track_peak`, "
							+ "`tracks`.`art` AS `art`, `releases`.`art` AS `fallback_art`, "
							+ "EXTRACT(YEAR FROM `tracks`.`created_at`) AS `year`, `tracks`.`track_number` AS `track_number`,"
							+ "`lyrics` "
					+ "FROM `tracks` "
						+ "JOIN `releases` ON `tracks`.`release_id` = `releases`.`release_id` "
						+ "JOIN `users` ON `releases`.`user_id` = `users`.`user_id` "
					+ "WHERE `tracks`.`slug` = ? AND (`releases`.`published` = true OR {});",
					
			"release-zip", ""
		);

	private static final Logger log = LoggerFactory.getLogger(TranscodeHandler.class);

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		Map<String, String> query = parseQuery(req);
		Iterator<String> split = SLASH_SPLITTER.split(path).iterator();
		String kind = split.next();
		String slug = split.next();
		if (!query.containsKey("format")) {
			throw new UserVisibleException(HTTP_400_BAD_REQUEST, "Format is required");
		}
		String masterQuery = QUERIES_BY_KIND.get(kind);
		if (masterQuery == null) {
			throw new UserVisibleException(HTTP_400_BAD_REQUEST, "Kind must be one of "+Joiner.on(", ").join(QUERIES_BY_KIND.keySet()));
		}
		String formatString = query.get("format");
		TranscodeFormat format = Partyflow.getFormatByName(formatString)
				.filter(TranscodeFormat::available)
				.orElseThrow(() -> new UserVisibleException(HTTP_400_BAD_REQUEST, "Unrecognized format "+formatString));
		Session s = SessionHelper.getSession(req);
		String permissionQuery = (s == null ? "false" : "`releases`.`user_id` = ?");
		try (Connection c = Partyflow.sql.getConnection()) {
			if ("release-zip".equals(kind)) {
				if (!format.usage().canDownload()) {
					throw new UserVisibleException(HTTP_400_BAD_REQUEST, "Format "+formatString+" is not valid for ZIP download");
				}
				record CollectResult(TranscodeResult tr, boolean isNew, long trackId, String master) {}
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
					if (s != null) ps.setInt(2, s.userId);
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
							TranscodeFindResult fr = findExistingTranscode(c, true, "track", rs.getString("slug"), format, master);
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
											false, trackId, master);
								}));
							} else {
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
									return new CollectResult(performTranscode(format, kind, slug, MoreObjects.firstNonNull(shortcutSource, master),
												title, releaseTitle, creator, art, lyrics, year, trackNumber, rgd,
												true, published, shortcut, null),
											true, trackId, master);
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
					if (cr.isNew()) {
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
					Blob b = Partyflow.storage.getBlob(Partyflow.storageContainer, cr.tr().blob());
					ZipEntry ze = new ZipEntry(cr.tr().filename());
					zos.putNextEntry(ze);
					try (var in = b.getPayload().openStream()) {
						in.transferTo(zos);
					}
					zos.closeEntry();
				}
				zos.close();
				return;
			}
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
			long releaseId;
			Integer trackNumber;
			ReplayGainData rgd;
			int year;
			try (PreparedStatement ps = c.prepareStatement(masterQuery.replace("{}", permissionQuery))) {
				ps.setString(1, slug);
				if (s != null) ps.setInt(2, s.userId);
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
			if (master == null) {
				res.sendError(HTTP_409_CONFLICT);
				return;
			}
			TranscodeFindResult findRes = findExistingTranscode(c, !head, kind, slug, format, master);
			if (findRes instanceof FoundTranscode ft) {
				res.setHeader("Transcode-Status", "CACHED");
				res.sendRedirect(Partyflow.resolveBlob(ft.blob()));
				return;
			} else if (findRes instanceof FoundShortcut fs) {
				shortcut = fs.shortcut();
				shortcutSource = fs.srcBlob();
			}
			if (head) {
				res.setStatus(HTTP_204_NO_CONTENT);
				res.setHeader("Transcode-Status", "UNAVAILABLE");
				res.setHeader("Comment", "Transcodes are not performed in response to HEAD requests");
				res.getOutputStream().close();
				return;
			}
			boolean direct = format.direct();
			boolean cache = format.cache();
			if (direct) {
				log.debug("Streaming {} from master...", format);
			} else if (shortcut == null) {
				log.debug("Transcoding to {} from master...", format);
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
				return performTranscode(format, kind, slug, MoreObjects.firstNonNull(fshortcutSource, master), title, releaseTitle, creator, art, lyrics, year,
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
					blobNameRes = ThreadPools.TRANSCODE.submit(transcoder).get();
				}
				try (PreparedStatement ps = c.prepareStatement("INSERT INTO `transcodes` (`master`, `format`, `file`, `track_id`, `release_id`, `created_at`, `last_downloaded`) "
						+ "VALUES (?, ?, ?, ?, ?, NOW(), NOW());")) {
					ps.setString(1, master);
					ps.setString(2, format.name());
					ps.setString(3, blobNameRes);
					if (trackId == null) {
						ps.setNull(4, Types.BIGINT);
					} else {
						ps.setLong(4, trackId);
					}
					ps.setLong(5, releaseId);
					ps.execute();
				}
				mutexes.row(master).remove(format, mutex);
				mutex.notifyAll();
			}
			if (!direct) {
				res.setHeader("Transcode-Status", fshortcut != null ? "SHORTCUT" : "FRESH");
				res.sendRedirect(Partyflow.resolveBlob(blobNameRes));
			}
		} catch (SQLException | InterruptedException | ExecutionException e) {
			throw new ServletException(e);
		}
	}

	public static TranscodeFindResult findExistingTranscode(Connection c, boolean updateLastDownload, String kind, String slug,
			TranscodeFormat format, String master) throws SQLException {
		String addnFormats = Strings.repeat(", ?", format.shortcuts().size());
		try (PreparedStatement ps = c.prepareStatement("SELECT `transcode_id`, `file`, `format` FROM `transcodes` "
				+ "WHERE `master` = ? AND `transcodes`.`format` IN (?"+addnFormats+");")) {
			int i = 1;
			ps.setString(i++, master);
			ps.setString(i++, format.name());
			for (Shortcut sc : format.shortcuts()) {
				ps.setString(i++, sc.source().name());
			}
			try (ResultSet rs = ps.executeQuery()) {
				if (rs.first()) {
					String id = rs.getString("transcodes.format");
					if (!format.name().equals(id)) {
						for (Shortcut sc : format.shortcuts()) {
							if (sc.source().name().equals(id)) {
								return new FoundShortcut(sc, rs.getString("transcodes.file"));
							}
						}
						return null;
					} else {
						if (Partyflow.storage.blobExists(Partyflow.storageContainer, rs.getString("transcodes.file"))) {
							if (updateLastDownload) {
								try (PreparedStatement ps2 = c.prepareStatement("UPDATE `transcodes` SET `last_downloaded` = NOW() WHERE `transcode_id` = ?;")) {
									ps2.setInt(1, rs.getInt("transcode_id"));
									ps2.execute();
								}
							}
							return new FoundTranscode(rs.getString("transcodes.file"));
						} else {
							log.warn("A transcode of {} {} to {} has gone missing!", kind, slug, format.name());
							try (PreparedStatement ps2 = c.prepareStatement("DELETE FROM `transcodes` WHERE `transcode_id` = ?;")) {
								ps2.setInt(1, rs.getInt("transcode_id"));
								ps2.executeUpdate();
							}
							return null;
						}
					}
				} else {
					return null;
				}
			}
		}
	}
	
	public interface TranscodeFindResult {}
	public record FoundTranscode(String blob) implements TranscodeFindResult {}
	public record FoundShortcut(Shortcut shortcut, String srcBlob) implements TranscodeFindResult {}
	
	public record TranscodeResult(String blob, long size, String filename) {}
	
	public interface DirectStreamSupplier {
		OutputStream get(String filename) throws IOException;
	}

	private static final Pattern MAGICK_SIZE_PATTERN = Pattern.compile("([0-9]+)x([0-9]+)");
	
	public static TranscodeResult performTranscode(TranscodeFormat fmt, String kind, String slug, String src,
			String title, String releaseTitle, String creator, String art, String lyrics, int year, int trackNumber, ReplayGainData rgd,
			boolean cache, boolean published, Shortcut shortcut, DirectStreamSupplier directOut) throws IOException, ServletException {
		WORK_DIR.mkdirs();
		Blob masterBlob = Partyflow.storage.getBlob(Partyflow.storageContainer, src);
		if (masterBlob == null) {
			log.error("Master for {} {} is missing!", kind, slug);
			return null;
		}
		File tmpFile = cache ? File.createTempFile("transcode-", "."+fmt.fileExtension(), WORK_DIR) : null;
		boolean attachArt = art != null && fmt.usage().canDownload() && !fmt.args().contains("-vn");
		boolean ogg = fmt.args().contains("ogg");
		String artB64 = null;
		File artFile = attachArt ? File.createTempFile("transcode-", art.substring(art.lastIndexOf('.')), WORK_DIR) : null;
		if (art != null && attachArt) {
			Blob artBlob = Partyflow.storage.getBlob(Partyflow.storageContainer, art);
			if (artBlob == null) {
				log.warn("Art for {} {} is missing!", kind, slug);
				attachArt = false;
			} else {
				try (var in = artBlob.getPayload().openStream()) {
					try (var out = new FileOutputStream(artFile)) {
						in.transferTo(out);
					}
				}
				if (ogg) {
					attachArt = false;
					// FFmpeg doesn't support writing Ogg album art...
					// Been an open feature request for 7 years
					Process p = Partyflow.magick_convert(artFile.getPath(), "-identify", "null:-");
					p.getOutputStream().close();
					String out = new String(ByteStreams.toByteArray(p.getInputStream()), Charsets.UTF_8);
					String err = new String(ByteStreams.toByteArray(p.getErrorStream()), Charsets.UTF_8);
					while (p.isAlive()) {
						try {
							p.waitFor();
						} catch (InterruptedException e) {
						}
					}
					if (p.exitValue() != 0) {
						log.warn("Failed to identify art with ImageMagick:\n{}", err);
					} else {
						var m = MAGICK_SIZE_PATTERN.matcher(out);
						if (m.find()) {
							int width = Integer.parseInt(m.group(1));
							int height = Integer.parseInt(m.group(2));
							var baos = new ByteArrayOutputStream();
							var dos = new DataOutputStream(baos);
							String mime = artBlob.getMetadata().getContentMetadata().getContentType();
							dos.writeInt(3); // Cover (front)
							dos.writeInt(mime.length());
							dos.write(mime.getBytes(Charsets.US_ASCII));
							dos.writeInt(0);
							dos.writeInt(width);
							dos.writeInt(height);
							dos.writeInt(24);
							dos.writeInt(0);
							dos.writeInt((int)artFile.length());
							try (var in = new FileInputStream(artFile)) {
								in.transferTo(dos);
							}
							artB64 = Base64.getEncoder().encodeToString(baos.toByteArray());
						}
					}
				}
			}
		}
		File metaFile = File.createTempFile("transcode-", ".txt", WORK_DIR);
		String guilt = (!fmt.usage().canDownload() ? ". Low-quality encode for streaming; consider downloading a real copy." : "");
		try {
			List<String> meta = new ArrayList<>();
			meta.add(";FFMETADATA1");
			meta.add("title="+title+(releaseTitle == null ? " (Full Album)" : ""));
			meta.add("album="+MoreObjects.firstNonNull(releaseTitle, title));
			meta.add("artist="+creator);
			meta.add("date="+year);
			if (trackNumber > 0) meta.add("track="+trackNumber);
			if (lyrics != null) meta.add("unsyncedlyrics="+lyrics.replace("\n", "\\\n"));
			if (artB64 != null) meta.add("metadata_block_picture="+artB64);
			meta.add("comment=Generated by Partyflow v"+Version.FULL+" hosted at "+Partyflow.publicUri.getHost()+guilt);
			fmt.replaygain().entrySet().stream()
				.map(en -> en.getKey()+"="+en.getValue().apply(rgd))
				.forEach(meta::add);
			Files.asCharSink(metaFile, Charsets.UTF_8).writeLines(meta);
			Process p = Partyflow.ffmpeg("-v", "error",
					"-i", "-", "-i", metaFile.getAbsolutePath(), attachArt ? List.of("-i", artFile.getAbsolutePath()) : null,
					shortcut == null ? fmt.args() : shortcut.args(),
					"-map_metadata", "1",
					"-map", "a",
					attachArt ?
						List.of(
							"-map", "2",
							"-metadata:s:v", "title=Album cover",
							"-metadata:s:v", "comment=Cover (front)",
							"-disposition:v", "attached_pic",
							"-codec:v", "copy"
						) : null,
					"-y",
					directOut != null ? "-" : tmpFile.getAbsolutePath());
			String filename = creator+" - "+(releaseTitle == null ? "" : releaseTitle+" - ")+String.format("%02d", trackNumber)+" "+title+"."+fmt.fileExtension();
			String filenameEncoded = encodeFilename(filename);
			if (directOut != null) {
				OutputStream out = cache ? new ForkOutputStream(new FileOutputStream(tmpFile), directOut.get(filenameEncoded)) : directOut.get(filenameEncoded);
				new Thread(() -> {
					try {
						ByteStreams.copy(p.getInputStream(), out);
						out.close();
					} catch (IOException e) {
						log.warn("Exception while copying", e);
					}
				}, "FFmpeg copy thread").start();
			}
			try (InputStream in = masterBlob.getPayload().openStream()) {
				ByteStreams.copy(in, p.getOutputStream());
				p.getOutputStream().close();
			} catch (IOException e) {
				if (!"Broken pipe".equals(e.getMessage())) {
					throw e;
				}
			}
			String err = new String(ByteStreams.toByteArray(p.getErrorStream()), Charsets.UTF_8);
			while (p.isAlive()) {
				try {
					p.waitFor();
				} catch (InterruptedException e) {
				}
			}
			if (p.exitValue() != 0) {
				log.warn("Failed to process audio with FFmpeg:\n{}", err);
				throw new ServletException("Failed to transcode; FFmpeg exited with code "+p.exitValue());
			}
			if (tmpFile != null) {
				log.debug("{} completed", shortcut == null ? "Transcode" : "Remux");
				String blobName;
				do {
					String rand = Partyflow.randomString(16);
					blobName = "transcodes/"+rand.substring(0, 3)+"/"+rand+"."+fmt.fileExtension();
				} while (Partyflow.storage.blobExists(Partyflow.storageContainer, blobName));
				Blob transBlob = Partyflow.storage.blobBuilder(blobName)
						.payload(tmpFile)
						.contentType(fmt.mimeType())
						.contentDisposition(fmt.usage() == Usage.DOWNLOAD ? "attachment; filename="+filenameEncoded+"; filename*=utf-8''"+filenameEncoded : "inline")
						.cacheControl(published ? "public, immutable" : "private")
						.build();
				Partyflow.storage.putBlob(Partyflow.storageContainer, transBlob, new PutOptions().multipart().setBlobAccess(BlobAccess.PUBLIC_READ));
				return new TranscodeResult(blobName, tmpFile.length(), filename);
			} else {
				return null;
			}
		} finally {
			if (tmpFile != null) tmpFile.delete();
			if (artFile != null) artFile.delete();
			metaFile.delete();
		}
	}

	public static String encodeFilename(String str) {
		return UrlEscapers.urlFragmentEscaper().escape(str).replace(";", "%3B");
	}

}
