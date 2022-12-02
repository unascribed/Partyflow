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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

public class Commands {

	public static final Logger log = LoggerFactory.getLogger(Commands.class);
	
	public static ProcessBuilder magick_convert(Object... arguments) throws IOException {
		return exec(combine(Partyflow.config.programs.magickConvert, arguments));
	}

	public static ProcessBuilder ffmpeg(Object... arguments) throws IOException {
		return exec(sub(combine(Partyflow.config.programs.ffmpeg, arguments), "mpeg"));
	}

	public static ProcessBuilder ffprobe(Object... arguments) throws IOException {
		return exec(sub(combine(Partyflow.config.programs.ffmpeg, arguments), "probe"));
	}

	public static ProcessBuilder altcmd(String altcmd, Object... arguments) throws IOException {
		var cmd = Partyflow.config.programs.altcmds.get(altcmd);
		if (cmd == null) throw new IllegalArgumentException("Unknown altcmd: "+altcmd);
		return exec(combine(cmd, arguments));
	}
	

	private static String[] combine(String[] a, Object[] b) {
		List<String> out = Lists.newArrayListWithExpectedSize(a.length+b.length);
		for (String s : a) {
			out.add(s);
		}
		for (Object o : b) {
			if (o instanceof String[] arr) {
				for (String s : arr) {
					out.add(s);
				}
			} else if (o instanceof Iterable<?> iter) {
				for (Object c : iter) {
					out.add(String.valueOf(c));
				}
			} else if (o != null) {
				out.add(String.valueOf(o));
			}
		}
		return out.toArray(new String[out.size()]);
	}

	private static String[] sub(String[] haystack, String replacement) {
		String[] nw = haystack.clone();
		for (int i = 0; i < nw.length; i++) {
			if (nw[i].contains("{}")) {
				nw[i] = nw[i].replace("{}", replacement);
			}
		}
		return nw;
	}

	private static ProcessBuilder exec(String[] arr) throws IOException {
		if (log.isTraceEnabled()) {
			log.trace("Executing command: {}", Joiner.on(' ').join(Arrays.stream(arr)
					.map(s -> s.length() > 100 ? "[snip - "+s.length()+"]" : s)
					.iterator()));
		}
		return new ProcessBuilder(arr);
	}
	
	
}
