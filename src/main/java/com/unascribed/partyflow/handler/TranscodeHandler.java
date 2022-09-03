package com.unascribed.partyflow.handler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.options.PutOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SimpleHandler;
import com.unascribed.partyflow.ThreadPools;
import com.unascribed.partyflow.TranscodeFormat;
import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.TranscodeFormat.Shortcut;
import com.unascribed.partyflow.TranscodeFormat.Usage;

import com.google.common.base.Charsets;
import com.google.common.base.Enums;
import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.net.UrlEscapers;

public class TranscodeHandler extends SimpleHandler implements GetOrHead {

	private static final File WORK_DIR = new File(System.getProperty("java.io.tmpdir"), "partyflow/work");
	private static final Splitter SLASH_SPLITTER = Splitter.on('/').limit(2);
	
	private static final ImmutableMap<String, String> QUERIES_BY_KIND = ImmutableMap.of(
			"release", "SELECT `concat_master` AS `master`, `title`, NULL as `release_title`, `users`.`display_name` AS `creator`, `releases`.`published` AS `published` FROM `releases` "
					+ "JOIN `users` ON `users`.`user_id` = `releases`.`user_id` "
					+ "WHERE `slug` = ? AND (`published` = true OR {});",
			
			"track", "SELECT `tracks`.`master` AS `master`, `tracks`.`title` AS `title`, `releases`.`title` AS `release_title`, `users`.`display_name` AS `creator`, `releases`.`published` AS `published` FROM `tracks` "
						+ "JOIN `releases` ON `tracks`.`release_id` = `releases`.`release_id` "
						+ "JOIN `users` ON `releases`.`user_id` = `users`.`user_id` "
					+ "WHERE `tracks`.`slug` = ? AND (`releases`.`published` = true OR {});"
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
		String cleanedFormat = query.get("format").toUpperCase(Locale.ROOT).replace('-', '_');
		TranscodeFormat fmt = Enums.getIfPresent(TranscodeFormat.class, cleanedFormat).toJavaUtil()
				.filter(Partyflow::isFormatLegal)
				.orElseThrow(() -> new UserVisibleException(HTTP_400_BAD_REQUEST, "Unrecognized format "+cleanedFormat));
		String shortcutSource = null;
		Shortcut shortcut = null;
		Session s = SessionHelper.getSession(req);
		try (Connection c = Partyflow.sql.getConnection()) {
			String master;
			String title;
			String releaseTitle;
			String creator;
			boolean published;
			String permissionQuery = (s == null ? "false" : "`releases`.`user_id` = ?");
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
			String addnFormats = Strings.repeat(", ?", fmt.getShortcuts().size());
			try (PreparedStatement ps = c.prepareStatement("SELECT `transcode_id`, `file`, `format` FROM `transcodes` "
					+ "WHERE `master` = ? AND `transcodes`.`format` IN (?"+addnFormats+");")) {
				int i = 1;
				ps.setString(i++, master);
				ps.setInt(i++, fmt.getDatabaseId());
				for (Shortcut sc : fmt.getShortcuts()) {
					ps.setInt(i++, sc.getSource().getDatabaseId());
				}
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.first()) {
						int id = rs.getInt("transcodes.format");
						if (id != fmt.getDatabaseId()) {
							shortcutSource = rs.getString("transcodes.file");
							for (Shortcut sc : fmt.getShortcuts()) {
								if (sc.getSource().getDatabaseId() == id) {
									shortcut = sc;
									break;
								}
							}
						} else {
							if (Partyflow.storage.blobExists(Partyflow.storageContainer, rs.getString("transcodes.file"))) {
								if (!head) {
									try (PreparedStatement ps2 = c.prepareStatement("UPDATE `transcodes` SET `last_downloaded` = NOW() WHERE `transcode_id` = ?;")) {
										ps2.setInt(1, rs.getInt("transcode_id"));
										ps2.execute();
									}
								}
								res.setHeader("Transcode-Status", "CACHED");
								res.sendRedirect(Partyflow.resolveBlob(rs.getString("transcodes.file")));
								return;
							} else {
								log.warn("A transcode of {} {} to {} has gone missing! Recreating it.", kind, slug, fmt.name());
								try (PreparedStatement ps2 = c.prepareStatement("DELETE FROM `transcodes` WHERE `transcode_id` = ?;")) {
									ps2.setInt(1, rs.getInt("transcode_id"));
									ps2.executeUpdate();
								}
							}
						}
					}
				}
			}
			if (head) {
				res.setStatus(HTTP_204_NO_CONTENT);
				res.setHeader("Transcode-Status", "UNAVAILABLE");
				res.setHeader("Comment", "Transcodes are not performed in response to HEAD requests");
				res.getOutputStream().close();
				return;
			}
			boolean direct = fmt.getUsage() == Usage.DOWNLOAD_DIRECT;
			if (direct) {
				log.debug("Streaming {} from master...", fmt);
			} else if (shortcut == null) {
				log.debug("Transcoding to {} from master...", fmt);
			} else {
				log.debug("Remuxing to {} from {}...", fmt, shortcut.getSource());
			}
			
