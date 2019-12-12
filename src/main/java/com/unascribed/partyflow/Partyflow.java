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

package com.unascribed.partyflow;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.Iterator;
import java.util.Properties;
import java.util.regex.Pattern;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.eclipse.jetty.server.AsyncRequestLogWriter;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.Jetty;
import org.h2.jdbcx.JdbcConnectionPool;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.filesystem.reference.FilesystemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.asyncsimplelog.AsyncSimpleLog;
import com.unascribed.partyflow.handler.CreateReleaseHandler;
import com.unascribed.partyflow.handler.FilesHandler;
import com.unascribed.partyflow.handler.MustacheHandler;
import com.unascribed.partyflow.handler.ReleaseHandler;
import com.unascribed.partyflow.handler.ReleasesHandler;
import com.unascribed.partyflow.handler.StaticHandler;
import com.unascribed.random.RandomXoshiro256StarStar;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.net.UrlEscapers;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.api.DeserializationException;
import blue.endless.jankson.api.SyntaxError;

public class Partyflow {

	private static final Logger log = LoggerFactory.getLogger(Partyflow.class);

	public static Config config;
	public static DataSource sql;
	public static BlobStore storage;
	public static String storageContainer;

	private static final ThreadLocal<RandomXoshiro256StarStar> rand = ThreadLocal.withInitial(RandomXoshiro256StarStar::new);

	public static RandomXoshiro256StarStar getRandom() {
		return rand.get();
	}

	public static void main(String[] args) {
		System.out.print("\u001Bc");
		AsyncSimpleLog.startLogging();
		try {
			String configStr = "{\n"+Files.asCharSource(new File("partyflow-config.jkson"), Charsets.UTF_8).read()+"\n}";
			config = Jankson.builder().build().fromJsonCarefully(configStr, Config.class);
		} catch (FileNotFoundException e) {
			log.error("partyflow-config.jkson does not exist. Cannot start.");
			return;
		} catch (IOException | DeserializationException e) {
			log.error("Failed to load partyflow-config.jkson", e);
			return;
		} catch (SyntaxError e) {
			log.error("Failed to load partyflow-config.jkson: syntax error {} in partyflow-config.jkson\n{}", e.getLineMessage().replaceFirst("^Errored ", ""), e.getMessage());
			return;
		}
		AsyncSimpleLog.setMinLogLevel(config.logger.level);
		AsyncSimpleLog.setAnsi(config.logger.color);
		AsyncSimpleLog.ban(Pattern.compile("^org\\.eclipse\\.jetty"));
		log.info("Partyflow v{} starting up...", Version.FULL);

		try {
			String url = "jdbc:h2:"+UrlEscapers.urlPathSegmentEscaper().escape(config.database.file).replace("%2F", "/");
			sql = JdbcConnectionPool.create(url, "", "");
			((JdbcConnectionPool)sql).setMaxConnections(1);
			try (Connection c = sql.getConnection()) {
				try (Statement s = c.createStatement()) {
					s.execute("CREATE TABLE IF NOT EXISTS releases (\n" +
							"    release_id  BIGINT AUTO_INCREMENT PRIMARY KEY,\n" +
							"    title       VARCHAR(255) NOT NULL,\n" +
							"    subtitle    VARCHAR(255) NOT NULL,\n" +
							"    slug        VARCHAR(255) NOT NULL UNIQUE,\n" +
							"    published   BOOL NOT NULL,\n" +
							"    art         VARCHAR(255),\n" +
							"    description CLOB NOT NULL\n" +
							");");
					s.execute("CREATE TABLE IF NOT EXISTS tracks (\n" +
							"    track_id    BIGINT AUTO_INCREMENT PRIMARY KEY,\n" +
							"    release_id  BIGINT NOT NULL,\n" +
							"    title       VARCHAR(255) NOT NULL,\n" +
							"    subtitle    VARCHAR(255) NOT NULL,\n" +
							"    slug        VARCHAR(255) NOT NULL UNIQUE,\n" +
							"    art         VARCHAR(255),\n" +
							"    master      VARCHAR(255),\n" +
							"    description CLOB NOT NULL\n" +
							");");
					s.execute("CREATE TABLE IF NOT EXISTS transcodes (\n" +
							"    track_id    BIGINT AUTO_INCREMENT PRIMARY KEY,\n" +
							"    master      VARCHAR(255),\n" +
							"    format      INT,\n" +
							"    file        VARCHAR(255)\n" +
							");");
				}
			}
		} catch (SQLException e) {
			if (e.getCause() != null && e.getCause() instanceof IllegalStateException && e.getCause().getMessage().contains("file is locked")) {
				log.error("Failed to open the database at {}.mv.db: The file is in use.\n"
						+ "Close any other instances of Partyflow or database editors and try again", config.database.file);
			} else {
				log.error("Failed to open database at {}.m", config.database.file, e);
			}
			return;
		}
		log.info("Opened database at {}.mv.db", config.database.file);

		File f = new File(config.storage.dir).getAbsoluteFile();
		f.mkdirs();
		Properties bsProps = new Properties();
		bsProps.setProperty(FilesystemConstants.PROPERTY_BASEDIR, f.getParent());
		storage = ContextBuilder.newBuilder("filesystem")
				.overrides(bsProps)
				.build(BlobStoreContext.class)
				.getBlobStore();
		storageContainer = f.getName();
		log.info("Prepared storage");

		String majorJavaVer;
		String ver = System.getProperty("java.version");
		Iterator<String> iter = Splitter.on('.').split(ver).iterator();
		String first = iter.next();
		if ("1".equals(first)) {
			majorJavaVer = iter.next();
		} else {
			majorJavaVer = first;
		}

		String poweredBy = "Jetty "+Splitter.on('.').split(Jetty.VERSION).iterator().next()+", "
				+ "Java "+majorJavaVer;

		String displayBind;
		if ("0.0.0.0".equals(config.http.bind)) {
			displayBind = "*";
		} else {
			displayBind = config.http.bind;
		}
		Server server = new Server(new InetSocketAddress(config.http.bind, config.http.port));
		HandlerCollection hc = new HandlerCollection(
				setHeader("Clacks-Overhead", "GNU Natalie Nguyen, Shiina Mota"),
				setHeader("Server", "Partyflow v"+Version.FULL),
				setHeader("Powered-By", poweredBy),
				handler("", new MustacheHandler("index.hbs.html")),
				handler("assets/partyflow.css", new MustacheHandler("partyflow.hbs.css")),
				handler("assets/create-release.js", new MustacheHandler("create-release.hbs.js")),
				handler("create-release", new CreateReleaseHandler()),
				handler("releases", new ReleasesHandler()),
				handler("releases/", new ReleaseHandler()),
				handler("static/", new StaticHandler()),
				handler("files/", new FilesHandler())
			);
		server.setHandler(hc);
		if (!"/dev/null".equals(config.http.accessLog)) {
			server.setRequestLog(new CustomRequestLog(new AsyncRequestLogWriter(config.http.accessLog), CustomRequestLog.EXTENDED_NCSA_FORMAT));
		}
		try {
			server.start();
		} catch (Exception e) {
			if (e instanceof IOException && e.getCause() instanceof BindException
					&& e.getCause().getMessage().equals("Address already in use")) {
				log.error("Failed to start HTTP server: {}:{} is already in use by another program", displayBind, config.http.port);
			} else {
				log.error("Failed to start HTTP server", e);
			}
			return;
		}
		log.info("Listening on http://{}:{}", displayBind, config.http.port);
		log.info("Ready.");

	}

