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
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

public enum TranscodeFormat {
	// download formats - exposed to user
	          FLAC( 0,  DL,  99999, "flac", "audio/flac"              , "-f flac -codec:a flac"                    , "FLAC"),
	          ALAC( 1,  DL,  99998, "m4a",  "audio/x-m4a; codecs=alac", "-f ipod -codec:a alac {MF}"               , "Apple Lossless"),
	  OGG_OPUS_128( 2,  DL,  1280,  "opus", "audio/ogg; codecs=opus"  , "-f ogg -codec:a libopus -b:a 128k"        , "Opus"),
	OGG_VORBIS_192( 3,  DL,  192,   "ogg",  "audio/ogg; codecs=vorbis", "-f ogg -codec:a libvorbis -q:a 6"         , "Ogg Vorbis"),
	        MP3_V0( 4,  DL,  321,   "mp3",  "audio/mpeg"              , "-f mp3 -codec:a libmp3lame -q:a 0"        , "MP3 V0"),
	       MP3_320( 5,  DL,  320,   "mp3",  "audio/mpeg"              , "-f mp3 -codec:a libmp3lame -b:a 320k"     , "MP3 320"),
	           WAV( 6, DLD,  999,   "wav",  "audio/wav"               , "-f wav"                                   , "WAV"),
	          AIFF( 7, DLD,  998,   "aiff", "audio/aiff"              , "-f aiff"                                  , "AIFF"),
	       AAC_VBR( 8,  DL,  128,   "m4a",  "audio/x-m4a; codecs=aac" , "-f ipod -codec:a libfdk_aac -vbr:a 5 {MF}", "AAC"),
                          
	// streaming formats, in order of preference; mostly invisible to user
	// WebM is used for macOS/Safari support
	  WEBM_OPUS_72(20,  ST,  4, "opus",  "audio/webm; codecs=opus" , "-f webm -codec:a libopus -b:a 72k"),
	        AAC_72(21,  ST,  3,  "m4a",  "audio/x-m4a; codecs=aac" , "-f ipod -codec:a libfdk_aac -b:a 72k -cutoff 18k {MF}"),
	 OGG_VORBIS_96(22,  ST,  2,  "ogg",  "audio/ogg; codecs=vorbis", "-f ogg -codec:a libvorbis -q:a 2"),
	       MP3_128(23,  ST,  1,  "mp3",  "audio/mpeg"              , "-f mp3 -codec:a libmp3lame -b:a 128k"),
	
	// low quality streaming formats for future use when payment is enabled, by admin choice
	  WEBM_OPUS_48(30, STL,  4, "opus",  "audio/webm; codecs=opus" , "-f webm -codec:a libopus -b:a 48k"),
	 OGG_VORBIS_64(31, STL,  2,  "ogg",  "audio/ogg; codecs=vorbis", "-f ogg -codec:a libvorbis -q:a 0"),
	        MP3_96(32, STL,  1,  "mp3",  "audio/mpeg"              , "-f mp3 -codec:a libmp3lame -b:a 96k"),
	;

	public static final ImmutableSet<TranscodeFormat> ENCUMBERED_FORMATS = ImmutableSet.of(AAC_VBR, AAC_72);
	public static final ImmutableSet<TranscodeFormat> MP3_FORMATS = ImmutableSet.of(MP3_V0, MP3_320, MP3_96);
	public static final ImmutableSet<TranscodeFormat> LOSSLESS_FORMATS = ImmutableSet.of(FLAC, ALAC);
	public static final ImmutableSet<TranscodeFormat> UNCOMPRESSED_FORMATS = ImmutableSet.of(WAV, AIFF);
	public static final ImmutableSet<TranscodeFormat> ALL_FORMATS = ImmutableSet.copyOf(values());

	public enum Usage {
		DOWNLOAD,
		DOWNLOAD_DIRECT,
		STREAM,
		STREAM_LOW,
		;
		public static final Usage DL = DOWNLOAD;
		public static final Usage DLD = DOWNLOAD_DIRECT;
		public static final Usage ST = STREAM;
		public static final Usage STL = STREAM_LOW;
		public boolean canDownload() {
			return this == DOWNLOAD || this == DOWNLOAD_DIRECT;
		}
		public boolean canStream() {
			return this == STREAM || this == STREAM_LOW;
		}
	}

	private static final ImmutableMap<Integer, TranscodeFormat> BY_DATABASE_ID = ALL_FORMATS.stream()
			.collect(ImmutableMap.toImmutableMap(TranscodeFormat::getDatabaseId, f -> f));

	public static class Shortcut {
		private final String sourceStr;
		private TranscodeFormat source;
		private final String ffmpegOptions;
		private final String[] ffmpegArguments;
		private Shortcut(String sourceStr, String ffmpegOptions) {
			this.sourceStr = sourceStr;
			this.ffmpegOptions = ffmpegOptions;
			this.ffmpegArguments = optToArg(ffmpegOptions);
		}
		public TranscodeFormat getSource() {
			if (source == null) source = TranscodeFormat.valueOf(sourceStr);
			return source;
		}
		public String getFFmpegOptions() {
			return ffmpegOptions;
		}
		public String[] getFFmpegArguments() {
			return ffmpegArguments;
		}
	}

	private final int databaseId;
	private final String name;
	private final int ytdlPriority;
	private final Usage usage;
	private final String fileExt;
	private final String mimeType;
	private final String ffmpegOptions;
	private final String[] ffmpegArguments;
	private final ImmutableList<Shortcut> shortcuts;

	TranscodeFormat(int databaseId, Usage usage, int ytdlPriority, String fileExt, String mimeType, String ffmpegOptions, Shortcut... shortcuts) {
		this(databaseId, usage, ytdlPriority, fileExt, mimeType, ffmpegOptions, null, shortcuts);
	}

	TranscodeFormat(int databaseId, Usage usage, int ytdlPriority, String fileExt, String mimeType, String ffmpegOptions, String name, Shortcut... shortcuts) {
		this.databaseId = databaseId;
		this.name = name;
		this.usage = usage;
		this.ytdlPriority = ytdlPriority;
		this.fileExt = fileExt;
		this.mimeType = mimeType;
		this.ffmpegOptions = ffmpegOptions;
		this.ffmpegArguments = optToArg(ffmpegOptions);
		this.shortcuts = ImmutableList.copyOf(shortcuts);
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
	
	public boolean canReplayGain() {
		// there is no standard (at least, not one known to Regainer) for embedding RG info into WAV/AIFF
		return !isUncompressed();
	}

	public ImmutableList<Shortcut> getShortcuts() {
		return shortcuts;
	}

	private static String[] optToArg(String opt) {
		return Splitter.on(' ').omitEmptyStrings().trimResults().splitToList(opt.replace("{MF}", "-movflags +faststart")).toArray(String[]::new);
	}
}
