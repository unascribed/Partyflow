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
import java.lang.ProcessBuilder.Redirect;
import java.net.BindException;
import java.net.URI;
import java.net.URISyntaxException;
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
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.annotation.Detainted;
import javax.annotation.Tainted;
import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import org.eclipse.jetty.server.AsyncRequestLogWriter;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.CustomRequestLog;
import org.eclipse.jetty.server.ForwardedRequestCustomizer;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.util.Jetty;
import org.h2.jdbcx.JdbcConnectionPool;
import org.h2.tools.Shell;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStore;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.filesystem.reference.FilesystemConstants;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.mariadb.jdbc.MariaDbPoolDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.asyncsimplelog.AsyncSimpleLog;
import com.unascribed.partyflow.config.Config;
import com.unascribed.partyflow.config.TranscodeFormat;
import com.unascribed.partyflow.config.Config.DatabaseSection.DatabaseDriver;
import com.unascribed.partyflow.config.Config.SecuritySection.ScryptSection;
import com.unascribed.partyflow.config.Config.StorageSection.StorageDriver;
import com.unascribed.partyflow.data.QMeta;
import com.unascribed.partyflow.handler.FilesHandler;
import com.unascribed.partyflow.handler.api.v1.LoginApi;
import com.unascribed.partyflow.handler.api.v1.ReleasesApi;
import com.unascribed.partyflow.handler.api.v1.ViewReleaseApi;
import com.unascribed.partyflow.handler.api.v1.WhoAmIApi;
import com.unascribed.partyflow.handler.frontend.AdminHandler;
import com.unascribed.partyflow.handler.frontend.ColorsHandler;
import com.unascribed.partyflow.handler.frontend.CreateReleaseHandler;
import com.unascribed.partyflow.handler.frontend.DownloadHandler;
import com.unascribed.partyflow.handler.frontend.IndexHandler;
import com.unascribed.partyflow.handler.frontend.ReleasesHandler;
import com.unascribed.partyflow.handler.frontend.SetupHandler;
import com.unascribed.partyflow.handler.frontend.StaticHandler;
import com.unascribed.partyflow.handler.frontend.TrackHandler;
import com.unascribed.partyflow.handler.frontend.release.DeleteReleaseHandler;
import com.unascribed.partyflow.handler.frontend.release.EditReleaseHandler;
import com.unascribed.partyflow.handler.frontend.release.PublishReleaseHandler;
import com.unascribed.partyflow.handler.frontend.release.AddTrackHandler;
import com.unascribed.partyflow.handler.frontend.release.ViewReleaseHandler;
import com.unascribed.partyflow.handler.frontend.session.LoginHandler;
import com.unascribed.partyflow.handler.frontend.session.LogoutHandler;
import com.unascribed.partyflow.handler.frontend.transcode.TranscodeReleaseHandler;
import com.unascribed.partyflow.handler.frontend.transcode.TranscodeReleaseZipHandler;
import com.unascribed.partyflow.handler.frontend.transcode.TranscodeTrackHandler;
import com.unascribed.partyflow.handler.frontend.release.UnpublishReleaseHandler;
import com.unascribed.partyflow.handler.util.MustacheHandler;
import com.unascribed.partyflow.handler.util.PartyflowErrorHandler;
import com.unascribed.partyflow.handler.util.PathResolvingHandler;
import com.unascribed.partyflow.handler.util.SimpleHandler;
import com.unascribed.partyflow.logic.AACSupport;
import com.unascribed.partyflow.logic.CSRF;
import com.unascribed.partyflow.logic.SessionHelper;
import com.unascribed.partyflow.logic.SpecialTrack;
import com.unascribed.partyflow.logic.Storage;
import com.unascribed.partyflow.logic.Transcoder;
import com.unascribed.partyflow.logic.URLs;
import com.unascribed.partyflow.logic.UserRole;
import com.unascribed.partyflow.util.Commands;
import com.unascribed.partyflow.util.Dankson;
import com.unascribed.partyflow.util.Services;
import com.google.common.base.Charsets;
import com.google.common.base.Splitter;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.hash.BloomFilter;
import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteStreams;
import com.google.common.io.CountingOutputStream;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.common.net.UrlEscapers;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;
import blue.endless.jankson.api.DeserializationException;
import blue.endless.jankson.api.SyntaxError;

