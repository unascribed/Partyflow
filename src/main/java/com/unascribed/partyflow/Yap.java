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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Warnings that only log once in a given run.
 */
public enum Yap {
	BAD_PROXY("""
		Got a local address on a request where the true request IP was desired.
		This suggests you're running Partyflow behind a proxy, and forgot to specify X-Forwarded-For and/or enable trustProxy.
		(You may also be testing locally, in which case you can ignore this warning.)
		"""),
	SATURATED_FILTER("""
		A downloads filter has become saturated. Congratulations on getting more traffic than you expected!
		To ensure download counts continue to update, you need to increase expectedTraffic.
		""")
	;

	private static final Logger log = LoggerFactory.getLogger(Partyflow.class);
	
	private final String msg;
	
	private boolean hasYapped;
	
	Yap(String msg) {
		this.msg = msg.stripIndent().strip()+"\nThis won't be printed again.";
	}
	
	private boolean yap() {
		if (hasYapped) return false;
		hasYapped = true;
		return true;
	}
	
	public void info() {
		if (!yap()) return;
		log.info(msg);
	}
	
	public void info(Object arg) {
		if (!yap()) return;
		log.info(msg, arg);
	}
	
	public void info(Object arg1, Object arg2) {
		if (!yap()) return;
		log.info(msg, arg1, arg2);
	}
	
	public void info(Object... args) {
		if (!yap()) return;
		log.info(msg, args);
	}
	
	
	public void warn() {
		if (!yap()) return;
		log.warn(msg);
	}
	
	public void warn(Object arg) {
		if (!yap()) return;
		log.warn(msg, arg);
	}
	
	public void warn(Object arg1, Object arg2) {
		if (!yap()) return;
		log.warn(msg, arg1, arg2);
	}
	
	public void warn(Object... args) {
		if (!yap()) return;
		log.warn(msg, args);
	}
	
	
	public void error() {
		if (!yap()) return;
		log.error(msg);
	}
	
	public void error(Object arg) {
		if (!yap()) return;
		log.error(msg, arg);
	}
	
	public void error(Object arg1, Object arg2) {
		if (!yap()) return;
		log.error(msg, arg1, arg2);
	}
	
	public void error(Object... args) {
		if (!yap()) return;
		log.error(msg, args);
	}
}
