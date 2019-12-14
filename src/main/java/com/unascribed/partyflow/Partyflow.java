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
import java.net.URL;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.text.Normalizer.Form;
import java.util.Iterator;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;
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
import com.unascribed.partyflow.handler.LoginHandler;
import com.unascribed.partyflow.handler.LogoutHandler;
import com.unascribed.partyflow.handler.MustacheHandler;
import com.unascribed.partyflow.handler.ReleaseHandler;
import com.unascribed.partyflow.handler.ReleasesHandler;
import com.unascribed.partyflow.handler.SetupHandler;
import com.unascribed.partyflow.handler.StaticHandler;
import com.unascribed.random.RandomXoshiro256StarStar;

import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.collect.Maps;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.common.net.UrlEscapers;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.api.DeserializationException;
import blue.endless.jankson.api.SyntaxError;

public class Partyflow {

	private static final Logger log = LoggerFactory.getLogger("Bootstrap");

	public static Config config;
	public static DataSource sql;
	public static BlobStore storage;
	public static String storageContainer;
	public static String setupToken;
	public static Key sessionSecret;

	public static ConcurrentMap<String, Long> csrfTokens = Maps.newConcurrentMap();

	public static final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor();

	private static final ThreadLocal<RandomXoshiro256StarStar> rand = ThreadLocal.withInitial(RandomXoshiro256StarStar::new);
	private static final SecureRandom secureRand = new SecureRandom();

	public static RandomXoshiro256StarStar getRandom() {
		return rand.get();
	}

