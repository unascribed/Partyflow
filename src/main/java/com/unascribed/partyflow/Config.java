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

import com.unascribed.asyncsimplelog.AsyncSimpleLog.LogLevel;

public final class Config {

	public HttpSection http;
	public static final class HttpSection {
		public String bind;
		public int port;
		public String path;
		public String accessLog;
		public boolean cacheTemplates;
	}

	public LoggerSection logger;
	public static final class LoggerSection {
		public LogLevel level;
		public boolean color;
	}

	public DatabaseSection database;
	public static final class DatabaseSection {
		public enum DatabaseDriver {
			h2
		}
		public DatabaseDriver driver;
		public String file;
	}

	public StorageSection storage;
	public static final class StorageSection {
		public enum StorageDriver {
			fs
		}
		public StorageDriver driver;
		public String dir;
		public String publicUrlPattern;
	}

	public ProgramsSection programs;
	public static final class ProgramsSection {
		public String ffmpeg;
		public String magickConvert;
	}

}