			final Shortcut fshortcut = shortcut;
			final String fshortcutSource = shortcutSource;
			
			Callable<String> transcoder = () -> {
				WORK_DIR.mkdirs();
				Blob masterBlob = Partyflow.storage.getBlob(Partyflow.storageContainer, MoreObjects.firstNonNull(fshortcutSource, master));
				File tmpFile = direct ? null : File.createTempFile("transcode-", "."+fmt.getFileExtension(), WORK_DIR);
				String guilt = (!fmt.getUsage().canDownload() ? ". Low-quality encode for streaming; consider downloading a real copy." : "");
				try {
					Process p = Partyflow.ffmpeg("-v", "error",
							"-i", "-",
							fshortcut == null ? fmt.getFFmpegArguments() : fshortcut.getFFmpegArguments(),
							"-map_metadata", "-1",
							"-map", "a",
							"-metadata", "title="+title,
							"-metadata", "album="+releaseTitle,
							"-metadata", "artist="+creator,
							"-metadata", "comment=Generated by Partyflow v"+Version.FULL+" hosted at "+Partyflow.publicUri.getHost()+guilt,
							"-y",
							tmpFile == null ? "-" : tmpFile.getAbsolutePath());
					String filename = encodeFilename(title+"."+fmt.getFileExtension());
					if (tmpFile == null) {
						res.setHeader("Transcode-Status", "DIRECT");
						res.setHeader("Content-Type", fmt.getMimeType());
						res.setHeader("Content-Disposition", "attachment; filename="+filename+"; filename*=utf-8''"+filename);
						res.setStatus(HTTP_200_OK);
						new Thread(() -> {
							try {
								ByteStreams.copy(p.getInputStream(), res.getOutputStream());
								res.getOutputStream().close();
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
					while (p.isAlive()) {
						try {
							p.waitFor();
						} catch (InterruptedException e) {
						}
					}
					if (p.exitValue() != 0) {
						String str = new String(ByteStreams.toByteArray(p.getErrorStream()), Charsets.UTF_8);
						log.warn("Failed to process audio with FFmpeg:\n{}", str);
						throw new ServletException("Failed to transcode; FFmpeg exited with code "+p.exitValue());
					}
					if (tmpFile != null) {
						log.debug("{} completed", fshortcut == null ? "Transcode" : "Remux");
						String blobName;
						do {
							String rand = Partyflow.randomString(16);
							blobName = "transcodes/"+rand.substring(0, 3)+"/"+rand+"."+fmt.getFileExtension();
						} while (Partyflow.storage.blobExists(Partyflow.storageContainer, blobName));
						Blob transBlob = Partyflow.storage.blobBuilder(blobName)
								.payload(tmpFile)
								.contentType(fmt.getMimeType())
								.contentDisposition(fmt.getUsage() == Usage.DOWNLOAD ? "attachment; filename="+filename+"; filename*=utf-8''"+filename : "inline")
								.cacheControl(published ? "public, immutable" : "private")
								.build();
						Partyflow.storage.putBlob(Partyflow.storageContainer, transBlob, new PutOptions().multipart().setBlobAccess(BlobAccess.PUBLIC_READ));
						return blobName;
					} else {
						return null;
					}
				} finally {
					if (tmpFile != null) tmpFile.delete();
				}
			};
			if (direct) {
				try {
					transcoder.call();
				} catch (Exception e) {
					throw new ServletException(e);
				}
				return;
			}
			
			String blobNameRes = ThreadPools.TRANSCODE.submit(transcoder).get();
			try (PreparedStatement ps = c.prepareStatement("INSERT INTO `transcodes` (`master`, `format`, `file`, `created_at`, `last_downloaded`) "
					+ "VALUES (?, ?, ?, NOW(), NOW());")) {
				ps.setString(1, master);
				ps.setInt(2, fmt.getDatabaseId());
				ps.setString(3, blobNameRes);
				ps.execute();
			}
			res.setHeader("Transcode-Status", fshortcut != null ? "SHORTCUT" : "FRESH");
			res.sendRedirect(Partyflow.resolveBlob(blobNameRes));
		} catch (SQLException | InterruptedException | ExecutionException e) {
			throw new ServletException(e);
		}
	}

	public static String encodeFilename(String str) {
		return UrlEscapers.urlFragmentEscaper().escape(str).replace(";", "%3B");
	}

}
