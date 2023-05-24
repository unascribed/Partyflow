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

package com.unascribed.partyflow.util;

import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public class ThreadSafeRandomFacade implements RandomGenerator {
	
	private final ThreadLocal<RandomGenerator> delegate;
	
	public ThreadSafeRandomFacade(LeapableGenerator parent) {
		System.out.println(parent);
		var iter = parent.leaps().iterator();
		this.delegate = ThreadLocal.withInitial(() -> {
			synchronized (iter) {
				var child = iter.next();
				System.out.println(child);
				child.jump();
				return child;
			}
		});
	}
	
	public ThreadSafeRandomFacade(RandomGeneratorFactory<?> factory) {
		this.delegate = ThreadLocal.withInitial(factory::create);
	}

	@Override
	public boolean isDeprecated() {
		return delegate.get().isDeprecated();
	}

	@Override
	public DoubleStream doubles() {
		return delegate.get().doubles();
	}

	@Override
	public DoubleStream doubles(double randomNumberOrigin, double randomNumberBound) {
		return delegate.get().doubles(randomNumberOrigin, randomNumberBound);
	}

	@Override
	public DoubleStream doubles(long streamSize) {
		return delegate.get().doubles(streamSize);
	}

	@Override
	public DoubleStream doubles(long streamSize, double randomNumberOrigin, double randomNumberBound) {
		return delegate.get().doubles(streamSize, randomNumberOrigin, randomNumberBound);
	}

	@Override
	public IntStream ints() {
		return delegate.get().ints();
	}

	@Override
	public IntStream ints(int randomNumberOrigin, int randomNumberBound) {
		return delegate.get().ints(randomNumberOrigin, randomNumberBound);
	}

	@Override
	public IntStream ints(long streamSize) {
		return delegate.get().ints(streamSize);
	}

	@Override
	public IntStream ints(long streamSize, int randomNumberOrigin, int randomNumberBound) {
		return delegate.get().ints(streamSize, randomNumberOrigin, randomNumberBound);
	}

	@Override
	public LongStream longs() {
		return delegate.get().longs();
	}

	@Override
	public LongStream longs(long randomNumberOrigin, long randomNumberBound) {
		return delegate.get().longs(randomNumberOrigin, randomNumberBound);
	}

	@Override
	public LongStream longs(long streamSize) {
		return delegate.get().longs(streamSize);
	}

	@Override
	public LongStream longs(long streamSize, long randomNumberOrigin, long randomNumberBound) {
		return delegate.get().longs(streamSize, randomNumberOrigin, randomNumberBound);
	}

	@Override
	public boolean nextBoolean() {
		return delegate.get().nextBoolean();
	}

	@Override
	public void nextBytes(byte[] bytes) {
		delegate.get().nextBytes(bytes);
	}

	@Override
	public float nextFloat() {
		return delegate.get().nextFloat();
	}

	@Override
	public float nextFloat(float bound) {
		return delegate.get().nextFloat(bound);
	}

	@Override
	public float nextFloat(float origin, float bound) {
		return delegate.get().nextFloat(origin, bound);
	}

	@Override
	public double nextDouble() {
		return delegate.get().nextDouble();
	}

	@Override
	public double nextDouble(double bound) {
		return delegate.get().nextDouble(bound);
	}

	@Override
	public double nextDouble(double origin, double bound) {
		return delegate.get().nextDouble(origin, bound);
	}

	@Override
	public int nextInt() {
		return delegate.get().nextInt();
	}

	@Override
	public int nextInt(int bound) {
		return delegate.get().nextInt(bound);
	}

	@Override
	public int nextInt(int origin, int bound) {
		return delegate.get().nextInt(origin, bound);
	}

	@Override
	public long nextLong() {
		return delegate.get().nextLong();
	}

	@Override
	public long nextLong(long bound) {
		return delegate.get().nextLong(bound);
	}

	@Override
	public long nextLong(long origin, long bound) {
		return delegate.get().nextLong(origin, bound);
	}

	@Override
	public double nextGaussian() {
		return delegate.get().nextGaussian();
	}

	@Override
	public double nextGaussian(double mean, double stddev) {
		return delegate.get().nextGaussian(mean, stddev);
	}

	@Override
	public double nextExponential() {
		return delegate.get().nextExponential();
	}

}