public class Partyflow {

	private static final Logger log = LoggerFactory.getLogger("Bootstrap");

	public static Config config;
	public static DataSource sql;
	public static String setupToken;
	public static Key sessionSecret;
	public static URI publicUri;

	public static void main(String[] args) {
		Stopwatch sw = Stopwatch.createStarted();
		AsyncSimpleLog.startLogging();
		Dankson jkson = new Dankson(Jankson.builder()
				.registerDeserializer(JsonPrimitive.class, Duration.class, (p, m) -> Duration.parse("P"+p.asString()))
				.allowBareRootObject());
		try {
			File file = new File("partyflow-config.jkson");
			String configStr = Files.asCharSource(file, Charsets.UTF_8).read();
			JsonObject obj = jkson.load("partyflow-config.jkson", configStr);
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
			System.exit(1);
			return;
		} catch (IOException | DeserializationException e) {
			log.error("Failed to load partyflow-config.jkson", e);
			System.exit(1);
			return;
		} catch (SyntaxError e) {
			log.error("Failed to load partyflow-config.jkson: syntax error {} in partyflow-config.jkson\n{}", e.getLineMessage().replaceFirst("^Errored ", ""), e.getMessage());
			System.exit(1);
			return;
		}
		if (config.security.scrypt == null) config.security.scrypt = new ScryptSection();
		config.custom = unwrap(config.custom);
		AsyncSimpleLog.setMinLogLevel(config.logger.level);
		AsyncSimpleLog.setAnsi(config.logger.color);
		AsyncSimpleLog.ban(Pattern.compile("^org\\.eclipse\\.jetty"));
		AsyncSimpleLog.ban(Pattern.compile("^org\\.mariadb\\.jdbc\\.client"));
		AsyncSimpleLog.ban(Pattern.compile("^jclouds\\."));
		AsyncSimpleLog.ban(Pattern.compile("^org\\.jclouds\\.(http|rest)\\.internal"));
		log.info("Partyflow v{} starting up...", Version.FULL);
		
		if (config.database.expectedTraffic > 0) {
			var counter = new CountingOutputStream(ByteStreams.nullOutputStream());
			try {
				BloomFilter.create((t, s) -> {}, config.database.expectedTraffic, 0.1).writeTo(counter);
			} catch (IOException e2) {
				throw new AssertionError(e2);
			}
			log.debug("Expected bloom filter size: {} bytes", counter.getCount());
			if (counter.getCount() > 2048) {
				log.warn("The current value of expectedTraffic ({}) will result in a large Bloom filter ({}K)", config.database.expectedTraffic, counter.getCount()/1024);
			}
		}
		
		if (config.programs.runWineserver) {
			log.debug("Starting the wineserver");
			try {
				var ws = Commands.wineserver()
					.redirectError(Redirect.DISCARD)
					.redirectOutput(Redirect.DISCARD)
					.start();
				ws.getOutputStream().close();
				Runtime.getRuntime().addShutdownHook(new Thread(ws::destroy, "Wine shutdown thread"));
			} catch (IOException e) {
				log.warn("Could not start the wineserver", e);
			}
		}
		
		if (config.formats.allowEncumberedFormats) {
			// separate class so we don't load ThreadPools too early
			AACSupport.test();
		}
		
		String fname = "?";
		try {
			String formatsStr;
			if (config.formats.definitions == null) {
				formatsStr = Resources.toString(ClassLoader.getSystemResource("formats.jkson"), Charsets.UTF_8);
				fname = "<built-in>/formats.jkson";
			} else {
				var f = new File(config.formats.definitions);
				if (!f.exists()) {
					log.info("Copying built-in format definitions to {}", config.formats.definitions);
					Files.createParentDirs(f);
					Resources.asByteSource(ClassLoader.getSystemResource("formats.jkson")).copyTo(Files.asByteSink(f));
				}
				formatsStr = Files.asCharSource(f, Charsets.UTF_8).read();
				fname = config.formats.definitions;
			}
			JsonObject obj = jkson.load(fname, formatsStr);
			JsonObject addn = null;
			if (config.formats.additionalDefinitions != null) {
				addn = jkson.load(config.formats.additionalDefinitions, Files.asCharSource(new File(config.formats.additionalDefinitions), Charsets.UTF_8).read());
			}
			TranscodeFormat.load(obj, addn);
			log.info("Loaded {} transcode formats ({} available)", TranscodeFormat.formats.size(), TranscodeFormat.formats.stream()
					.filter(TranscodeFormat::available)
					.count());
		} catch (FileNotFoundException e) {
			log.error("{} does not exist. Cannot start.", fname);
			System.exit(1);
			return;
		} catch (IOException e) {
			log.error("Failed to load {}", fname, e);
			System.exit(1);
			return;
		} catch (SyntaxError e) {
			log.error("Failed to load {}: syntax error {}\n{}", fname, e.getLineMessage().replaceFirst("^Errored ", ""), e.getMessage());
			System.exit(1);
			return;
		}
		try {
			publicUri = new URI(config.http.publicUrl);
		} catch (URISyntaxException e1) {
			log.error("{} does not appear to be a valid URI", config.http.publicUrl);
			System.exit(1);
			return;
		}
		URLs.init();
		
		byte[] sessionSecretBytes = config.security.sessionSecret.getBytes(Charsets.UTF_8);
		sessionSecret = new SecretKeySpec(sessionSecretBytes, "RAW");

		String dbId = "(?)";
		try {
			var pesc = UrlEscapers.urlPathSegmentEscaper();
			var esc = UrlEscapers.urlFormParameterEscaper();
			if (config.database.driver == DatabaseDriver.h2) {
				dbId = "(h2)"+config.database.h2.file+".db.mv";
				sql = JdbcConnectionPool.create("jdbc:h2:"+pesc.escape(config.database.h2.file).replace("%2F", "/"), "", "");
			} else if (config.database.driver == DatabaseDriver.mariadb) {
				var c = config.database.mariadb;
				sql = new MariaDbPoolDataSource("jdbc:mariadb://"+pesc.escape(c.host)+":"+c.port+"/"+pesc.escape(c.db)+"?user="+esc.escape(c.user)+"&password="+esc.escape(c.pass)+"&autoReconnect=true");
				c.pass = null;
				dbId = "(mariadb)"+c.host+":"+c.port+"/"+c.db;
			} else {
				log.error("Unknown database driver");
				System.exit(1);
				return;
			}
			try (Connection c = sql.getConnection()) {
				try (Statement s = c.createStatement()) {
					int dataVersion;
					try {
						try (ResultSet rs = s.executeQuery("SELECT `value` FROM `meta` WHERE `name` = 'data_version';")) {
							if (rs.first()) {
								dataVersion = Integer.parseInt(rs.getString("value"));
							} else {
								throw new SQLException("data_version not present");
							}
						}
					} catch (SQLException e) {
						if (e.getMessage() != null && (e.getMessage().contains("not found") || e.getMessage().contains("doesn't exist"))) {
							log.info("Initializing database...");
							exec(s, ClassLoader.getSystemResource("sql/init.sql"));
							dataVersion = 0;
						} else {
							throw e;
						}
					}
					if (dataVersion > 0 && ClassLoader.getSystemResource("sql/upgrade_to_"+dataVersion+".sql") == null) {
						log.error("Database version {} is not recognized by this version of Partyflow", dataVersion);
						System.exit(1);
						return;
					}
					while (true) {
						URL upgrade = ClassLoader.getSystemResource("sql/upgrade_to_"+(dataVersion+1)+".sql");
						if (upgrade != null) {
							log.info("Upgrading database from v{} to v{}...", dataVersion, dataVersion+1);
							exec(s, upgrade);
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
				log.error("Failed to open the database at {}: The file is in use.\n"
						+ "Close any other instances of Partyflow or database editors and try again", dbId);
			} else {
				log.error("Failed to open database at {}", dbId, e);
			}
			System.exit(1);
			return;
		}
		log.info("Opened database at {}", dbId);
		QMeta.populate();

		BlobStore storage;
		String storageContainer;
		if (config.storage.driver == StorageDriver.fs) {
			File f = new File(config.storage.fs.dir).getAbsoluteFile();
			f.mkdirs();
			Properties bsProps = new Properties();
			bsProps.setProperty(FilesystemConstants.PROPERTY_BASEDIR, f.getParent());
			storage = ContextBuilder.newBuilder("filesystem")
					.overrides(bsProps)
					.build(BlobStoreContext.class)
					.getBlobStore();
			storageContainer = f.getName();
		} else if (config.storage.driver == StorageDriver.s3) {
			var c = config.storage.s3;
			if (c.endpoint.contains("s3.wasabisys.com") && config.storage.pruneTime.compareTo(Duration.ofDays(90)) < 0) {
				log.warn("Using Wasabi as a storage backend with a prune time shorter than 90 days (Wasabi's minimum retention time). This will cost you money!");
			}
			storage = ContextBuilder.newBuilder("s3")
					.credentials(c.accessKeyId, c.secretAccessKey)
					.modules(ImmutableList.of(new SLF4JLoggingModule()))
					.endpoint(c.endpoint)
					.build(BlobStoreContext.class)
					.getBlobStore();
			storageContainer = c.bucket;
		} else {
			log.error("Unknown storage driver");
			System.exit(1);
			return;
		}
		Storage.init(storage, storageContainer);
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

		String poweredBy = "Jetty/"+Splitter.on('.').split(Jetty.VERSION).iterator().next()+" "
				+ "Java/"+majorJavaVer;

		String displayBind;
		if ("0.0.0.0".equals(config.http.bind)) {
			displayBind = "*";
		} else {
			displayBind = config.http.bind;
		}
		var server = new Server();
		var cfg = new HttpConfiguration();
		// we handle this ourselves
		cfg.setSendServerVersion(false);
		cfg.setSendXPoweredBy(false);
		if (config.http.trustProxy) cfg.addCustomizer(new ForwardedRequestCustomizer());
		var factory = new HttpConnectionFactory(cfg);
		var conn = new ServerConnector(server, factory);
		conn.setHost(config.http.bind);
		conn.setPort(config.http.port);
		server.setConnectors(new Connector[] {conn});
		HandlerCollection hc = new HandlerCollection(
				setHeader("Clacks-Overhead", "GNU Natalie Nguyen, Shiina Mota, Near"),
				setHeader("Server", "Partyflow/"+Version.FULL),
				setHeader("Powered-By", poweredBy),
				
				new SetupHandler().asJettyHandler(),
				
				handler("", new IndexHandler()),
				handler("assets/colors.css", new ColorsHandler()),
				handler("assets/{}", new MustacheHandler("assets/{}")),
				handler("create-release", new CreateReleaseHandler()),
				handler("login", new LoginHandler()),
				handler("logout", new LogoutHandler()),
				handler("releases", new ReleasesHandler()),
				handler("admin", new AdminHandler()),

				handler("release/{}.rss", new ViewReleaseHandler("release-playlist.hbs.xml")),
				handler("release/{}", new ViewReleaseHandler("release.hbs.html")),
				handler("release/{}/add-track", new AddTrackHandler()),
				handler("release/{}/delete", new DeleteReleaseHandler()),
				handler("release/{}/edit", new EditReleaseHandler()),
				handler("release/{}/publish", new PublishReleaseHandler()),
				handler("release/{}/unpublish", new UnpublishReleaseHandler()),
				
				handler("track/", new TrackHandler()),
				handler("transcode/release-zip/{}", new TranscodeReleaseZipHandler()),
				handler("transcode/release/{}", new TranscodeReleaseHandler()),
				handler("transcode/track/{}", new TranscodeTrackHandler()),
				handler("download/", new DownloadHandler()),
				handler("static/", new StaticHandler()),
				handler("files/", new FilesHandler()),
				
				handler("api/v1/login", new LoginApi()),
				handler("api/v1/whoami", new WhoAmIApi()),
				handler("api/v1/release/{}", new ViewReleaseApi()),
				handler("api/v1/releases", new ReleasesApi())
			);
		server.setHandler(hc);
		server.setErrorHandler(new PartyflowErrorHandler());
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
			System.exit(1);
			return;
		}
		log.info("Listening on http://{}:{}", displayBind, config.http.port);
		log.info("Ready after {}", sw);

		try (var c = sql.getConnection()) {
			try (var s = c.createStatement()) {
				try (ResultSet rs = s.executeQuery("SELECT 1 FROM users WHERE role = "+UserRole.ADMIN.id()+" LIMIT 1;")) {
					if (!rs.first()) {
						setupToken = randomString(Services.secureRandom, 32);
						log.info("There are no admin users. Entering setup mode.\n"
								+ "The secret token is {}", setupToken);
					}
				} catch (SQLException e) {
					log.warn("Failed to check for the existence of admin users", e);
				}
				
				try (var rs = s.executeQuery("SELECT release_id, title FROM releases WHERE concat_master IS NULL;")) {
					while (rs.next()) {
						log.info("Processing for release {} was interrupted, restarting", rs.getString("title"));
						AddTrackHandler.regenerateAlbumFile(rs.getLong("release_id"));
					}
				} catch (SQLException e) {
					log.warn("Failed to check for unprocessed releases", e);
				}
			}
		} catch (SQLException e) {
			log.warn("Failed to perform startup checks", e);
		}

		Services.cron.scheduleWithFixedDelay(SessionHelper::cleanup, 0, 1, TimeUnit.HOURS);
		Services.cron.scheduleWithFixedDelay(CSRF::cleanup, 15, 15, TimeUnit.MINUTES);
		Services.cron.scheduleWithFixedDelay(Transcoder::cleanup, 0, 1, config.storage.pruneTime.toHours() <= 0 ? TimeUnit.MINUTES : TimeUnit.HOURS);
		
		if (Boolean.getBoolean("partyflow.sqlShell")) {
			try (Connection c = sql.getConnection()) {
				new Shell().runTool(c);
			} catch (SQLException e) {
				e.printStackTrace();
			}
		}
	}
	
	private static void exec(Statement s, URL res) throws SQLException, IOException {
		for (var q : Resources.toString(res, Charsets.UTF_8)
				.replace("{{clob}}", config.database.driver.clob())
				.replace("{{u8}}", config.database.driver.u8())
				.replace("{{u32}}", config.database.driver.u32())
				.split("\n--\n")) {
			s.execute(q);
		}
	}

	@SuppressWarnings("unchecked")
	private static Map.Entry<String, Object> unwrap(Map.Entry<?, ?> o) {
		return (Map.Entry<String, Object>)unwrap((Object)o);
	}
	
	private static Object unwrap(Object o) {
		if (o instanceof Map.Entry<?, ?> en) {
			return Map.entry(String.valueOf(en.getKey()), unwrap(en.getValue()));
		} else if (o instanceof JsonObject obj) {
			return obj.entrySet().stream()
				.map(Partyflow::unwrap)
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
		} else if (o instanceof JsonPrimitive jp) {
			return jp.getValue();
		}
		return o;
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

	private static final String RANDOM_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-";

	public static String randomString(RandomGenerator rand, int len) {
		StringBuilder sb = new StringBuilder(len);
		for (int i = 0; i < len; i++) {
			sb.append(RANDOM_CHARS.charAt(rand.nextInt(RANDOM_CHARS.length())));
		}
		return sb.toString();
	}

	private static final Pattern ILLEGAL = Pattern.compile("[?/#&;\\\\=\\^\\[\\]%]+");
	private static final Pattern SPACE = Pattern.compile("[\\p{Space}]+");

	public static @Detainted String sanitizeSlug(@Tainted String name) {
		if (SpecialTrack.BY_SLUG.containsKey(name)) return "_"+name;
		String s = Normalizer.normalize(name, Form.NFC);
		s = SPACE.matcher(s).replaceAll("-");
		s = ILLEGAL.matcher(s).replaceAll("_");
		s = s.replace("(", "");
		s = s.replace(")", "");
		s = s.replace('"', '\'');
		return s.strip().toLowerCase();
	}

}
