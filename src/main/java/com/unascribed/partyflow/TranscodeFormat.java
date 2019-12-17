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
import com.google.common.collect.ImmutableSet;

public enum TranscodeFormat {
	// download formats - exposed to user
	          FLAC( 0,      "FLAC", DOWNLOAD, 999999, "flac",  "audio/flac"               , "-f flac -codec:a flac"),
	          ALAC( 1,      "ALAC", DOWNLOAD,  99999,  "m4a", "audio/x-m4a; codecs=alac"  , "-f ipod -codec:a alac -movflags +faststart"),
	  OGG_OPUS_128( 2,      "Opus", DOWNLOAD,   1280, "opus",   "audio/ogg; codecs=opus"  , "-f ogg -codec:a libopus -b:a 128k"),
	  CAF_OPUS_128( 3,"Apple Opus", DOWNLOAD,    128,  "caf", "audio/x-caf; codecs=opus"  , "-f caf -codec:a libopus -b:a 128k"),
	OGG_VORBIS_192( 4,"Ogg Vorbis", DOWNLOAD,    192,  "ogg",   "audio/ogg; codecs=vorbis", "-f ogg -codec:a libvorbis -q:a 6"),
	        MP3_V0( 5,    "MP3 V0", DOWNLOAD,   9999,  "mp3",  "audio/mpeg"               , "-f mp3 -codec:a libmp3lame -q:a 0"),
	       MP3_320( 6,   "MP3 320", DOWNLOAD,    320,  "mp3",  "audio/mpeg"               , "-f mp3 -codec:a libmp3lame -b:a 320k"),
	           WAV(11,       "WAV", DOWNLOAD,    999,  "wav",  "audio/wav"                , "-f wav"),
	          AIFF(12,      "AIFF", DOWNLOAD,    998, "aiff",  "audio/aiff"               , "-f aiff"),
	        AAC_96(13,       "AAC", DOWNLOAD,     96,  "aac", "audio/x-m4a; codecs=aac"   , "-f ipod -codec:a libfdk_aac -vbr:a 4 -movflags +faststart"),

	// streaming formats, in order of preference; mostly invisible to user
	   OGG_OPUS_72( 7,        null,   STREAM,  4, "opus",   "audio/ogg; codecs=opus"  , "-f ogg -codec:a libopus -b:a 72k" ),
	   CAF_OPUS_72( 8,        null,   STREAM,  3,  "caf", "audio/x-caf; codecs=opus"  , "-f caf -codec:a libopus -b:a 72k" ),
	 OGG_VORBIS_96( 9,        null,   STREAM,  2,  "ogg",   "audio/ogg; codecs=vorbis", "-f ogg -codec:a libvorbis -b:a 96k" ),
	       MP3_128(10,        null,   STREAM,  1,  "mp3",  "audio/mpeg"               , "-f mp3 -codec:a libmp3lame -b:a 128k"),
	;

	private static final Splitter SPACE_SPLITTER = Splitter.on(' ').omitEmptyStrings().trimResults();

	public static final ImmutableSet<TranscodeFormat> ENCUMBERED_FORMATS = ImmutableSet.of(AAC_96);
	public static final ImmutableSet<TranscodeFormat> MP3_FORMATS = ImmutableSet.of(MP3_V0, MP3_320);
	public static final ImmutableSet<TranscodeFormat> LOSSLESS_FORMATS = ImmutableSet.of(FLAC, ALAC);
	public static final ImmutableSet<TranscodeFormat> UNCOMPRESSED_FORMATS = ImmutableSet.of(WAV, AIFF);
	public static final ImmutableSet<TranscodeFormat> ALL_FORMATS = ImmutableSet.copyOf(values());

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
	private final int ytdlPriority;
	private final Usage usage;
	private final String fileExt;
	private final String mimeType;
	private final String ffmpegOptions;
	private String[] ffmpegArguments;


	private TranscodeFormat(int databaseId, String name, Usage usage, int ytdlPriority, String fileExt, String mimeType, String ffmpegOptions) {
		this.databaseId = databaseId;
		this.name = name;
		this.usage = usage;
		this.ytdlPriority = ytdlPriority;
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

	public int getYtdlPriority() {
		return ytdlPriority;
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

	public boolean isEncumbered() {
		return ENCUMBERED_FORMATS.contains(this);
	}

	public boolean isMP3() {
		return MP3_FORMATS.contains(this);
	}

	public boolean isLossless() {
		return LOSSLESS_FORMATS.contains(this);
	}

	public boolean isUncompressed() {
		return UNCOMPRESSED_FORMATS.contains(this);
	}
}
