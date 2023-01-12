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

package com.unascribed.partyflow.logic;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.unascribed.partyflow.Partyflow;
import com.unascribed.partyflow.util.Commands;
import com.unascribed.partyflow.util.MoreByteStreams;
import com.unascribed.partyflow.util.Processes;
import com.unascribed.partyflow.util.ThreadPools;

public class AACSupport {
	
	private static final Logger log = LoggerFactory.getLogger(AACSupport.class);

	public static void test() {
		ThreadPools.GENERIC.execute(() -> {
			if ("qaac".equals(Partyflow.config.formats.aacMode)) {
				testAltcmd(0, "qaac", "--check");
			} else if ("fdkaac".equals(Partyflow.config.formats.aacMode)) {
				testAltcmd(1, "fdkaac", "--help");
			} else if ("ffmpeg-fdk".equals(Partyflow.config.formats.aacMode)) {
				try {
					var ffm = Commands.ffmpeg("-encoders")
							.redirectErrorStream(true)
							.start();
					ffm.getOutputStream().close();
					String output = MoreByteStreams.slurp(ffm.getInputStream());
					if (output.contains(" libfdk_aac ")) {
						log.info("FFmpeg appears to have libfdk_aac support. AAC support enabled");
					} else {
						log.error("aacMode is set to ffmpeg-fdk, but libfdk_aac support is not available. Disabling AAC support!");
						Partyflow.config.formats.aacMode = "none";
					}
				} catch (IOException e) {
					log.warn("FFmpeg seems to be broken, couldn't execute it to test for libfdk_aac support", e);
				}
			} else if ("none".equals(Partyflow.config.formats.aacMode)) {
				log.info("AAC support is disabled");
			} else {
				log.info("Unknown AAC support mode {} - this will only work if you've modified the format definitions", Partyflow.config.formats.aacMode);
			}
		});
	}

	private static void testAltcmd(int expectedCode, String altcmd, String... args) {
		boolean success = false;
		try {
			var p = Commands.altcmd(altcmd, (Object[])args)
					.redirectErrorStream(true)
					.start();
			p.getOutputStream().close();
			String output = MoreByteStreams.slurp(p.getInputStream());
			int code = Processes.waitForUninterruptibly(p);
			success = code == expectedCode;
			if (!success) {
				log.error("aacMode is set to {}, but {} is not working. Disabling AAC support!\nExit code: {}, output:\n{}", altcmd, altcmd, code, output);
			}
		} catch (IOException e) {
			log.error("aacMode is set to {}, but {} is not working. Disabling AAC support!", altcmd, altcmd, e);
		}
		if (!success) {
			Partyflow.config.formats.aacMode = "none";
		} else {
			log.info("{} seems to be working. AAC support enabled", altcmd);
		}
	}
	
}
