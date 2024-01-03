/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
