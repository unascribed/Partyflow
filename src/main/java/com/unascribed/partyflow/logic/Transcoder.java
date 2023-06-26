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

package com.unascribed.partyflow.logic;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import javax.annotation.WillClose;

import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.BlobAccess;
import org.jclouds.blobstore.options.PutOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.config.TranscodeFormat;
import com.unascribed.partyflow.config.TranscodeFormat.ReplayGainData;
import com.unascribed.partyflow.config.TranscodeFormat.Shortcut;
import com.unascribed.partyflow.config.TranscodeFormat.Usage;
import com.unascribed.partyflow.util.BlobByteSource;
import com.unascribed.partyflow.util.Commands;
import com.unascribed.partyflow.util.ForkOutputStream;
import com.unascribed.partyflow.util.IBXMByteSource;
import com.unascribed.partyflow.util.MoreByteStreams;
import com.unascribed.partyflow.util.Processes;
import com.unascribed.partyflow.util.Services;

import com.google.common.base.Charsets;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteSource;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.net.UrlEscapers;

import jakarta.servlet.ServletException;

public class Transcoder {
	
	private static final Logger log = LoggerFactory.getLogger(Transcoder.class);

	public interface DirectStreamSupplier {
		OutputStream get(String filename) throws IOException;
	}
	
	public record TranscodeResult(String blob, long size, String filename) {}

	public static final File WORK_DIR = new File(System.getProperty("java.io.tmpdir"), "partyflow/work");
	private static final Pattern MAGICK_SIZE_PATTERN = Pattern.compile("([0-9]+)x([0-9]+)");
	private static final AtomicInteger pipeNum = new AtomicInteger();

