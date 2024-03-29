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

package com.unascribed.partyflow.handler.util;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.MultiPartFormInputStream;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import com.unascribed.partyflow.util.MoreByteStreams;
import com.unascribed.partyflow.util.Unchecked;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Maps;
import com.google.common.net.UrlEscapers;

public class SimpleHandler {

	public static final int HTTP_100_CONTINUE = 100;
    public static final int HTTP_101_SWITCHING_PROTOCOLS = 101;
    public static final int HTTP_102_PROCESSING = 102;

    public static final int HTTP_200_OK = 200;
    public static final int HTTP_201_CREATED = 201;
    public static final int HTTP_202_ACCEPTED = 202;
    public static final int HTTP_203_NON_AUTHORITATIVE_INFORMATION = 203;
    public static final int HTTP_204_NO_CONTENT = 204;
    public static final int HTTP_205_RESET_CONTENT = 205;
    public static final int HTTP_206_PARTIAL_CONTENT = 206;
    public static final int HTTP_207_MULTI_STATUS = 207;

    public static final int HTTP_300_MULTIPLE_CHOICES = 300;
    public static final int HTTP_301_MOVED_PERMANENTLY = 301;
    public static final int HTTP_302_MOVED_TEMPORARILY = 302;
    public static final int HTTP_302_FOUND = 302;
    public static final int HTTP_303_SEE_OTHER = 303;
    public static final int HTTP_304_NOT_MODIFIED = 304;
    public static final int HTTP_305_USE_PROXY = 305;
    public static final int HTTP_307_TEMPORARY_REDIRECT = 307;
    public static final int HTTP_308_PERMANENT_REDIRECT = 308;
    
    public static final int HTTP_400_BAD_REQUEST = 400;
    public static final int HTTP_401_UNAUTHORIZED = 401;
    public static final int HTTP_402_PAYMENT_REQUIRED = 402;
    public static final int HTTP_403_FORBIDDEN = 403;
    public static final int HTTP_404_NOT_FOUND = 404;
    public static final int HTTP_405_METHOD_NOT_ALLOWED = 405;
    public static final int HTTP_406_NOT_ACCEPTABLE = 406;
    public static final int HTTP_407_PROXY_AUTHENTICATION_REQUIRED = 407;
    public static final int HTTP_408_REQUEST_TIMEOUT = 408;
    public static final int HTTP_409_CONFLICT = 409;
    public static final int HTTP_410_GONE = 410;
    public static final int HTTP_411_LENGTH_REQUIRED = 411;
    public static final int HTTP_412_PRECONDITION_FAILED = 412;
    public static final int HTTP_413_PAYLOAD_TOO_LARGE = 413;
    public static final int HTTP_414_URI_TOO_LONG = 414;
    public static final int HTTP_415_UNSUPPORTED_MEDIA_TYPE = 415;
    public static final int HTTP_416_RANGE_NOT_SATISFIABLE = 416;
    public static final int HTTP_417_EXPECTATION_FAILED = 417;
    public static final int HTTP_418_IM_A_TEAPOT = 418;
    public static final int HTTP_420_ENHANCE_YOUR_CALM = 420;
    public static final int HTTP_421_MISDIRECTED_REQUEST = 421;
    public static final int HTTP_422_UNPROCESSABLE_ENTITY = 422;
    public static final int HTTP_423_LOCKED = 423;
    public static final int HTTP_424_FAILED_DEPENDENCY = 424;
    public static final int HTTP_426_UPGRADE_REQUIRED = 426;
    public static final int HTTP_428_PRECONDITION_REQUIRED = 428;
    public static final int HTTP_429_TOO_MANY_REQUESTS = 429;
    public static final int HTTP_431_REQUEST_HEADER_FIELDS_TOO_LARGE = 431;
    public static final int HTTP_451_UNAVAILABLE_FOR_LEGAL_REASONS = 451;

    public static final int HTTP_500_INTERNAL_SERVER_ERROR = 500;
    public static final int HTTP_501_NOT_IMPLEMENTED = 501;
    public static final int HTTP_502_BAD_GATEWAY = 502;
    public static final int HTTP_503_SERVICE_UNAVAILABLE = 503;
    public static final int HTTP_504_GATEWAY_TIMEOUT = 504;
    public static final int HTTP_505_HTTP_VERSION_NOT_SUPPORTED = 505;
    public static final int HTTP_507_INSUFFICIENT_STORAGE = 507;
    public static final int HTTP_508_LOOP_DETECTED = 508;
    public static final int HTTP_510_NOT_EXTENDED = 510;
    public static final int HTTP_511_NETWORK_AUTHENTICATION_REQUIRED = 511;

	private static final Joiner COMMA_JOINER = Joiner.on(", ");
	protected static final File TEMP_DIR = Unchecked.call(() -> Files.createTempDirectory("partyflow-upload").toFile());
	
	private static final int K = 1024;
	private static final int M = 1024*K;
	private static final long G = 1024*M;
	private static final MultipartConfigElement MP_CFG = new MultipartConfigElement(TEMP_DIR.getAbsolutePath(),
			512L*M, 2*G, 2*M);

	private static final Splitter URLENCODED_SPLITTER = Splitter.on('&');

	public interface Any {
		/**
		 * @return {@code true} to suppress default behavior
		 */
		boolean any(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException;
	}

	public interface Get {
		void get(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException;
	}
	public interface Post {
		void post(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException;
	}
	public interface Head {
		void head(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException;
	}
	public interface Put {
		void put(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException;
	}
	public interface Options {
		/**
		 * @return {@code true} to suppress default behavior
		 */
		boolean options(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException;
	}
	public interface Delete {
		void delete(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException;
	}

