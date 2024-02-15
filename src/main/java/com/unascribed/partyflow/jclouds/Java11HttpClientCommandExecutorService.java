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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.USER_AGENT;
import static org.jclouds.Constants.PROPERTY_IDEMPOTENT_METHODS;
import static org.jclouds.Constants.PROPERTY_USER_AGENT;
import static org.jclouds.http.HttpUtils.filterOutContentHeaders;
import static org.jclouds.io.Payloads.newInputStreamPayload;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import org.jclouds.http.HttpRequest;
import org.jclouds.http.HttpResponse;
import org.jclouds.http.HttpUtils;
import org.jclouds.http.IOExceptionRetryHandler;
import org.jclouds.http.handlers.DelegatingErrorHandler;
import org.jclouds.http.handlers.DelegatingRetryHandler;
import org.jclouds.http.internal.BaseHttpCommandExecutorService;
import org.jclouds.http.internal.HttpWire;
import org.jclouds.io.ContentMetadataCodec;
import org.jclouds.io.MutableContentMetadata;
import org.jclouds.io.Payload;
import org.jclouds.proxy.internal.GuiceProxyConfig;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.ImmutableMultimap.Builder;
import com.google.inject.Inject;


/**
 * Based on OkHttpCommandExecutorService: https://github.com/apache/jclouds/blob/rel/jclouds-2.5.0/drivers/okhttp/src/main/java/org/jclouds/http/okhttp/OkHttpCommandExecutorService.java
 */
public final class Java11HttpClientCommandExecutorService extends BaseHttpCommandExecutorService<java.net.http.HttpRequest> {
	private final HttpClient globalClient;
	private final String userAgent;
	private final GuiceProxyConfig proxyConfig;

	@Inject
	Java11HttpClientCommandExecutorService(HttpUtils utils, ContentMetadataCodec contentMetadataCodec,
			DelegatingRetryHandler retryHandler, IOExceptionRetryHandler ioRetryHandler,
			DelegatingErrorHandler errorHandler, HttpWire wire, Function<URI, Proxy> proxyForURI, HttpClient.Builder httpClient,
			@Named(PROPERTY_IDEMPOTENT_METHODS) String idempotentMethods,
			@Named(PROPERTY_USER_AGENT) String userAgent, GuiceProxyConfig proxyConfig) {
		super(utils, contentMetadataCodec, retryHandler, ioRetryHandler, errorHandler, wire, idempotentMethods);
		httpClient = httpClient
				.proxy(new ProxySelector() {
					@Override
					public List<Proxy> select(URI uri) {
						return List.of(proxyForURI.apply(uri));
					}

					@Override
					public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {}
				});
		if (proxyConfig.getCredentials().isPresent()) {
			httpClient.authenticator(new Authenticator() {
				@Override
				protected PasswordAuthentication getPasswordAuthentication() {
					if (getRequestorType() == RequestorType.PROXY) {
						var creds = proxyConfig.getCredentials().get();
						return new PasswordAuthentication(creds.identity, creds.credential.toCharArray());
					}
					return null;
				}
			});
		}
		globalClient = httpClient.build();
		this.userAgent = userAgent;
		this.proxyConfig = proxyConfig;
	}

	@Override
	protected java.net.http.HttpRequest convert(HttpRequest request) throws IOException, InterruptedException {
		java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder();

		builder.uri(request.getEndpoint());
		populateHeaders(request, builder);

		BodyPublisher body = BodyPublishers.noBody();
		Payload payload = request.getPayload();

		if (payload != null) {
			Long length = checkNotNull(payload.getContentMetadata().getContentLength(), "payload.getContentLength");
			if (length > 0) {
				body = generateRequestBody(request, payload);
			}
		}

		builder.method(request.getMethod(), body);

		return builder.build();
	}

	protected void populateHeaders(HttpRequest request, java.net.http.HttpRequest.Builder builder) {
		// OkHttp does not set the Accept header if not present in the request.
		// Make sure we send a flexible one.
		if (request.getFirstHeaderOrNull(ACCEPT) == null) {
			builder.header(ACCEPT, "*/*");
		}
		if (request.getFirstHeaderOrNull(USER_AGENT) == null) {
			builder.header(USER_AGENT, userAgent);
		}
		for (Map.Entry<String, String> entry : request.getHeaders().entries()) {
			builder.header(entry.getKey(), entry.getValue());
		}
		if (request.getPayload() != null) {
			MutableContentMetadata md = request.getPayload().getContentMetadata();
			for (Map.Entry<String, String> entry : contentMetadataCodec.toHeaders(md).entries()) {
				builder.header(entry.getKey(), entry.getValue());
			}
		}
	}

	private BodyPublisher generateEmptyRequestBody(final Payload payload) {
		return BodyPublishers.noBody();
	}

	protected BodyPublisher generateRequestBody(final HttpRequest request, final Payload payload) {
		checkNotNull(payload.getContentMetadata().getContentType(), "payload.getContentType");
		var pub = BodyPublishers.ofInputStream(() -> {
			try {
				return payload.openStream();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		});
		if (payload.getContentMetadata().getContentLength() != null) {
			pub = BodyPublishers.fromPublisher(pub, payload.getContentMetadata().getContentLength());
		}
		return pub;
	}

	@Override
	protected HttpResponse invoke(java.net.http.HttpRequest nativeRequest) throws IOException, InterruptedException {
		java.net.http.HttpResponse<InputStream> response = globalClient.send(nativeRequest, BodyHandlers.ofInputStream());

		HttpResponse.Builder<?> builder = HttpResponse.builder();
		builder.statusCode(response.statusCode());
		builder.message("");

		Builder<String, String> headerBuilder = ImmutableMultimap.builder();
		HttpHeaders responseHeaders = response.headers();
		for (var en : responseHeaders.map().entrySet()) {
			headerBuilder.putAll(en.getKey(), en.getValue());
		}

		ImmutableMultimap<String, String> headers = headerBuilder.build();

		if (response.statusCode() == 204 && response.body() != null) {
			response.body().close();
		} else {
			Payload payload = newInputStreamPayload(response.body());
			contentMetadataCodec.fromHeaders(payload.getContentMetadata(), headers);
			builder.payload(payload);
		}

		builder.headers(filterOutContentHeaders(headers));

		return builder.build();
	}

	@Override
	protected void cleanup(java.net.http.HttpRequest nativeRequest) {
	}

}
