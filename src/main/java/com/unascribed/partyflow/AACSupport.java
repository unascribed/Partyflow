package com.unascribed.partyflow;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.ByteStreams;

public class AACSupport {
	
	private static final Logger log = LoggerFactory.getLogger(AACSupport.class);

	public static void test() {
		ThreadPools.GENERIC.execute(() -> {
			if ("qaac".equals(Partyflow.config.formats.aacMode)) {
				testAltcmd(0, "qaac", "--check");
			} else if ("fdkaac".equals(Partyflow.config.formats.aacMode)) {
				testAltcmd(1, "fdkaac", "--help");
			} else if ("ffmpeg".equals(Partyflow.config.formats.aacMode)) {
				try {
					var ffm = Commands.ffmpeg("-encoders")
							.redirectErrorStream(true)
							.start();
					ffm.getOutputStream().close();
					String output = new String(ByteStreams.toByteArray(ffm.getInputStream()), Charsets.UTF_8);
					if (output.contains(" libfdk_aac ")) {
						log.info("FFmpeg appears to have libfdk_aac support. AAC support enabled");
					} else {
						log.error("aacMode is set to ffmpeg, but libfdk_aac support is not available. Disabling AAC support!");
						Partyflow.config.formats.aacMode = "none";
					}
				} catch (IOException e) {
					log.warn("FFmpeg seems to be broken, couldn't execute it to test for libfdk_aac support", e);
				}
			} else {
				log.info("AAC support is disabled");
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
			String output = new String(ByteStreams.toByteArray(p.getInputStream()), Charsets.UTF_8);
			int code = p.isAlive() ? -1 : p.exitValue();
			success = code == expectedCode;
			while (p.isAlive()) {
				try {
					success = (code = p.waitFor()) == expectedCode;
				} catch (InterruptedException e) {}
			}
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
