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

import java.net.http.HttpClient;
import java.net.http.HttpClient.Redirect;
import java.time.Duration;
import javax.inject.Named;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

import org.jclouds.http.HttpCommandExecutorService;
import org.jclouds.http.HttpUtils;
import org.jclouds.http.config.ConfiguresHttpCommandExecutorService;
import org.jclouds.http.config.SSLModule;
import com.google.common.base.Supplier;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Scopes;


/**
 * Configures the {@link Java11HttpClientCommandExecutorService}.
 * <p>
 * Note that this uses threads.
 * <p>
 * Based on OkHttpCommandExecutorServiceModule: https://github.com/apache/jclouds/blob/rel/jclouds-2.5.0/drivers/okhttp/src/main/java/org/jclouds/http/okhttp/config/OkHttpCommandExecutorServiceModule.java
 */
@ConfiguresHttpCommandExecutorService
public class Java11HttpClientCommandExecutorServiceModule extends AbstractModule {

	@Override
	protected void configure() {
		install(new SSLModule());
		bind(HttpCommandExecutorService.class).to(Java11HttpClientCommandExecutorService.class).in(Scopes.SINGLETON);
		bind(HttpClient.Builder.class).toProvider(Java11HttpClientProvider.class).in(Scopes.SINGLETON);
	}

	private static final class Java11HttpClientProvider implements Provider<HttpClient.Builder> {
		private final HostnameVerifier verifier;
		private final Supplier<SSLContext> untrustedSSLContextProvider;
		private final X509TrustManager trustAllCertsManager;
		private final HttpUtils utils;
		private final Java11HttpClientSupplier clientSupplier;

		@Inject
		Java11HttpClientProvider(HttpUtils utils, @Named("untrusted") HostnameVerifier verifier,
				@Named("untrusted") Supplier<SSLContext> untrustedSSLContextProvider,
				@Named("untrusted") X509TrustManager trustAllCertsManager,
				Java11HttpClientSupplier clientSupplier) {
			this.utils = utils;
			this.verifier = verifier;
			this.untrustedSSLContextProvider = untrustedSSLContextProvider;
			this.trustAllCertsManager = trustAllCertsManager;
			this.clientSupplier = clientSupplier;
		}

		@Override
		public HttpClient.Builder get() {
			HttpClient.Builder clientBuilder = clientSupplier.get()
					.connectTimeout(Duration.ofMillis(utils.getConnectionTimeout()))
					.followRedirects(Redirect.NEVER);

			if (utils.trustAllCerts()) {
				clientBuilder.sslContext(untrustedSSLContextProvider.get());
			}

			return clientBuilder;
		}
	}

}
