package com.unascribed.partyflow;

import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.AbstractListeningExecutorService;

/**
 * Executors.newSingleThreadExecutor returns a full-blown
 * {@link ThreadPoolExecutor}. This is a simple implementation of a
 * single-thread executor with a queue.
 */
public class SimpleSingleThreadExecutor extends AbstractListeningExecutorService {

	private static final AtomicInteger nextDefaultThreadId = new AtomicInteger();
	private static final ThreadFactory DEFAULT_THREAD_FACTORY = r -> {
		return new Thread(r, "Anonymous SimpleSingleThreadExecutor #"+nextDefaultThreadId.getAndIncrement());
	};
	
	private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>();
	private boolean shutdown = false;

	private long operationDelay = 0;

	private final Thread thread;
	
	public SimpleSingleThreadExecutor() {
		this(DEFAULT_THREAD_FACTORY);
	}
	
	public SimpleSingleThreadExecutor(String threadName) {
		this(r -> new Thread(r, threadName));
	}
	
	public SimpleSingleThreadExecutor(ThreadFactory threadFactory) {
		this.thread = threadFactory.newThread(() -> {
			while (!shutdown || !queue.isEmpty()) {
				try {
					queue.take().run();
					Thread.sleep(operationDelay);
				} catch (InterruptedException e) {
				}
			}
		});
	}

	public SimpleSingleThreadExecutor withOperationDelay(long operationDelay) {
		this.operationDelay = operationDelay;
		return this;
	}

	@Override
	public void execute(Runnable command) {
		if (shutdown) throw new IllegalStateException("Executor is shut down");
		if (!thread.isAlive()) {
			thread.start();
		}
		queue.add(command);
	}

	@Override
	public void shutdown() {
		shutdown = true;
	}

	@Override
	public List<Runnable> shutdownNow() {
		shutdown = true;
		List<Runnable> li = Lists.newArrayList();
		queue.drainTo(li);
		thread.interrupt();
		return li;
	}

	@Override
	public boolean isShutdown() {
		return shutdown;
	}

	@Override
	public boolean isTerminated() {
		return shutdown && !thread.isAlive();
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		unit.timedJoin(thread, timeout);
		return !thread.isAlive();
	}

}
