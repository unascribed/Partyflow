package com.unascribed.partyflow.handler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
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
import com.unascribed.partyflow.TranscodeFormat;
import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.SimpleHandler.GetOrHead;
import com.unascribed.partyflow.TranscodeFormat.Shortcut;
import com.unascribed.partyflow.TranscodeFormat.Usage;

import com.google.common.base.Charsets;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.io.ByteStreams;
import com.google.common.net.UrlEscapers;

public class TranscodeHandler extends SimpleHandler implements GetOrHead {

	private static final File WORK_DIR = new File(System.getProperty("java.io.tmpdir"), "partyflow/work");

	private static final Logger log = LoggerFactory
			.getLogger(TranscodeHandler.class);

	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		Map<String, String> query = parseQuery(req);
		String trackSlug = path;
		if (!query.containsKey("format")) {
			throw new UserVisibleException(HTTP_400_BAD_REQUEST, "Format is required");
		}
		String cleanedFormat = query.get("format").toUpperCase(Locale.ROOT).replace('-', '_');
		TranscodeFormat fmt;
		try {
			fmt = TranscodeFormat.valueOf(cleanedFormat);
		} catch (IllegalArgumentException e) {
			throw new UserVisibleException(HTTP_400_BAD_REQUEST, "Unrecognized format "+cleanedFormat);
		}
		if (!Partyflow.isFormatLegal(fmt)) {
			throw new UserVisibleException(HTTP_400_BAD_REQUEST, "Unrecognized format "+cleanedFormat);
		}
		String shortcutSource = null;
		Shortcut shortcut = null;
		Session s = SessionHelper.getSession(req);
		try (Connection c = Partyflow.sql.getConnection()) {
			String suffix = (s != null ? " OR releases.user_id = ?" : "");
			String addnFormats = Strings.repeat(", ?", fmt.getShortcuts().size());
			try (PreparedStatement ps = c.prepareStatement("SELECT transcode_id, transcodes.file, transcodes.format FROM tracks "
					+ "JOIN transcodes ON transcodes.track_id = tracks.track_id "
					+ "JOIN releases ON releases.release_id = tracks.release_id "
					+ "WHERE tracks.slug = ? AND transcodes.format IN (?"+addnFormats+") AND (releases.published = true"+suffix+");")) {
				int i = 1;
				ps.setString(i++, trackSlug);
				ps.setInt(i++, fmt.getDatabaseId());
				for (Shortcut sc : fmt.getShortcuts()) {
					ps.setInt(i++, sc.getSource().getDatabaseId());
				}
				if (s != null) {
					ps.setInt(i++, s.userId);
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
							if (!head) {
								try (PreparedStatement ps2 = c.prepareStatement("UPDATE transcodes SET last_downloaded = NOW() WHERE transcode_id = ?;")) {
									ps2.setInt(1, rs.getInt("transcode_id"));
									ps2.execute();
								}
							}
							res.setHeader("Transcode-Status", "CACHED");
							res.sendRedirect(Partyflow.resolveBlob(rs.getString("transcodes.file")));
							return;
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
			int trackId;
			String master;
			boolean published;
			String title;
			String releaseTitle;
			String creator;
			try (PreparedStatement ps = c.prepareStatement("SELECT track_id, master, published, tracks.title, releases.title, users.display_name FROM tracks "
					+ "JOIN releases ON releases.release_id = tracks.release_id "
					+ "JOIN users ON users.user_id = releases.user_id "
					+ "WHERE tracks.slug = ? AND (releases.published = true"+suffix+");")) {
				ps.setString(1, trackSlug);
				if (s != null) {
					ps.setInt(2, s.userId);
				}
				try (ResultSet rs = ps.executeQuery()) {
					if (rs.first()) {
						trackId = rs.getInt("track_id");
						master = rs.getString("master");
						published = rs.getBoolean("published");
						title = rs.getString("tracks.title");
						releaseTitle = rs.getString("releases.title");
						creator = rs.getString("users.display_name");
					} else {
						res.sendError(HTTP_404_NOT_FOUND);
						return;
					}
				}
			}
			if (shortcut == null) {
				log.debug("Transcoding to {} from master...", fmt);
			} else {
				log.debug("Remuxing to {} from {}...", fmt, shortcut.getSource());
			}
			WORK_DIR.mkdirs();
			Blob masterBlob = Partyflow.storage.getBlob(Partyflow.storageContainer, MoreObjects.firstNonNull(shortcutSource, master));
			File tmpFile = File.createTempFile("transcode-", "."+fmt.getFileExtension(), WORK_DIR);
			String guilt = (!fmt.getUsage().canDownload() ? ". Low-quality encode for streaming; consider downloading a real copy." : "");
			try {
				Process p = Partyflow.ffmpeg("-v", "error",
						"-i", "-",
						shortcut == null ? fmt.getFFmpegArguments() : shortcut.getFFmpegArguments(),
						"-map_metadata", "-1",
						"-map", "a",
						"-metadata", "title="+title,
						"-metadata", "album="+releaseTitle,
						"-metadata", "artist="+creator,
						"-metadata", "comment=Generated by Partyflow v"+Version.FULL+" hosted at "+Partyflow.publicUri.getHost()+guilt,
						"-y",
						tmpFile.getAbsolutePath());
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
				log.debug("{} completed", shortcut == null ? "Transcode" : "Remux");
				String blobName;
				do {
					String rand = Partyflow.randomString(16);
					blobName = "transcodes/"+rand.substring(0, 3)+"/"+rand+"."+fmt.getFileExtension();
				} while (Partyflow.storage.blobExists(Partyflow.storageContainer, blobName));
				String filename = encodeFilename(title+"."+fmt.getFileExtension());
				Blob transBlob = Partyflow.storage.blobBuilder(blobName)
						.payload(tmpFile)
						.contentType(fmt.getMimeType())
						.contentDisposition(fmt.getUsage() == Usage.DOWNLOAD ? "attachment; filename="+filename+"; filename*=utf-8''"+filename : "inline")
						.cacheControl(published ? "public, immutable" : "private")
						.build();
				Partyflow.storage.putBlob(Partyflow.storageContainer, transBlob, new PutOptions().multipart().setBlobAccess(BlobAccess.PUBLIC_READ));
				try (PreparedStatement ps = c.prepareStatement("INSERT INTO transcodes (track_id, format, file, created_at, last_downloaded) "
						+ "VALUES (?, ?, ?, NOW(), NOW());")) {
					ps.setInt(1, trackId);
					ps.setInt(2, fmt.getDatabaseId());
					ps.setString(3, blobName);
					ps.execute();
				}
				res.setHeader("Transcode-Status", shortcut != null ? "SHORTCUT" : "FRESH");
				res.sendRedirect(Partyflow.resolveBlob(blobName));
			} finally {
				tmpFile.delete();
			}
		} catch (SQLException e) {
			throw new ServletException(e);
		}
	}

	public static String encodeFilename(String str) {
		return UrlEscapers.urlFragmentEscaper().escape(str).replace(";", "%3B");
	}

}
