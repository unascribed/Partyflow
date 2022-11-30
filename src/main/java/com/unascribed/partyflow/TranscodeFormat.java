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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.JexlInfo;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.internal.Engine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Dankson.JsonJexlExpression;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;

import blue.endless.jankson.JsonArray;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;

public record TranscodeFormat(
		String name, Usage usage, String displayName, String description, String icon, int ytdlPriority,
		String fileExtension, String mimeType, ImmutableList<String> args,
		String altcmd, ImmutableList<String> altcmdargs,
		BooleanSupplier availableWhen, Predicate<UserData> suggestWhen, boolean direct, boolean cache,
		ToDoubleFunction<TrackData> sizeEstimator,
		ImmutableMap<String, Function<ReplayGainData, String>> replaygain,
		ImmutableList<Shortcut> shortcuts
	) {
	
	private static final Logger log = LoggerFactory.getLogger(TranscodeFormat.class);
	
	public boolean available() { return availableWhen().getAsBoolean(); }
	public boolean suggested(String userAgent) { return suggestWhen.test(new UserData(userAgent)); }
	public double estimateSize(long duration, long master) {
		return sizeEstimator().applyAsDouble(new TrackData(duration, duration/48000D, master));
	}

	@Override
	public String toString() {
		return name();
	}
	
	public record TrackData(long durationSamples, double durationSecs, long master) {}
	public record ReplayGainData(double albumLoudness, double trackLoudness, double albumPeak, double trackPeak) {}
	public record UserData(String userAgent) {}

	public enum Usage {
		DOWNLOAD,
		STREAM,
		STREAM_LOW,
		;
		public boolean canDownload() {
			return this == DOWNLOAD;
		}
		public boolean canStream() {
			// TODO check if low-quality is enabled
			return this == STREAM;
		}
	}

	public record Shortcut(TranscodeFormat source, ImmutableList<String> args) {}
	
	public static ImmutableList<TranscodeFormat> load(JsonObject obj) {
		var engine = new Engine(new JexlBuilder()
				.strict(true)
				.namespaces(Map.of(
							"math", Math.class,
							"string", String.class,
							"log", log,
							"int", Integer.class
						)));
		Table<String, String, JexlExpression> defs = HashBasedTable.create();
		Table<String, String, Map<String, JexlExpression>> mapDefs = HashBasedTable.create();
		for (var en : obj.getObject("_defs").entrySet()) {
			for (var en2 : ((JsonObject)en.getValue()).entrySet()) {
				if (en2.getValue() instanceof JsonObject jo) {
					Map<String, JexlExpression> res = new LinkedHashMap<>();
					for (var en3 : jo.entrySet()) {
						res.put(en3.getKey(), createExpr(engine, "_defs."+en.getKey()+"."+en2.getKey()+"."+en3.getKey(), en3.getValue()));
					}
					mapDefs.put(en.getKey(), en2.getKey(), res);
				} else if (en2.getValue() instanceof JsonPrimitive jp) {
					defs.put(en.getKey(), en2.getKey(), createExpr(engine,  "_defs."+en.getKey()+"."+en2.getKey(), jp));
				}
			}
		}
		var availableWhenCtx = new MapContext(new HashMap<>(defs.row("availableWhen")));
		availableWhenCtx.set("config", Partyflow.config);
		ImmutableList.Builder<TranscodeFormat> out = ImmutableList.builder();
		for (Usage usage : Usage.values()) {
			for (var en : obj.getObject(usage.name().toLowerCase(Locale.ROOT)).entrySet()) {
				JsonObject jo = (JsonObject)en.getValue();
				String name = en.getKey();
				String displayName = jo.get(String.class, "name");
				String description = jo.get(String.class, "description");
				String icon = jo.get(String.class, "icon");
				int ytdlPriority = jo.getInt("ytdlPriority", 0);
				String ext = jo.get(String.class, "ext");
				String type = jo.get(String.class, "type");
				boolean direct = jo.getBoolean("direct", false);
				boolean cache = jo.getBoolean("cache", true);
				ImmutableList<String> args = jo.get(JsonArray.class, "args").stream()
						.map(ele -> ((JsonPrimitive)ele).asString())
						.collect(ImmutableList.toImmutableList());
				
				var availableWhenExpr = createExpr(engine, name+".availableWhen", jo.get("availableWhen"));
				BooleanSupplier availableWhen = () -> evaluate(name+".availableWhen", availableWhenExpr, availableWhenCtx, Boolean.class);
				
				var sizeEstimateExpr = createExpr(engine, name+".sizeEstimate", jo.get("sizeEstimate"));
				ToDoubleFunction<TrackData> sizeEstimate = (data) -> {
					var ctx = new MapContext(new HashMap<>(defs.row("sizeEstimate")));
					ctx.set("durationSecs", data.durationSecs());
					ctx.set("durationSamples", data.durationSamples());
					ctx.set("master", data.master());
					return evaluate(name+".sizeEstimate", sizeEstimateExpr, ctx, Number.class).doubleValue();
				};
				
				var suggestWhenExpr = createExpr(engine, name+".suggestWhen", jo.containsKey("suggestWhen") ? jo.get("suggestWhen") : JsonPrimitive.of("false"));
				Predicate<UserData> suggestWhen = (data) -> {
					var ctx = new MapContext(new HashMap<>(defs.row("suggestWhen")));
					ctx.set("userAgent", data.userAgent());
					return evaluate(name+".suggestWhen", suggestWhenExpr, ctx, Boolean.class);
				};
				
				Map<String, JexlExpression> replaygainWork;
				JsonElement replaygainEle = jo.get("replaygain");
				if (replaygainEle instanceof JsonPrimitive rp) {
					replaygainWork = mapDefs.get("replaygain", rp.asString());
				} else if (replaygainEle instanceof JsonObject ro) {
					replaygainWork = new LinkedHashMap<>();
					for (var en2 : ro.entrySet()) {
						replaygainWork.put(en2.getKey(), createExpr(engine, name+".replaygain."+en2.getKey(), en2.getValue()));
					}
				} else {
					replaygainWork = Map.of();
				}
				ImmutableMap<String, Function<ReplayGainData, String>> replaygain = replaygainWork.entrySet().stream()
						.collect(ImmutableMap.toImmutableMap(Map.Entry::getKey, entry -> {
							var k = entry.getKey();
							var expr = entry.getValue();
							return (data) -> {
								var ctx = new MapContext(new HashMap<>(defs.row("replaygain")));
								ctx.set("albumLoudness", data.albumLoudness());
								ctx.set("trackLoudness", data.trackLoudness());
								ctx.set("albumPeak", data.albumPeak());
								ctx.set("trackPeak", data.trackPeak());
								return String.valueOf(evaluate(name+".replaygain."+k, expr, ctx, Object.class));
							};
						}));
				
				String altcmd = jo.get(String.class, "altcmd");
				ImmutableList<String> altcmdargs = null;
				if (altcmd != null) {
					altcmdargs = jo.get(JsonArray.class, "altcmdargs").stream()
							.map(ele -> ((JsonPrimitive)ele).asString())
							.collect(ImmutableList.toImmutableList());;
				}
				
				out.add(new TranscodeFormat(name, usage, displayName, description, icon, ytdlPriority, ext, type, args, altcmd, altcmdargs, availableWhen, suggestWhen, direct, cache, sizeEstimate, replaygain, ImmutableList.of()));
			}
		}
		return out.build();
	}

	private static JexlExpression createExpr(Engine engine, String info, JsonElement expr) {
		try {
			JexlInfo jinfo = null;
			String str;
			if (expr instanceof JsonJexlExpression jje) {
				str = jje.asString();
				jinfo = jje.getInfo();
			} else if (expr instanceof JsonPrimitive jp) {
				str = jp.asString();
			} else {
				throw new IllegalArgumentException(expr.getClass().getName());
			}
			return engine.createExpression(jinfo, str);
		} catch (Throwable t) {
			throw new RuntimeException("Exception while creating expression for "+info, t);
		}
	}

	private static <T> T evaluate(String info, JexlExpression expr, JexlContext ctx, Class<T> clazz) {
		try {
			Object res = expr;
			while (res instanceof JexlExpression e) {
				res = e.evaluate(ctx);
			}
			return clazz.cast(res);
		} catch (Throwable t) {
			throw new RuntimeException("Exception while processing expression for "+info, t);
		}
	}
	
}