	private static Handler setHeader(String header, String value) {
		return new AbstractHandler() {
			@Override
			public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
					throws IOException, ServletException {
				response.setHeader(header, value);
			}
		};
	}

	private static Handler handler(String path, SimpleHandler sh) {
		return new PathResolvingHandler(path, sh.asJettyHandler());
	}

	// misc utilities

	public static byte[] readWithLimit(InputStream in, long limit) throws IOException {
		byte[] bys = ByteStreams.toByteArray(ByteStreams.limit(in, limit));
		if (in.read() != -1) return null;
		return bys;
	}

	private static final String RANDOM_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-";

	public static String randomString(int len) {
		StringBuilder sb = new StringBuilder(len);
		RandomXoshiro256StarStar rand = getRandom();
		for (int i = 0; i < len; i++) {
			sb.append(RANDOM_CHARS.charAt(rand.nextInt(RANDOM_CHARS.length())));
		}
		return sb.toString();
	}

	private static final Pattern ILLEGAL = Pattern.compile("[^\\p{IsLetter}\\p{IsDigit}- ]");
	private static final Pattern PUNCT = Pattern.compile("[\\p{IsPunctuation}]");
	private static final Pattern SPACE = Pattern.compile("[\\p{Space}]+");

	public static String sanitizeSlug(String name) {
		String s = Normalizer.normalize(name, Form.NFC);
		s = PUNCT.matcher(s).replaceAll("-");
		s = SPACE.matcher(s).replaceAll(" ");
		s = ILLEGAL.matcher(s).replaceAll("");
		return s;
	}

	public static String resolveArt(String art) {
		if (art == null) {
			return config.http.path+"static/default_art.svg";
		} else {
			if (config.storage.publicUrlPattern.startsWith("/")) {
				return config.storage.publicUrlPattern.replace("{}", art);
			} else {
				return config.http.path+config.storage.publicUrlPattern.replace("{}", art);
			}
		}
	}

	public static Process magick_convert(String commandLine) throws IOException {
		return Runtime.getRuntime().exec(config.programs.magickConvert+" "+commandLine);
	}

}
