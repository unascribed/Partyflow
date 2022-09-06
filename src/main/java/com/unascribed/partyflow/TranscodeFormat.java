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
import java.util.function.ToDoubleFunction;

import org.apache.commons.jexl3.JexlBuilder;
import org.apache.commons.jexl3.JexlContext;
import org.apache.commons.jexl3.JexlExpression;
import org.apache.commons.jexl3.MapContext;
import org.apache.commons.jexl3.internal.Engine;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Table;

import blue.endless.jankson.JsonArray;
import blue.endless.jankson.JsonElement;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;

public record TranscodeFormat(
		String name, Usage usage, String displayName, int ytdlPriority, String fileExtension,
		String mimeType, ImmutableList<String> args, BooleanSupplier availableWhen,
		boolean direct, boolean cache, ToDoubleFunction<TrackData> sizeEstimate,
		ImmutableMap<String, Function<ReplayGainData, String>> replaygain,
		ImmutableList<Shortcut> shortcuts
	) {
	
	public boolean available() { return availableWhen().getAsBoolean(); }

	@Override
	public String toString() {
		return name();
	}
	
	public record TrackData(long durationSamples, double durationSecs, long master) {}
	public record ReplayGainData(double albumLoudness, double trackLoudness, double albumPeak, double trackPeak) {}

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
							"string", String.class
						)));
		Table<String, String, JexlExpression> defs = HashBasedTable.create();
		Table<String, String, Map<String, JexlExpression>> mapDefs = HashBasedTable.create();
		for (var en : obj.getObject("_defs").entrySet()) {
			for (var en2 : ((JsonObject)en.getValue()).entrySet()) {
				if (en2.getValue() instanceof JsonObject jo) {
					Map<String, JexlExpression> res = new LinkedHashMap<>();
					for (var en3 : jo.entrySet()) {
						res.put(en3.getKey(), createExpr(engine, "_defs."+en.getKey()+"."+en2.getKey()+"."+en3.getKey(), ((JsonPrimitive)en3.getValue()).asString()));
					}
					mapDefs.put(en.getKey(), en2.getKey(), res);
				} else if (en2.getValue() instanceof JsonPrimitive jp) {
					defs.put(en.getKey(), en2.getKey(), createExpr(engine,  "_defs."+en.getKey()+"."+en2.getKey(), jp.asString()));
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
				int ytdlPriority = jo.getInt("ytdlPriority", 0);
				String ext = jo.get(String.class, "ext");
				String type = jo.get(String.class, "type");
				boolean direct = jo.getBoolean("direct", false);
				boolean cache = jo.getBoolean("cache", true);
				ImmutableList<String> args = jo.get(JsonArray.class, "args").stream()
						.map(ele -> ((JsonPrimitive)ele).asString())
						.collect(ImmutableList.toImmutableList());
				
				var availableWhenExpr = createExpr(engine, name+".availableWhen", jo.get(String.class, "availableWhen"));
				BooleanSupplier availableWhen = () -> evaluate(name+".availableWhen", availableWhenExpr, availableWhenCtx, Boolean.class);
				
				var sizeEstimateExpr = createExpr(engine, name+".sizeEstimate", jo.get(String.class, "sizeEstimate"));
				ToDoubleFunction<TrackData> sizeEstimate = (data) -> {
					var ctx = new MapContext(new HashMap<>(defs.row("sizeEstimate")));
					ctx.set("durationSecs", data.durationSecs());
					ctx.set("durationSamples", data.durationSamples());
					ctx.set("master", data.master());
					return evaluate(name+".sizeEstimate", sizeEstimateExpr, ctx, Double.class);
				};
				
				Map<String, JexlExpression> replaygainWork;
				JsonElement replaygainEle = jo.get("replaygain");
				if (replaygainEle instanceof JsonPrimitive rp) {
					replaygainWork = mapDefs.get("replaygain", rp.asString());
				} else if (replaygainEle instanceof JsonObject ro) {
					replaygainWork = new LinkedHashMap<>();
					for (var en2 : ro.entrySet()) {
						replaygainWork.put(en2.getKey(), createExpr(engine, name+".replaygain."+en2.getKey(), ((JsonPrimitive)en2.getValue()).asString()));
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
				
				out.add(new TranscodeFormat(name, usage, displayName, ytdlPriority, ext, type, args, availableWhen, direct, cache, sizeEstimate, replaygain, ImmutableList.of()));
			}
		}
		return out.build();
	}

	private static JexlExpression createExpr(Engine engine, String info, String expr) {
		try {
			return engine.createExpression(expr);
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
