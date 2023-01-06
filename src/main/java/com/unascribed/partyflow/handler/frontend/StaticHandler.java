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
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.unascribed.partyflow.ThreadPools;
import com.unascribed.partyflow.Version;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler.GetOrHead;

public class StaticHandler extends SimpleHandler implements GetOrHead {

	private static final Logger log = LoggerFactory.getLogger(StaticHandler.class);
	
	private static final String etag = StaticHandler.class.getPackage().getImplementationVersion();
	private static final String qetag = "\""+etag+"\"";
	
	@Override
	public void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head)
			throws IOException, ServletException {
		if (path.endsWith(".gz")) {
			res.sendError(HTTP_404_NOT_FOUND);
			return;
		}
		if (etag != null && qetag.equals(req.getHeader("If-None-Match"))) {
			res.setStatus(HTTP_304_NOT_MODIFIED);
			res.getOutputStream().close();
			return;
		}
		var encodings = ((Request)req).getHttpFields().getQualityCSV(HttpHeader.ACCEPT_ENCODING);
		URL gz = ClassLoader.getSystemResource("static/"+path+".gz");
		URL raw = ClassLoader.getSystemResource("static/"+path);
		
		String etag = StaticHandler.etag;
		String qetag = etag == null ? null : StaticHandler.qetag;
		if (etag == null) {
			if (raw != null && "file".equals(raw.getProtocol())) {
				// dev environment, use lastModified as etag
				try {
					File f = new File(raw.toURI());
					etag = "dev-"+Long.toHexString(f.lastModified());
					qetag = "\""+etag+"\"";
					if (qetag.equals(req.getHeader("If-None-Match"))) {
						res.setStatus(HTTP_304_NOT_MODIFIED);
						res.getOutputStream().close();
						return;
					}
				} catch (URISyntaxException e) {}
			}
		}
		
		
		String mime = "application/octet-stream";
		Long len;
		InputStream in;
		if (gz != null) {
			var conn = gz.openConnection();
			conn.setDoInput(!head);
			if (encodings.contains("gzip")) {
				len = conn.getContentLengthLong();
				res.setHeader("Content-Encoding", "gzip");
				in = head ? null : conn.getInputStream();
			} else {
				len = null;
				in = head ? null : new GZIPInputStream(conn.getInputStream());
			}
		} else if (raw != null) {
			var conn = raw.openConnection();
			conn.setDoInput(!head);
			len = conn.getContentLengthLong();
			in = head ? null : conn.getInputStream();
		} else {
			if (path.equals("quine.zip") && StaticHandler.etag == null) {
				in = buildDevQuine();
				len = null;
			} else {
				res.sendError(HTTP_404_NOT_FOUND);
				return;
			}
		}
		if (path.endsWith(".svg")) {
			mime = "image/svg+xml";
		} else if (path.endsWith(".js")) {
			mime = "application/javascript";
		} else if (path.endsWith(".css")) {
			mime = "text/css";
		} else if (path.endsWith(".png")) {
			mime = "image/png";
		}
		if ("quine.zip".equals(path)) {
			res.setHeader("Content-Disposition", "attachment; filename=Partyflow-src-v"+Version.FULL+".zip");
		}
		res.setHeader("Content-Type", mime);
		res.setHeader("ETag", qetag);
		if (len != null) res.setHeader("Content-Length", Long.toString(len));
		if (in != null) {
			try (in; var out = res.getOutputStream()) {
				in.transferTo(out);
			}
		} else {
			res.getOutputStream().close();
		}
	}

	private InputStream buildDevQuine() throws IOException {
		var out = new PipedOutputStream();
		ThreadPools.GENERIC.execute(() -> {
			try (var zos = new ZipOutputStream(out)) {
				var root = new File(".").toPath();
				var ignores = Files.readAllLines(root.resolve(".gitignore")).stream()
						.filter(s -> !s.startsWith("#"))
						.map(s -> s.replaceFirst("^/", "^")
								.replace(".", "\\.")
								.replace("*", "[^/]*"))
						.map(s -> Pattern.compile(s+"(/|$)"))
						.collect(Collectors.toCollection(ArrayList::new));
				ignores.add(Pattern.compile("^.git(/|$)"));
				Files.walkFileTree(root, new FileVisitor<Path>() {
					private boolean ignore(Path path) {
						var str = root.relativize(path).toString();
						return ignores.stream().anyMatch(p -> p.matcher(str).matches());
					}
					
					private void startEntry(Path path) throws IOException {
						var attr = Files.readAttributes(path, BasicFileAttributes.class);
						var ze = new ZipEntry(root.relativize(path).toString()+(attr.isDirectory()?"/":""));
						ze.setLastModifiedTime(attr.lastModifiedTime());
						ze.setLastAccessTime(attr.lastAccessTime());
						ze.setCreationTime(attr.creationTime());
						if (attr.isDirectory()) {
							ze.setMethod(ZipEntry.STORED);
							ze.setSize(0);
							ze.setCompressedSize(0);
							ze.setCrc(0);
						} else {
							ze.setMethod(ZipEntry.DEFLATED);
						}
						zos.putNextEntry(ze);
					}
					
					@Override
					public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
						if (!ignore(dir)) {
							if (!dir.equals(root)) {
								startEntry(dir);
								zos.closeEntry();
							}
							return FileVisitResult.CONTINUE;
						}
						return FileVisitResult.SKIP_SUBTREE;
					}

					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
						if (!ignore(file)) {
							startEntry(file);
							try (var in = Files.newInputStream(file)) {
								in.transferTo(zos);
							}
							zos.closeEntry();
						}
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
						log.warn("Exception while making devquine", exc);
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
						if (exc != null) log.warn("Exception while making devquine", exc);
						return FileVisitResult.CONTINUE;
					}
				});
			} catch (IOException e) {
				log.error("Error while making devquine", e);
			}
		});
		return new PipedInputStream(out);
	}

}
