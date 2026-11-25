// SPDX-License-Identifier: MIT
package ru.vtosters.lite.core.net;

import java.io.IOException;

/**
 * Single HTTP entry point for mod code (F-03). Implementations must define connect/read timeouts,
 * one retry with backoff, User-Agent, proxy support ({@code ProxyUtils}) and always close the
 * response body (try-with-resources).
 * Implementation TODO(F-03): wrap OkHttp/NetClient (see SEC-01, SEC-03).
 */
public interface ApiClient {

    String get(String url) throws IOException;

    String post(String url, String body, String contentType) throws IOException;
}
