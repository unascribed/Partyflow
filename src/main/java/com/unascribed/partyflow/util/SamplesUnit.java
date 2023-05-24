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

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalUnit;

public class SamplesUnit implements TemporalUnit {

	public static final SamplesUnit INST = new SamplesUnit();
	
	private SamplesUnit() {}
	
	@Override
	public boolean isTimeBased() { return false; }
	@Override
	public boolean isDurationEstimated() { return false; }
	@Override
	public boolean isDateBased() { return false; }
	@Override
	public Duration getDuration() { return Duration.ofNanos(20833/* and a third */); }
	
	@Override
	public long between(Temporal temporal1Inclusive, Temporal temporal2Exclusive) {
		long nanos = temporal1Inclusive.until(temporal2Exclusive, ChronoUnit.NANOS);
		return (nanos*3)/62500;
	}
	
	@Override
	@SuppressWarnings("unchecked") // operation is safe
	public <R extends Temporal> R addTo(R temporal, long amount) {
		return (R)temporal.plus((amount*62500)/3, ChronoUnit.NANOS);
	}
	
}
