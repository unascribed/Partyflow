package com.unascribed.partyflow;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.apache.commons.jexl3.JexlInfo;

import blue.endless.jankson.Jankson;
import blue.endless.jankson.JsonArray;
import blue.endless.jankson.JsonObject;
import blue.endless.jankson.JsonPrimitive;
import blue.endless.jankson.api.SyntaxError;
import blue.endless.jankson.impl.ElementParserContext;
import blue.endless.jankson.impl.ParserContext;
import blue.endless.jankson.impl.StringParserContext;

public class Dankson extends Jankson {

	public String filename = "<unknown>";
	
	private final MethodHandle line, column, spc_builder;
	
	public Dankson(Builder builder) {
		super(builder);
		try {
			var lk = MethodHandles.privateLookupIn(Jankson.class, MethodHandles.lookup());
			lk.findSetter(Jankson.class, "allowBareRootObject", boolean.class)
				.invokeExact((Jankson)this, true);
			line = lk.findGetter(Jankson.class, "line", int.class);
			column = lk.findGetter(Jankson.class, "column", int.class);
			spc_builder = MethodHandles.privateLookupIn(StringParserContext.class, MethodHandles.lookup())
					.findGetter(StringParserContext.class, "builder", StringBuilder.class);
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
			if (codePoint == '<') {
				loader.push(new ShellStringParserContext(), this::setResult);
				return true;
			}
			return super.consume(codePoint, loader);
		}
		
	}
	
	public class NestableStringParserContext extends StringParserContext {
		
		protected final char open, close;
		
		protected int bracketCount = 1;
		protected int l, c;
		
		public NestableStringParserContext(char open, char close) {
			super('\0');
			this.open = open;
			this.close = close;
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
			if (codePoint == open) {
				bracketCount++;
			} else if (codePoint == close) {
				bracketCount--;
				if (bracketCount == 0) {
					return super.consume(0, loader);
				}
			}
			return super.consume(codePoint, loader);
		}
		
	}
	
	public class ShellStringParserContext implements ParserContext<JsonArray> {

		private final ShellStringParserContextInner inner = new ShellStringParserContextInner();
		
		@Override
		public boolean consume(int codePoint, Jankson loader) throws SyntaxError {
			return inner.consume(codePoint, loader);
		}

		@Override
		public void eof() throws SyntaxError {
			inner.eof();
		}

		@Override
		public boolean isComplete() {
			return inner.isComplete();
		}

		@Override
		public JsonArray getResult() throws SyntaxError {
			if (!inner.builder.isEmpty()) inner.components.add(inner.builder.toString());
			JsonArray arr = new JsonArray();
			for (String s : inner.components) arr.add(JsonPrimitive.of(s));
			return arr;
		}
		
	}
	
	public class ShellStringParserContextInner extends NestableStringParserContext {
		
		private final List<String> components = new ArrayList<>();
		private final StringBuilder builder;
		private int inQuote = 0;
		private boolean escaped = false;
		
		public ShellStringParserContextInner() {
			super('<', '>');
			try {
				this.builder = (StringBuilder)spc_builder.invokeExact((StringParserContext)this);
			} catch (Throwable t) {
				throw new AssertionError(t);
			}
		}

		@Override
		public boolean consume(int codePoint, Jankson loader) {
			if (codePoint == '\n') {
				return super.consume('\u00FF', loader);
			}
			if (codePoint == '\\') {
				escaped = true;
				return true;
			} else {
				if (codePoint == inQuote) {
					if (!escaped) {
						inQuote = 0;
						components.add(builder.toString());
						builder.setLength(0);
						return true;
					}
				} else if (inQuote == 0 && codePoint == ' ') {
					if (!builder.isEmpty()) {
						components.add(builder.toString());
						builder.setLength(0);
					}
					return true;
				} else if (codePoint == '\'' || codePoint == '"') {
					inQuote = codePoint;
					return true;
				}
				escaped = false;
			}
			return super.consume(codePoint, loader);
		}
		
	}
	
	public class ParenStringParserContext extends NestableStringParserContext {
		
		public ParenStringParserContext() {
			super('(', ')');
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