	public interface GetOrHead extends Get, Head {
		@Override
		default void get(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException {
			getOrHead(path, req, res, false);
		}
		@Override
		default void head(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException {
			getOrHead(path, req, res, true);
		}
		void getOrHead(String path, HttpServletRequest req, HttpServletResponse res, boolean head) throws IOException, ServletException, SQLException;
	}
	public interface MultipartPost extends Post {
		@Override
		default void post(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException {
			if (req.getContentType().startsWith("multipart/form-data;")) {
				MultiPartFormInputStream mpfis = null;
				try (var in = req.getInputStream()) {
					mpfis = new MultiPartFormInputStream(in, req.getContentType(), MP_CFG, TEMP_DIR);
					multipartPost(path, req, res, new MultipartData(mpfis));
				} catch (IllegalStateException e) {
					if (e.getMessage() != null && e.getMessage().endsWith("exceeds max filesize")) {
						req.getInputStream().close();
						res.sendError(HTTP_413_PAYLOAD_TOO_LARGE);
					} else {
						throw e;
					}
				} finally {
					if (mpfis != null && mpfis.getParts() != null) {
						mpfis.deleteParts();
					}
				}
			} else {
				res.sendError(HTTP_415_UNSUPPORTED_MEDIA_TYPE);
			}
		}
		void multipartPost(String path, HttpServletRequest req, HttpServletResponse res, MultipartData data) throws IOException, ServletException, SQLException;
	}
	public interface UrlEncodedPost extends Post {
		@Override
		default void post(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException {
			if (req.getContentType().equals("application/x-www-form-urlencoded")) {
				byte[] bys;
				try {
					bys = MoreByteStreams.consume(req.getInputStream(), 8192);
				} finally {
					req.getInputStream().close();
				}
				if (bys == null) {
					res.sendError(HTTP_413_PAYLOAD_TOO_LARGE);
					return;
				}
				String str = new String(bys, Charsets.UTF_8);
				urlEncodedPost(path, req, res, parseUrlEncoded(str));
			} else {
				res.sendError(HTTP_415_UNSUPPORTED_MEDIA_TYPE);
			}
		}
		void urlEncodedPost(String path, HttpServletRequest req, HttpServletResponse res, Map<String, String> params) throws IOException, ServletException, SQLException;
	}
	public interface UrlEncodedOrMultipartPost extends UrlEncodedPost, MultipartPost {
		@Override
		default void post(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException {
			if (req.getContentType().equals("application/x-www-form-urlencoded")) {
				UrlEncodedPost.super.post(path, req, res);
			} else if (req.getContentType().startsWith("multipart/form-data;")) {
				MultipartPost.super.post(path, req, res);
			} else {
				res.sendError(HTTP_415_UNSUPPORTED_MEDIA_TYPE);
			}
		}
	}

	// Handler has a bunch of gross and spammy lifecycle methods that we'd rather not expose to subclasses

	private final Handler jettyHandler = new AbstractHandler() {
		@Override
		public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response)
				throws IOException, ServletException {
			try {
				SimpleHandler.this.handle(target, request, response);
			} catch (SQLException e) {
				throw new ServletException(e);
			}
		}
	};

	public void handle(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException {
		if (this instanceof Any) {
			if (((Any)this).any(path, req, res)) return;
		}
		switch (req.getMethod().toUpperCase(Locale.ROOT)) {
			case "GET":
				if (this instanceof Get) ((Get)this).get(path, req, res);
				else res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
				break;
			case "POST":
				if (this instanceof Post) ((Post)this).post(path, req, res);
				else res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
				break;
			case "HEAD":
				if (this instanceof Head) ((Head)this).head(path, req, res);
				else res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
				break;
			case "PUT":
				if (this instanceof Put) ((Put)this).put(path, req, res);
				else res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
				break;
			case "DELETE":
				if (this instanceof Delete) ((Delete)this).delete(path, req, res);
				else res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
				break;
			case "OPTIONS":
				if (this instanceof Options) {
					if (((Options)this).options(path, req, res)) break;
				}
				List<String> allow = new ArrayList<>(6);
				allow.add("OPTIONS");
				if (this instanceof Get) allow.add("GET");
				if (this instanceof Post) allow.add("POST");
				if (this instanceof Head) allow.add("HEAD");
				if (this instanceof Put) allow.add("PUT");
				if (this instanceof Delete) allow.add("DELETE");
				res.setHeader("Allow", COMMA_JOINER.join(allow));
				res.setStatus(HTTP_204_NO_CONTENT);
				res.getOutputStream().close();
				break;
			default:
				res.sendError(HTTP_405_METHOD_NOT_ALLOWED);
				break;
		}
	}

	public Handler asJettyHandler() {
		return jettyHandler;
	}

	protected static Map<String, String> parseQuery(HttpServletRequest req) {
		String query = req.getQueryString();
		if (query == null) return Collections.emptyMap();
		return parseUrlEncoded(query);
	}

	protected static Map<String, String> parseUrlEncoded(String str) {
		try {
			Iterable<String> params = URLENCODED_SPLITTER.split(str);
			Map<String, String> decodedParams = Maps.newHashMap();
			for (String s : params) {
				int eq = s.indexOf('=');
				String k, v;
				if (eq != -1) {
					k = s.substring(0, eq);
					v = s.substring(eq+1);
				} else {
					k = s;
					v = "";
				}
				decodedParams.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
			}
		return decodedParams;
		} catch (UnsupportedEncodingException e) {
			throw new AssertionError(e);
		}
	}

	protected static String escPathSeg(String str) {
		return UrlEscapers.urlPathSegmentEscaper().escape(str);
	}
	
	protected static String keepQuery(HttpServletRequest req) {
		String q = req.getQueryString();
		if (q == null) return "";
		return "?"+q;
	}

}
