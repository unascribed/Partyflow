package com.unascribed.partyflow;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ThreadPools {

	public static final ExecutorService TRANSCODE;
	
	static {
		int maxTranscodes = Partyflow.config.programs.maxTranscodes;
		if (maxTranscodes == 0) maxTranscodes = Runtime.getRuntime().availableProcessors();
		TRANSCODE = new ThreadPoolExecutor(0, maxTranscodes, 0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
	}
	
}
