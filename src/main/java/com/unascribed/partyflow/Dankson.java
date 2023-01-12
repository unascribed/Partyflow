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
import blue.endless.jankson.api.Marshaller;
import blue.endless.jankson.api.SyntaxError;
import blue.endless.jankson.impl.ElementParserContext;
import blue.endless.jankson.impl.MarshallerImpl;
import blue.endless.jankson.impl.ObjectParserContext;
import blue.endless.jankson.impl.ParserContext;
import blue.endless.jankson.impl.StringParserContext;
import blue.endless.jankson.impl.TokenParserContext;

@SuppressWarnings("deprecation") // MarshallerImpl usage is not optional
public class Dankson extends Jankson {

	public String filename = "<unknown>";
	
	private final MethodHandle line, column, spc_builder;
	
	private boolean firstObject = false;
	
	public Dankson(Builder builder) {
		super(builder);
		try {
			var lk = MethodHandles.privateLookupIn(Jankson.class, MethodHandles.lookup());
			var lk2 = MethodHandles.privateLookupIn(Builder.class, MethodHandles.lookup());
			lk.findSetter(Jankson.class, "allowBareRootObject", boolean.class)
				.invokeExact((Jankson)this, true);
			lk.findSetter(Jankson.class, "marshaller", Marshaller.class)
				.invokeExact((Jankson)this, (Marshaller)lk2.findGetter(Builder.class, "marshaller", MarshallerImpl.class)
						.invoke(builder));
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
		firstObject = true;
		return super.load(f);
	}
	
	public JsonObject load(String filename, InputStream in) throws IOException, SyntaxError {
		this.filename = filename;
		firstObject = true;
		return super.load(in);
	}
	
	public JsonObject load(String filename, String s) throws SyntaxError {
		this.filename = filename;
		firstObject = true;
		return super.load(s);
	}

	@Override
	@SuppressWarnings("unchecked") // operations are safe
	public <T> void push(ParserContext<T> t, Consumer<T> consumer) {
		if (t instanceof ElementParserContext) {
			t = (ParserContext<T>) new DankElementParserContext();
		} else if (t instanceof TokenParserContext tpc) {
			try {
				t = (ParserContext<T>) new DankTokenParserContext(tpc.getResult().asString().codePointAt(0));
			} catch (SyntaxError e) {
				throw new AssertionError(e);
			}
		} else if (t instanceof ObjectParserContext) {
			t = (ParserContext<T>) new DankObjectParserContext(firstObject);
			if (firstObject) firstObject = false;
		}
		super.push(t, consumer);
	}

	public class DankObjectParserContext extends ObjectParserContext {

		public DankObjectParserContext(boolean assumeOpen) {
			super(assumeOpen);
		}

		@Override
		public boolean consume(int codePoint, Jankson loader) throws SyntaxError {
			try {
				return super.consume(codePoint, loader);
			} catch (SyntaxError e) {
				// this is the easiest way to get the ObjectParserContext to expose its internal state
				// I tried some other solutions that don't involve catching an exception and they were buggy
				if ("Found unexpected character '{' while looking for the colon (':') between a key and a value in an object".equals(e.getMessage())) {
					super.consume(':', loader);
					return super.consume(codePoint, loader);
				}
				throw e;
			}
		}
		
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
	
	public class DankTokenParserContext extends TokenParserContext {

		private final StringBuilder bldr = new StringBuilder();
		private boolean complete = false;
		
		public DankTokenParserContext(int firstCodePoint) {
			super(firstCodePoint);
			bldr.appendCodePoint(firstCodePoint);
		}
		
		@Override
		public boolean consume(int codePoint, Jankson loader) throws SyntaxError {
			if (complete) return false;
			
			if (codePoint == '~' || Character.isUnicodeIdentifierPart(codePoint) || codePoint == '-' || codePoint == '@') {
				bldr.appendCodePoint(codePoint);
				return true;
			} else {
				complete = true;
				return false;
			}
		}

		@Override
		public void eof() throws SyntaxError {
			complete = true;
		}

		@Override
		public boolean isComplete() {
			return complete;
		}

		@Override
		public JsonPrimitive getResult() throws SyntaxError {
			return JsonPrimitive.of(bldr.toString());
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
