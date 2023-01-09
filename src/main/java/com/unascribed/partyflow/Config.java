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

import java.time.Duration;
import java.util.Map;

import com.unascribed.asyncsimplelog.AsyncSimpleLog.LogLevel;

public final class Config {

	public HttpSection http;
	public static final class HttpSection {
		public String bind;
		public int port;
		public String path;
		public String accessLog;
		public boolean cacheTemplates;
		public String publicUrl;
		public boolean trustProxy;
	}

	public LoggerSection logger;
	public static final class LoggerSection {
		public LogLevel level;
		public boolean color;
	}

	public DatabaseSection database;
	public static final class DatabaseSection {
		public enum DatabaseDriver {
			h2("CLOB", "BIGINT", "SMALLINT"),
			mariadb("TEXT", "INT UNSIGNED", "TINYINT UNSIGNED"),
			;
			private final String clob, u32, u8;
			DatabaseDriver(String clob, String u32, String u8) {
				this.clob = clob;
				this.u32 = u32;
				this.u8 = u8;
			}

			public String clob() { return clob; }
			public String u32() { return u32; }
			public String u8() { return u8; }
		}
		public DatabaseDriver driver;
		
		public int expectedTraffic;

		public H2Section h2;
		public static final class H2Section {
			public String file;
		}
		
		public MariaDBSection mariadb;
		public static final class MariaDBSection {
			public String host;
			public int port;
			public String user;
			public String pass;
			public String db;
		}
		
	}

	public SecuritySection security;
	public static final class SecuritySection {
		public String sessionSecret;
		public boolean https;
		
		public ScryptSection scrypt;
		public static final class ScryptSection {
			public int cpu;
			public int memory;
			public int parallelization;
		}
	}

	public StorageSection storage;
	public static final class StorageSection {
		public enum StorageDriver {
			fs, s3
		}
		public StorageDriver driver;

		public FsSection fs;
		public static final class FsSection {
			public String dir;
		}
		
		public S3Section s3;
		public static final class S3Section {
			public String endpoint;
			public String bucket;
			public String accessKeyId;
			public String secretAccessKey;
		}
		
		public Duration pruneTime;
		public String publicUrlPattern;
	}

	public ProgramsSection programs;
	public static final class ProgramsSection {
		public String[] ffmpeg;
		public String[] magickConvert;
		public int maxTranscodes;
		
		public boolean runWineserver;
		public String[] wineserver;
		
		public Map<String, String[]> altcmds;
	}

	public FormatsSection formats;
	public static final class FormatsSection {
		public String definitions;
		public String additionalDefinitions;
		
		public boolean allowUncompressedFormats;
		public boolean allowLosslessFormats;
		public boolean allowMP3;
		public boolean allowEncumberedFormats;
		
		public boolean recommendMP3;
		
		public String aacMode;
	}
	
	public Object custom;

}
