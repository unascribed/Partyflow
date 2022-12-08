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

import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.SessionHelper;
import com.unascribed.partyflow.SessionHelper.Session;
import com.unascribed.partyflow.handler.UserVisibleException;
import com.unascribed.partyflow.handler.util.PartyflowErrorHandler.JsonError;
import com.unascribed.partyflow.handler.util.SimpleHandler.Get;
import com.unascribed.partyflow.handler.util.SimpleHandler.Post;

import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonGrammar;
import blue.endless.jankson.JsonNull;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;
import blue.endless.jankson.api.SyntaxError;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public abstract class ApiHandler extends SimpleHandler implements Get, Post {
	
	private static final Logger log = LoggerFactory.getLogger(ApiHandler.class);
	
	private static final Pattern JSON_PATTERN = Pattern.compile("application/json;\\s+charset=utf-8", Pattern.CASE_INSENSITIVE);
	
	@Documented
	@Target({ElementType.RECORD_COMPONENT, ElementType.PARAMETER})
	@Retention(RetentionPolicy.RUNTIME)
	public @interface Header {
		String value();
	}
	
	@Documented
	@Target(ElementType.PARAMETER)
	@Retention(RetentionPolicy.RUNTIME)
	public @interface RequestPath {}
	
	private static final ThreadLocal<Jankson> jkson = ThreadLocal.withInitial(() -> Jankson.builder()
			.build());
	
	private interface ParamMapper {
		Object map(String path, HttpServletRequest req, JsonObject body) throws MissingParameterException, SQLException;
	}

	private record Invocation(ImmutableSet<String> paramNames, ImmutableList<ParamMapper> mappers, MethodHandle invoker, boolean isVoid) {}
	
	private record NoMatchingInvocation(boolean error, int code, String message, List<? extends List<String>> options) {}
	private record UnexpectedParameters(boolean error, int code, String message, List<String> got, List<String> expected) {}
	
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
	
	private final List<Invocation> invocations;
	private final boolean canAcceptGet;
	private final boolean canAcceptPost;
	
	public ApiHandler() {
		ImmutableList.Builder<Invocation> invocationsTmp = ImmutableList.builder();
		boolean foundAnything = false;
		for (var m : getClass().getMethods()) {
			if (m.getName().equals("invoke") && (m.getReturnType() == void.class || m.getReturnType().isRecord())) {
				foundAnything = true;
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
					invocationsTmp.add(new Invocation(paramNames.build(), mappers.build(), MethodHandles.publicLookup().unreflect(m), m.getReturnType() == void.class));
				} catch (IllegalAccessException e) {
					throw new AssertionError(e);
				}
			}
		}
		if (!foundAnything) {
			throw new AbstractMethodError("ApiHandler implementations must specify a static invoke method");
		}
		invocations = invocationsTmp.build();
		canAcceptGet = invocations.stream().anyMatch(i -> i.paramNames.isEmpty());
		canAcceptPost = invocations.stream().anyMatch(i -> !i.paramNames.isEmpty());
	}
	
	@Override
	public void get(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException {
		req.setAttribute("partyflow.isApi", true);
		if (!canAcceptGet) throw new UserVisibleException(HTTP_405_METHOD_NOT_ALLOWED);
		invoke(path, req, res, null);
	}
	
	@Override
	public void post(String path, HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException, SQLException {
		req.setAttribute("partyflow.isApi", true);
		if (!canAcceptPost) throw new UserVisibleException(HTTP_405_METHOD_NOT_ALLOWED);
		if (req.getContentType() == null || !JSON_PATTERN.matcher(req.getContentType()).matches()) {
			res.setStatus(HTTP_415_UNSUPPORTED_MEDIA_TYPE);
			serve(req, res, new JsonError(true, 415, "Content-Type must be 'application/json; charset=utf-8'", null));
			return;
		}
		try {
			invoke(path, req, res, jkson.get().load(ByteStreams.limit(req.getInputStream(), 16384)));
		} catch (SyntaxError e) {
			throw new UserVisibleException(HTTP_400_BAD_REQUEST, "JSON syntax error: "+e.getCompleteMessage());
		}
	}

	protected void invoke(String path, HttpServletRequest req, HttpServletResponse res, JsonObject body) throws IOException, ServletException, SQLException {
		try {
			outer: for (var inv : invocations) {
				if (body == null) {
					if (!inv.paramNames.isEmpty()) {
						continue;
					}
				} else if (!inv.paramNames.containsAll(body.keySet())) {
					if (invocations.size() == 1) {
						res.setStatus(HTTP_400_BAD_REQUEST);
						serve(req, res, new UnexpectedParameters(true, 400, "The request body has unexpected parameters", body.keySet().stream()
								.filter(s -> !inv.paramNames.contains(s))
								.toList(), inv.paramNames.asList()));
						return;
					}
					continue;
				}
				List<String> missingParams = invocations.size() == 1 ? new ArrayList<>() : null;
				var li = new ArrayList<>(inv.mappers.size());
				for (var mapper : inv.mappers) {
					try {
						li.add(mapper.map(path, req, body));
					} catch (MissingParameterException e) {
						if (invocations.size() == 1) {
							missingParams.add(e.getParam());
						} else {
							continue outer;
						}
					}
				}
				if (missingParams != null && !missingParams.isEmpty()) {
					res.setStatus(HTTP_400_BAD_REQUEST);
					serve(req, res, new UnexpectedParameters(true, 400, "The request body is missing required parameters", missingParams, inv.paramNames.asList()));
					return;
				}
				if (inv.isVoid) {
					inv.invoker.invokeWithArguments(li);
					res.setStatus(HTTP_204_NO_CONTENT);
					res.getOutputStream().close();
				} else {
					Record r = (Record)inv.invoker.invokeWithArguments(li);
					res.setStatus(HTTP_200_OK);
					serve(req, res, r);
				}
				return;
			}
			res.setStatus(HTTP_400_BAD_REQUEST);
			serve(req, res, new NoMatchingInvocation(true, 400, "The request body does not match any valid signature", invocations.stream()
					.map(inv -> inv.paramNames.asList())
					.toList()));
		} catch (UserVisibleException e) {
			res.setStatus(e.getCode());
			serve(req, res, new JsonError(true, e.getCode(), e.getMessage(), null));
		} catch (ServletException | IOException | SQLException e) {
			throw e;
		} catch (Throwable e) {
			throw new ServletException(e);
		}
	}

	public static void serve(HttpServletRequest req, HttpServletResponse res, Record obj) throws ServletException, IOException {
		res.setContentType("application/json; charset=utf-8");
		boolean curl = Strings.nullToEmpty(req.getHeader("User-Agent")).startsWith("curl/");
		byte[] utf = jsonify(obj, res).toJson(curl ? JsonGrammar.STRICT : JsonGrammar.COMPACT).getBytes(Charsets.UTF_8);
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
		} else {
			return jkson.get().getMarshaller().serialize(rawV);
		}
	}
	
}
