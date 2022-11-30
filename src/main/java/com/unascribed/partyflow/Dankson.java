package com.unascribed.partyflow;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.function.Consumer;

import org.apache.commons.jexl3.JexlInfo;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;
import blue.endless.jankson.api.SyntaxError;
import blue.endless.jankson.impl.ElementParserContext;
import blue.endless.jankson.impl.ParserContext;
import blue.endless.jankson.impl.StringParserContext;

public class Dankson extends Jankson {

	public String filename = "<unknown>";
	
	private final MethodHandle line, column;
	
	public Dankson(Builder builder) {
		super(builder);
		try {
			var lk = MethodHandles.privateLookupIn(Jankson.class, MethodHandles.lookup());
			lk.findSetter(Jankson.class, "allowBareRootObject", boolean.class)
				.invokeExact((Jankson)this, true);
			line = lk.findGetter(Jankson.class, "line", int.class);
			column = lk.findGetter(Jankson.class, "column", int.class);
		} catch (Throwable e) {
			throw new AssertionError(e);
		}
	}
	
	@Override
	public JsonObject load(File f) throws IOException, SyntaxError {
		filename = f.getName();
		return super.load(f);
	}
	
	public JsonObject load(String filename, InputStream in) throws IOException, SyntaxError {
		this.filename = filename;
		return super.load(in);
	}
	
	public JsonObject load(String filename, String s) throws SyntaxError {
		this.filename = filename;
		return super.load(s);
	}

	@Override
	public <T> void push(ParserContext<T> t, Consumer<T> consumer) {
		if (t instanceof ElementParserContext) {
			t = (ParserContext<T>) new DankElementParserContext();
		}
		super.push(t, consumer);
	}

	public class DankElementParserContext extends ElementParserContext {

		@Override
		public boolean consume(int codePoint, Jankson loader) throws SyntaxError {
			if (codePoint == '(') {
				loader.push(new ParenStringParserContext(), this::setResult);
				return true;
			}
			return super.consume(codePoint, loader);
		}
		
	}
	
	public class ParenStringParserContext extends StringParserContext {
		
		private int parenCount = 1;
		private int l, c;
		
		public ParenStringParserContext() {
			super('\0');
			try {
				this.l = (int)line.invokeExact((Jankson)Dankson.this);
				this.c = (int)column.invokeExact((Jankson)Dankson.this);
			} catch (Throwable t) {
				throw new AssertionError(t);
			}
		}
		
		@Override
		public boolean consume(int codePoint, Jankson loader) {
			if (codePoint == '\n') {
				return super.consume('\u00FF', loader);
			}
			if (codePoint == '(') {
				parenCount++;
			} else if (codePoint == ')') {
				parenCount--;
				if (parenCount == 0) {
					return super.consume(0, loader);
				}
			}
			return super.consume(codePoint, loader);
		}
		
		@Override
		public JsonPrimitive getResult() {
			final int l = this.l;
			final int c = this.c;
			return new JsonJexlExpression(super.getResult().asString().replace('\u00FF', '\n'), new JexlInfo(filename, l, c) {
				@Override
				public JexlInfo at(int newL, int newC) {
					return super.at(l+newL, c+newC);
				}
			});
		}
		
	}

	public class JsonJexlExpression extends JsonPrimitive {

		private final JexlInfo info;
		
		public JsonJexlExpression(String value, JexlInfo info) {
			super(value);
			this.info = info;
		}
		
		public JexlInfo getInfo() {
			return info;
		}

	}
	
}