	public static void main(String[] args) {
		System.out.print("\u001Bc");
		AsyncSimpleLog.startLogging();
		try {
			File file = new File("partyflow-config.jkson");
			String configStr = Files.asCharSource(file, Charsets.UTF_8).read();
			Jankson jkson = Jankson.builder().build();
			JsonObject obj = jkson.load("{\n"+configStr+"\n}");
			config = jkson.fromJsonCarefully(obj, Config.class);
			if (config.security.sessionSecret == null) {
				String secret;
				BaseEncoding hex = BaseEncoding.base16().lowerCase();
				try {
					secret = hex.encode(SecureRandom.getInstanceStrong().generateSeed(32));
				} catch (NoSuchAlgorithmException e) {
					secret = hex.encode(new SecureRandom().generateSeed(32));
				}
				config.security.sessionSecret = secret;
				String hack = configStr.replaceFirst("(\"?)sessionSecret(\"?:\\s+)null", "$1sessionSecret$2\""+secret+"\"");
				Files.asCharSink(file, Charsets.UTF_8).write(hack);
			}
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

		byte[] sessionSecretBytes = config.security.sessionSecret.getBytes(Charsets.UTF_8);
		sessionSecret = new SecretKey() {

			@Override
			public String getFormat() {
				return "RAW";
			}

			@Override
			public byte[] getEncoded() {
				return sessionSecretBytes.clone();
			}

			@Override
			public String getAlgorithm() {
				return "RAW";
			}
		};

		try {
			String url = "jdbc:h2:"+UrlEscapers.urlPathSegmentEscaper().escape(config.database.file).replace("%2F", "/");
			sql = JdbcConnectionPool.create(url, "", "");
			try (Connection c = sql.getConnection()) {
				try (Statement s = c.createStatement()) {
					int dataVersion;
					try {
						try (ResultSet rs = s.executeQuery("SELECT value FROM meta WHERE name = 'data_version';")) {
							if (rs.first()) {
								dataVersion = Integer.parseInt(rs.getString("value"));
							} else {
								throw new SQLException("data_version not present");
							}
						}
					} catch (SQLException e) {
						if (e.getMessage() != null && e.getMessage().contains("not found")) {
							log.info("Initializing database...");
							s.execute(new String(Resources.toByteArray(ClassLoader.getSystemResource("sql/init.sql")), Charsets.UTF_8));
							dataVersion = 0;
						} else {
							throw e;
						}
					}
					if (dataVersion > 0 && ClassLoader.getSystemResource("sql/upgrade_to_"+dataVersion) == null) {
						log.error("Database version {} is not recognized by this version of Partyflow", dataVersion);
						return;
					}
					while (true) {
						URL upgrade = ClassLoader.getSystemResource("sql/upgrade_to_"+(dataVersion+1)+".sql");
						if (upgrade != null) {
							log.info("Upgrading database from v{} to v{}...", dataVersion, dataVersion+1);
							s.execute(new String(Resources.toByteArray(upgrade), Charsets.UTF_8));
							dataVersion++;
						} else {
							break;
						}
					}
				}
			}
		} catch (IOException e) {
			log.error("Failed to load init.sql off classpath", e);
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
				new SetupHandler().asJettyHandler(),
				handler("", new MustacheHandler("index.hbs.html")),
				handler("assets/partyflow.css", new MustacheHandler("partyflow.hbs.css")),
				handler("assets/password-hasher.js", new MustacheHandler("password-hasher.hbs.js")),
				handler("assets/edit-art.js", new MustacheHandler("edit-art.hbs.js")),
				handler("create-release", new CreateReleaseHandler()),
				handler("login", new LoginHandler()),
				handler("logout", new LogoutHandler()),
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

		try (Connection c = sql.getConnection()) {
			try (Statement s = c.createStatement()) {
				try (ResultSet rs = s.executeQuery("SELECT 1 FROM users WHERE admin = true LIMIT 1;")) {
					if (!rs.first()) {
						setupToken = randomString(32);
						log.info("There are no admin users. Entering setup mode.\n"
								+ "The secret token is {}", setupToken);
					}
				}
			}
		} catch (SQLException e) {
			log.warn("Failed to check for the existence of admin users");
		}

		sched.scheduleWithFixedDelay(() -> {
			try (Connection c = sql.getConnection()) {
				try (Statement s = c.createStatement()) {
					int deleted = s.executeUpdate("DELETE FROM sessions WHERE expires < NOW();");
					if (deleted > 0) {
						log.debug("Pruned {} expired sessions", deleted);
					}
				}
			} catch (SQLException e) {
				log.warn("Failed to prune expired sessions");
			}
		}, 0, 1, TimeUnit.HOURS);
		sched.scheduleWithFixedDelay(() -> {
			Iterator<Long> i = csrfTokens.values().iterator();
			long now = System.currentTimeMillis();
			while (i.hasNext()) {
				if (i.next() < now) {
					i.remove();
				}
			}
		}, 15, 15, TimeUnit.MINUTES);
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

	public static String allocateCsrfToken() {
		String token;
		synchronized (secureRand) {
			token = randomString(secureRand, 32);
		}
		csrfTokens.put(token, System.currentTimeMillis()+(30*60*1000));
		return token;
	}

	public static boolean isCsrfTokenValid(String token) {
		if (token == null) return false;
		Long l = csrfTokens.get(token);
		return l != null && l > System.currentTimeMillis();
	}

	public static byte[] readWithLimit(InputStream in, long limit) throws IOException {
		byte[] bys = ByteStreams.toByteArray(ByteStreams.limit(in, limit));
		if (in.read() != -1) return null;
		return bys;
	}

	private static final String RANDOM_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-";

	public static String randomString(int len) {
		return randomString(getRandom(), len);
	}

	public static String randomString(Random rand, int len) {
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			sb.append(RANDOM_CHARS.charAt(rand.nextInt(RANDOM_CHARS.length())));
		}
		return sb.toString();
	}

	private static final Pattern ILLEGAL = Pattern.compile("[^\\p{IsLetter}\\p{IsDigit} ]");
	private static final Pattern SPACE = Pattern.compile("[\\p{Space}]+");

	public static String sanitizeSlug(String name) {
		String s = Normalizer.normalize(name, Form.NFC);
		s = SPACE.matcher(s).replaceAll(" ");
		s = ILLEGAL.matcher(s).replaceAll("");
		return s.trim();
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