	public static TranscodeResult performTranscode(TranscodeFormat fmt, String kind, String slug, String src,
			String title, String releaseTitle, String creator, String art, String lyrics, int year, int trackNumber, ReplayGainData rgd,
			boolean cache, boolean published, Shortcut shortcut, DirectStreamSupplier directOut) throws IOException, ServletException {
		WORK_DIR.mkdirs();
		var st = SpecialTrack.BY_SLUG.get(src);
		ByteSource masterBlob;
		if (st == null) {
			masterBlob = new BlobByteSource(Storage.getBlob(src));
		} else {
			masterBlob = switch (st) {
				case TEST_TRACK -> null;
				case SAMPLE_SONG -> new IBXMByteSource("sample-song.xm.gz");
			};
		}
		if (masterBlob == null && st != SpecialTrack.TEST_TRACK) {
			log.error("Master for {} {} is missing!", kind, slug);
			return new TranscodeResult(null, 0, null);
		}
		File tmpFile = cache ? File.createTempFile("transcode-", "."+fmt.fileExtension(), WORK_DIR) : null;
		boolean attachArt = art != null && fmt.usage().canDownload() && !fmt.args().contains("-vn");
		boolean ogg = fmt.args().contains("ogg");
		String artB64 = null;
		File artFile = null;
		if (art != null && attachArt) {
			artFile = File.createTempFile("transcode-", art.substring(art.lastIndexOf('.')), WORK_DIR);
			Blob artBlob = Storage.getBlob(art);
			if (artBlob == null) {
				log.warn("Art for {} {} is missing!", kind, slug);
				attachArt = false;
			} else {
				try (var p = artBlob.getPayload();
						var in = p.openStream();
						var out = new FileOutputStream(artFile)) {
					in.transferTo(out);
				}
				if (ogg) {
					attachArt = false;
					// FFmpeg doesn't support writing Ogg album art...
					// Been an open feature request for 7 years
					Process p = Commands.magick_convert(artFile.getPath(), "-identify", "null:-").start();
					p.getOutputStream().close();
					String out = MoreByteStreams.slurp(p.getInputStream());
					String err = MoreByteStreams.slurp(p.getErrorStream());
					if (Processes.waitForUninterruptibly(p) != 0) {
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
			boolean useAltcmd = shortcut == null && fmt.altcmd() != null;
			List<Process> processes = new ArrayList<>();
			List<String> inputArgs;
			if (masterBlob == null) {
				inputArgs = List.of("-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo:d=1");
			} else {
				inputArgs = List.of("-i", "-");
			}
			ProcessBuilder ffmBldr = Commands.ffmpeg("-v", "error",
					useAltcmd ? List.of("-i", "-") : inputArgs, "-i", metaFile.getAbsolutePath(),
							attachArt && artFile != null ? List.of("-i", artFile.getAbsolutePath()) : null,
					shortcut == null ? removeCopyIf(fmt.args(), st != null) : shortcut.args(),
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
					directOut != null ? "-" : Objects.requireNonNull(tmpFile).getAbsolutePath());
			Process input;
			if (useAltcmd) {
				ProcessBuilder inffm = Commands.ffmpeg("-v", "error",
						inputArgs,
						"-f", "wav",
						"-map_metadata", "-1",
						"-");
				ProcessBuilder altcmd = Commands.altcmd(fmt.altcmd(), fmt.altcmdargs());
				processes.addAll(ProcessBuilder.startPipeline(List.of(inffm, altcmd, ffmBldr)));
				input = processes.get(0);
			} else {
				input = ffmBldr.start();
				processes.add(input);
			}
			Process ffm = processes.get(processes.size()-1);
			String filename = creator+" - "+(releaseTitle == null ? "" : releaseTitle+" - ")+String.format("%02d", trackNumber)+" "+title+"."+fmt.fileExtension();
			String filenameEncoded = encodeFilename(filename);
			if (directOut != null) {
				OutputStream dio = directOut.get(filenameEncoded);
				OutputStream out = cache ? new ForkOutputStream(new FileOutputStream(tmpFile), dio) : dio;
				pipe(ffm, out);
			}
			if (masterBlob != null) {
				try (var in = masterBlob.openStream();
						var out = input.getOutputStream()) {
					ByteStreams.copy(in, out);
				} catch (IOException e) {
					if (!"Broken pipe".equals(e.getMessage())) {
						throw e;
					}
				}
			} else {
				input.getOutputStream().close();
			}
			AtomicBoolean errored = new AtomicBoolean(false);
			CountDownLatch cdl = new CountDownLatch(processes.size());
			for (var p : processes) {
				new Thread(() -> {
					try {
						String err = MoreByteStreams.slurp(p.getErrorStream());
						if (Processes.waitForUninterruptibly(p) != 0) {
							log.warn("Failed to process audio:\n{}", err);
							errored.set(true);
						}
					} catch (IOException e) {
						log.warn("Failed to process audio:\n{}", e);
						errored.set(true);
					} finally {
						cdl.countDown();
					}
				}, "Process watcher").start();
			}
			try {
				cdl.await();
			} catch (InterruptedException e) {
			}
			if (errored.get()) {
				throw new ServletException("Failed to process audio");
			}
			if (tmpFile != null) {
				log.debug("{} of {} to {} completed", shortcut == null ? "Transcode" : "Remux", title, fmt);
				String blobName;
				do {
					String rand = Partyflow.randomString(Services.random, 16);
					blobName = "transcodes/"+rand.substring(0, 3)+"/"+rand+"."+fmt.fileExtension();
				} while (Storage.blobExists(blobName));
				Blob transBlob = Storage.blobBuilder(blobName)
						.payload(tmpFile)
						.contentType(fmt.mimeType())
						.contentDisposition(fmt.usage() == Usage.DOWNLOAD ? "attachment; filename="+filenameEncoded+"; filename*=utf-8''"+filenameEncoded : "inline")
						.cacheControl(published ? "public, immutable" : "private")
						.build();
				Storage.putBlob(transBlob, new PutOptions().multipart().setBlobAccess(BlobAccess.PUBLIC_READ));
				return new TranscodeResult(blobName, tmpFile.length(), filename);
			} else {
				return new TranscodeResult(null, 0, filename);
			}
		} finally {
			if (tmpFile != null) tmpFile.delete();
			if (artFile != null) artFile.delete();
			metaFile.delete();
		}
	}

	private static List<String> removeCopyIf(ImmutableList<String> args, boolean when) {
		if (!when) return args;
		int idx = args.indexOf("-codec:a");
		if (idx == -1) return args;
		if (args.get(idx+1).equals("copy")) {
			var li = new ArrayList<>(args);
			li.remove(idx);
			li.remove(idx);
			return li;
		}
		return args;
	}

	@SuppressWarnings("resource")
	private static void pipe(Process a, Process b) {
		pipe(a.getInputStream(), b.getOutputStream());
	}

	@SuppressWarnings("resource")
	public static void pipe(Process a, @WillClose OutputStream b) {
		pipe(a.getInputStream(), b);
	}

	@SuppressWarnings("resource")
	private static void pipe(@WillClose InputStream a, @WillClose OutputStream b) {
		new Thread(() -> {
			try (a; b) {
				a.transferTo(b);
			} catch (IOException e) {
				log.warn("Exception while copying", e);
			}
		}, "Pipe #"+pipeNum.getAndIncrement()).start();
	}

	public static String encodeFilename(String str) {
		return UrlEscapers.urlFragmentEscaper().escape(str).replace(";", "%3B");
	}
	
	public static void cleanup() {
		try (var c = Partyflow.sql.getConnection()) {
			try (var ps = c.prepareStatement("SELECT `transcode_id`, `file` FROM `transcodes` WHERE `last_downloaded` <= ? AND `master` != '__testtrack';");
					var ps2 = c.prepareStatement("DELETE FROM `transcodes` WHERE `transcode_id` = ?;")) {
				ps.setTimestamp(1, new Timestamp(Instant.now().minus(Partyflow.config.storage.pruneTime).toEpochMilli()));
				try (var rs = ps.executeQuery()) {
					int removed = 0;
					while (rs.next()) {
						ps2.setLong(1, rs.getLong("transcode_id"));
						if (ps2.executeUpdate() > 0) {
							Storage.removeBlob(rs.getString("file"));
							removed++;
						}
					}
					if (removed > 0) {
						log.debug("Pruned {} old transcode{}", removed, removed == 1 ? "" : "s");
					}
				}
			}
		} catch (SQLException e) {
			log.warn("Failed to prune old transcodes", e);
		}
	}

}
