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

import static com.unascribed.partyflow.TranscodeFormat.Usage.*;

import com.google.common.base.Splitter;

public enum TranscodeFormat {
	// download formats - exposed to user
	          FLAC( 0,      "FLAC", DOWNLOAD,   0, "flac",  "audio/flac"               , "-f flac -codec:a flac"                ),
	          ALAC( 1,      "ALAC", DOWNLOAD,   0,  "m4a", "audio/x-m4a; codecs=alac"  , "-f ipod -codec:a alac"                ),
	  OGG_OPUS_128( 2,      "Opus", DOWNLOAD, 128, "opus",   "audio/ogg; codecs=opus"  , "-f ogg  -codec:a libopus    -b:a 128k"),
	  CAF_OPUS_128( 3,"Apple Opus", DOWNLOAD, 128,  "caf", "audio/x-caf; codecs=opus"  , "-f caf  -codec:a libopus    -b:a 128k"),
	OGG_VORBIS_192( 4,"Ogg Vorbis", DOWNLOAD, 192,  "ogg",   "audio/ogg; codecs=vorbis", "-f ogg  -codec:a libvorbis  -q:a 6"   ),
	        MP3_V0( 5,    "MP3 V0", DOWNLOAD,  -1,  "mp3",  "audio/mpeg"               , "-f mp3  -codec:a libmp3lame -q:a 0"   ),
	       MP3_320( 6,   "MP3 320", DOWNLOAD, 320,  "mp3",  "audio/mpeg"               , "-f mp3  -codec:a libmp3lame -b:a 320k"),

	// streaming formats, in order of preference; mostly invisible to user
	   OGG_OPUS_72( 7,        null,   STREAM,  72, "opus",   "audio/ogg; codecs=opus"  , "-f ogg  -codec:a libopus    -b:a 72k" ),
	   CAF_OPUS_72( 8,        null,   STREAM,  72,  "caf", "audio/x-caf; codecs=opus"  , "-f caf  -codec:a libopus    -b:a 72k" ),
	 OGG_VORBIS_72( 9,        null,   STREAM,  72,  "ogg",   "audio/ogg; codecs=vorbis", "-f ogg  -codec:a libvorbis  -b:a 72k" ),
	       MP3_128(10,        null,   STREAM, 128,  "mp3",  "audio/mpeg"               , "-f mp3  -codec:a libmp3lame -b:a 128k"),
	;

	private static final Splitter SPACE_SPLITTER = Splitter.on(' ').omitEmptyStrings().trimResults();

	public enum Usage {
		DOWNLOAD,
		STREAM,
		;
		public boolean canDownload() {
			return this == DOWNLOAD;
		}
		public boolean canStream() {
			return this == STREAM;
		}
	}

	private static final TranscodeFormat[] BY_DATABASE_ID;
	static {
		int max = 0;
		for (TranscodeFormat tf : values()) {
			max = Math.max(tf.databaseId, max);
		}
		BY_DATABASE_ID = new TranscodeFormat[max+1];
		for (TranscodeFormat tf : values()) {
			BY_DATABASE_ID[tf.databaseId] = tf;
		}
	}

	private final int databaseId;
	private final String name;
	private final int bitrateInK;
	private final Usage usage;
	private final String fileExt;
	private final String mimeType;
	private final String ffmpegOptions;
	private String[] ffmpegArguments;


	private TranscodeFormat(int databaseId, String name, Usage usage, int bitrateInK, String fileExt, String mimeType, String ffmpegOptions) {
		this.databaseId = databaseId;
		this.name = name;
		this.usage = usage;
		this.bitrateInK = bitrateInK;
		this.fileExt = fileExt;
		this.mimeType = mimeType;
		this.ffmpegOptions = ffmpegOptions;
	}

	public int getDatabaseId() {
		return databaseId;
	}

	public String getName() {
		return name;
	}

	public int getBitrateInK() {
		return bitrateInK;
	}

	public Usage getUsage() {
		return usage;
	}

	public String getFileExtension() {
		return fileExt;
	}

	public String getMimeType() {
		return mimeType;
	}

	public String getFFmpegOptions() {
		return ffmpegOptions;
	}

	public String[] getFFmpegArguments() {
		if (ffmpegArguments == null) {
			synchronized (this) {
				if (ffmpegArguments == null) {
					ffmpegArguments = SPACE_SPLITTER.splitToList(ffmpegOptions).toArray(new String[0]);
				}
			}
		}
		return ffmpegArguments;
	}
}
