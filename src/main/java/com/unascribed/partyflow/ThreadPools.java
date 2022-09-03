package com.unascribed.partyflow;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPools {

	public static final ExecutorService GENERIC;
	public static final ExecutorService TRANSCODE;
	
	static {
		GENERIC = new ThreadPoolExecutor(1, Runtime.getRuntime().availableProcessors(),
				5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), namedFactory("Generic pool thread"));
		int maxTranscodes = Partyflow.config.programs.maxTranscodes;
		if (maxTranscodes == 0) maxTranscodes = Runtime.getRuntime().availableProcessors();
		TRANSCODE = new ThreadPoolExecutor(0, maxTranscodes,
				5, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), namedFactory("Transcode pool thread"));
	}

	private static ThreadFactory namedFactory(String name) {
		AtomicInteger counter = new AtomicInteger(0);
		return r -> {
			var t = new Thread(r, name+" #"+counter.getAndIncrement());
			t.setDaemon(true);
			return t;
		};
	}
	
}
