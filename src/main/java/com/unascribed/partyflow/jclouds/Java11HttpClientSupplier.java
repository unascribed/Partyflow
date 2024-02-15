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

package com.unascribed.partyflow.jclouds;

import com.google.common.base.Supplier;

import java.net.http.HttpClient;

import com.google.inject.ImplementedBy;

/**
 * Provides the Java 11 HTTP client used for all requests. This could be used to designate a custom
 * SSL context or limit TLS ciphers.
 * <p>
 * Note that it should configured it in the Guice module designated as
 * <code>@ConfiguresHttpApi</code>.
 * <p>
 * Based on OkHttpClientSupplier: https://github.com/apache/jclouds/blob/rel/jclouds-2.5.0/drivers/okhttp/src/main/java/org/jclouds/http/okhttp/OkHttpClientSupplier.java
 */
@ImplementedBy(Java11HttpClientSupplier.NewJava11HttpClient.class)
public interface Java11HttpClientSupplier extends Supplier<HttpClient.Builder> {

	static final class NewJava11HttpClient implements Java11HttpClientSupplier {
		@Override
		public HttpClient.Builder get() {
			return HttpClient.newBuilder();
		}
	}
}
