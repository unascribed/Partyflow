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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nullable;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.server.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.handler.frontend.UserVisibleException;
import com.unascribed.partyflow.handler.util.PartyflowErrorHandler.JsonError;
import com.unascribed.partyflow.handler.util.SimpleHandler.Any;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.primitives.Doubles;
import com.google.common.primitives.Longs;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonArray;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonGrammar;
import blue.endless.jankson.JsonNull;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;
import blue.endless.jankson.api.SyntaxError;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public abstract class ApiHandler extends SimpleHandler implements Any {
	
	private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);
	
	private static final Pattern JSON_PATTERN = Pattern.compile("application/json;\\s+charset=utf-8", Pattern.CASE_INSENSITIVE);
	private static final Joiner COMMA_JOINER = Joiner.on(", ");
	
	@Documented @Target({RECORD_COMPONENT, PARAMETER}) @Retention(RUNTIME)
	protected @interface Header {
		String value();
	}

	@Documented @Target(PARAMETER) @Retention(RUNTIME)
	protected @interface RequestPath {}

	@Documented @Target(METHOD) @Retention(RUNTIME)
	protected @interface GET {}
	@Documented @Target(METHOD) @Retention(RUNTIME)
	protected @interface POST {}
	@Documented @Target(METHOD) @Retention(RUNTIME)
	protected @interface PUT {}
	@Documented @Target(METHOD) @Retention(RUNTIME)
	protected @interface DELETE {}
	@Documented @Target(METHOD) @Retention(RUNTIME)
	protected @interface PATCH {}
	
	private static final ThreadLocal<Jankson> jkson = ThreadLocal.withInitial(() -> Jankson.builder()
			.registerSerializer(Date.class, (d, m) -> JsonPrimitive.of(DateTimeFormatter.ISO_INSTANT.format(d.toInstant().truncatedTo(ChronoUnit.SECONDS))))
			.registerSerializer(OptionalInt.class, (o, m) -> o.isPresent() ? JsonPrimitive.of((long)o.getAsInt()) : JsonNull.INSTANCE)
			.registerSerializer(OptionalLong.class, (o, m) -> o.isPresent() ? JsonPrimitive.of(o.getAsLong()) : JsonNull.INSTANCE)
			.registerSerializer(OptionalDouble.class, (o, m) -> o.isPresent() ? JsonPrimitive.of(o.getAsDouble()) : JsonNull.INSTANCE)
			.registerSerializer(Optional.class, (o, m) -> o.isPresent() ? m.serialize(o.get()) : JsonNull.INSTANCE)
			.build());
	
	private interface ParamMapper {
		Object map(String path, HttpServletRequest req, JsonObject body) throws MissingParameterException, SQLException, IOException;
	}

	private record Invocation(String verb, ImmutableSet<String> paramNames, ImmutableList<ParamMapper> mappers, MethodHandle invoker, boolean isVoid, boolean wantsJson) {}
	
	private record NoMatchingInvocation(boolean error, int code, String message, List<? extends List<String>> options) {}
	private record UnexpectedParametersError(boolean error, int code, String message, List<String> got, List<String> expected) {}
	
	private record UnexpectedParametersWarning(String message, List<String> got, List<String> expected) {}
	
	private static class MissingParameterException extends Exception {
		private final String param;
		
		public MissingParameterException(String param) {
			super("Required parameter "+param+" is missing");
			this.param = param;
		}
		
		public String getParam() {
			return param;
		}
	}
	
	private final ImmutableSet<String> verbs;
	private final ImmutableList<Invocation> invocations;
	private final boolean canAcceptNonJson;
	
	public ApiHandler() {
		ImmutableSet.Builder<String> verbsTmp = ImmutableSet.builder();
		ImmutableList.Builder<Invocation> invocationsTmp = ImmutableList.builder();
		boolean canAcceptNonJsonTmp = false;
		boolean foundAnything = false;
		for (var m : getClass().getMethods()) {
			if (Modifier.isStatic(m.getModifiers()) && (m.getReturnType() == void.class || m.getReturnType().isRecord())) {
				String verb = null;
				if (m.isAnnotationPresent(GET.class)) verb = "GET";
				else if (m.isAnnotationPresent(POST.class)) verb = "POST";
				else if (m.isAnnotationPresent(PUT.class)) verb = "PUT";
				else if (m.isAnnotationPresent(DELETE.class)) verb = "DELETE";
				else if (m.isAnnotationPresent(PATCH.class)) verb = "PATCH";
				else continue;
				foundAnything = true;
				boolean wantsJson = true;
				ImmutableList.Builder<ParamMapper> mappers = ImmutableList.builder();
				ImmutableSet.Builder<String> paramNames = ImmutableSet.builder();
				for (var p : m.getParameters()) {
					if (p.isSynthetic() || p.isImplicit()) continue;
					var hdr = p.getAnnotation(Header.class);
					if (hdr != null) {
						if (!String.class.isAssignableFrom(p.getType()))
							throw new IllegalArgumentException("@Header parameters must be of type String, not "+p.getType().getSimpleName());
						mappers.add((path, req, body) -> req.getHeader(hdr.value()));
					} else if (p.isAnnotationPresent(RequestPath.class)) {
						if (!String.class.isAssignableFrom(p.getType()))
							throw new IllegalArgumentException("@RequestPath parameters must be of type String, not "+p.getType().getSimpleName());
						mappers.add((path, req, body) -> path);
					} else if (p.getType() == Session.class) {
						mappers.add((path, req, body) -> SessionHelper.getSession(req));
					} else if (p.getType() == InputStream.class) {
						mappers.add((path, req, body) -> req.getInputStream());
						wantsJson = false;
						canAcceptNonJsonTmp = true;
					} else {
						var nullable = p.isAnnotationPresent(Nullable.class);
						var type = p.getParameterizedType();
						var name = p.getName();
						var bool = p.getType() == boolean.class;
						paramNames.add(name);
						mappers.add((path, req, body) -> {
							var ele = body.get(name);
							if (ele == null || ele instanceof JsonNull) {
								if (bool) {
									ele = JsonPrimitive.FALSE;
								} else if (!nullable) {
									throw new MissingParameterException(name);
								}
							}
							return jkson.get().getMarshaller().marshall(type, ele);
						});
					}
				}
				try {
					verbsTmp.add(verb);
					invocationsTmp.add(new Invocation(verb, paramNames.build(), mappers.build(),
							MethodHandles.publicLookup().unreflect(m), m.getReturnType() == void.class, wantsJson));
				} catch (IllegalAccessException e) {
					throw new AssertionError(e);
				}
			}
		}
		if (!foundAnything) {
			throw new AbstractMethodError("ApiHandler implementations must specify at least one public static method annotated with an HTTP verb");
		}
		verbs = verbsTmp.build();
		invocations = invocationsTmp.build();
		canAcceptNonJson = canAcceptNonJsonTmp;
	}
	
	@Override
	public boolean any(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException {
		req.setAttribute("partyflow.isApi", true);
		String verb = req.getMethod().toUpperCase(Locale.ROOT);
		if ("OPTIONS".equals(verb)) {
			res.setHeader("Allow", "OPTIONS, "+COMMA_JOINER.join(verbs));
			res.setStatus(HTTP_204_NO_CONTENT);
			res.getOutputStream().close();
			return true;
		}
		if (!verbs.contains(verb)) throw new UserVisibleException(HTTP_405_METHOD_NOT_ALLOWED);
		boolean isJson = req.getContentType() != null && JSON_PATTERN.matcher(req.getContentType()).matches();
		JsonObject params;
		if ((!isJson && canAcceptNonJson) || "GET".equals(verb)) {
			params = new JsonObject();
			parseQuery(req).forEach((k, v) -> params.put(k, parseAdhoc(v)));
		} else if (!isJson) {
			res.setStatus(HTTP_415_UNSUPPORTED_MEDIA_TYPE);
			serve(req, res, new JsonError(true, 415, "Content-Type must be 'application/json; charset=utf-8'", null), List.of());
			return true;
		} else {
			try (var in = req.getInputStream()) {
				params = jkson.get().load(ByteStreams.limit(in, 16384));
			} catch (SyntaxError e) {
				throw new UserVisibleException(HTTP_400_BAD_REQUEST, "JSON syntax error: "+e.getCompleteMessage());
			}
		}
		try {
			outer: for (var inv : invocations) {
				if (!"GET".equals(verb) && inv.wantsJson != isJson) continue;
				if (!inv.verb.equals(verb)) continue;
				List<Record> warnings = new ArrayList<>();
				if (params == null) {
					if (!inv.paramNames.isEmpty()) {
						continue;
					}
				} else if (!inv.paramNames.containsAll(params.keySet())) {
					if (invocations.size() == 1) {
						warnings.add(new UnexpectedParametersWarning("The request has unexpected parameters", params.keySet().stream()
								.filter(s -> !inv.paramNames.contains(s))
								.toList(), inv.paramNames.asList()));
					}
				}
				List<String> missingParams = invocations.size() == 1 ? new ArrayList<>() : null;
				var li = new ArrayList<>(inv.mappers.size());
				for (var mapper : inv.mappers) {
					try {
						li.add(mapper.map(path, req, params));
					} catch (MissingParameterException e) {
						if (missingParams != null) {
							missingParams.add(e.getParam());
						} else {
							continue outer;
						}
					}
				}
				if (missingParams != null && !missingParams.isEmpty()) {
					res.setStatus(HTTP_400_BAD_REQUEST);
					serve(req, res, new UnexpectedParametersError(true, 400, "The request is missing required parameters", missingParams, inv.paramNames.asList()), List.of());
					return true;
				}
				if (inv.isVoid) {
					inv.invoker.invokeWithArguments(li);
					res.setStatus(HTTP_204_NO_CONTENT);
					res.getOutputStream().close();
				} else {
					Record r = (Record)inv.invoker.invokeWithArguments(li);
					res.setStatus(HTTP_200_OK);
					serve(req, res, r, warnings);
				}
				return true;
			}
			res.setStatus(HTTP_400_BAD_REQUEST);
			serve(req, res, new NoMatchingInvocation(true, 400, "The request does not match any valid signature", invocations.stream()
					.map(inv -> inv.paramNames.asList())
					.toList()), List.of());
		} catch (UserVisibleException e) {
			res.setStatus(e.getCode());
			serve(req, res, new JsonError(true, e.getCode(), e.getMessage(), null), List.of());
		} catch (ServletException | IOException | SQLException e) {
			throw e;
		} catch (Throwable e) {
			throw new ServletException(e);
		}
		return true;
	}

	private JsonElement parseAdhoc(String v) {
		switch (v) {
			case "true", "on", "": return JsonPrimitive.TRUE;
			case "false", "off": return JsonPrimitive.FALSE;
		}
		if (v.contains(".")) {
			var d = Doubles.tryParse(v);
			if (d != null) return JsonPrimitive.of(d);
		}
		var l = Longs.tryParse(v);
		if (l != null) return JsonPrimitive.of(l);
		return JsonPrimitive.of(v);
	}

	public static void serve(HttpServletRequest req, HttpServletResponse res, Record obj, List<? extends Record> warnings) throws ServletException, IOException {
		res.setContentType("application/json; charset=utf-8");
		var encodings = ((Request)req).getHttpFields().getQualityCSV(HttpHeader.ACCEPT_ENCODING);
		boolean curl = Strings.nullToEmpty(req.getHeader("User-Agent")).startsWith("curl/");
		var json = jsonify(obj, res);
		if (!warnings.isEmpty()) {
			var arr = new JsonArray();
			for (var w : warnings) arr.add(jsonify(w));
			json.put("_warnings", arr);
		}
		byte[] utf = json.toJson(curl ? JsonGrammar.STRICT : JsonGrammar.COMPACT).getBytes(Charsets.UTF_8);
		if (encodings.contains("gzip")) {
			var baos = new ByteArrayOutputStream();
			var gz = new GZIPOutputStream(baos) {{
				def.setLevel(2);
			}};
			gz.write(utf);
			gz.close();
			utf = baos.toByteArray();
			res.setHeader("Content-Encoding", "gzip");
		}
		res.setContentLength(utf.length);
		try (var out = res.getOutputStream()) {
			out.write(utf);
		}
	}

	private static JsonObject jsonify(Record obj, HttpServletResponse res) throws ServletException {
		JsonObject json = new JsonObject();
		for (var c : obj.getClass().getRecordComponents()) {
			try {
				var rawV = c.getAccessor().invoke(obj);
				var hdr = c.getAnnotation(Header.class);
				if (hdr != null) {
					if (res == null) {
						log.warn("Nested record cannot have an @Header component: {}.{} - ignoring", obj.getClass().getSimpleName(), c.getName());
					} else {
						res.setHeader(hdr.value(), String.valueOf(rawV));
					}
				} else {
					JsonElement ele = jsonify(rawV);
					if (ele != null) {
						json.put(c.getName(), ele);
					}
				}
			} catch (Exception e) {
				throw new ServletException(e);
			}
		}
		return json;
	}

	private static JsonElement jsonify(Object rawV) throws ServletException {
		if (rawV == null) {
			return null;
		} else if (rawV instanceof Record r) {
			return jsonify(r, null);
		} else if (rawV instanceof Iterable<?> coll) {
			var out = new JsonArray();
			for (var e : coll) {
				out.add(jsonify(e));
			}
			return out;
		} else if (rawV instanceof Map<?, ?> map) {
			var out = new JsonObject();
			for (var e : map.entrySet()) {
				out.put(String.valueOf(e.getKey()), jsonify(e.getValue()));
			}
			return out;
		} else {
			return jkson.get().getMarshaller().serialize(rawV);
		}
	}
	
}
