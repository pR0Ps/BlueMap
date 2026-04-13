/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.common.web;

import de.bluecolored.bluemap.common.web.http.HttpRequest;
import de.bluecolored.bluemap.common.web.http.HttpRequestHandler;
import de.bluecolored.bluemap.common.web.http.HttpResponse;
import de.bluecolored.bluemap.common.web.http.WebSocketConnection;

import java.io.IOException;
import java.util.function.Supplier;

/**
 * Handles a route that serves live JSON data from a {@link Supplier}.
 *
 * If a client indicates support, will upgrade the connection to a websocket and push updates whenever the data changes.
 * If the client does not indicate websocket support, will respond with a single JSON snapshot using a
 * {@link JsonDataRequestHandler} and {@link CachedRateLimitDataSupplier}
 *
 * {@code pollIntervalMillis} indicates how frequently you want to receive data. For HTTP connections this is passed to
 * the {@link CachedRateLimitDataSupplier}, for websockets it's how long to sleep before checking updates.
 */
public class LiveJsonDataRequestHandler implements HttpRequestHandler {

    private final Supplier<String> dataSupplier;
    private final HttpRequestHandler httpFallback;
    private final long pollIntervalMillis;

    public LiveJsonDataRequestHandler(Supplier<String> dataSupplier) {
        this(dataSupplier, 1000);
    }

    public LiveJsonDataRequestHandler(Supplier<String> dataSupplier, long pollIntervalMillis) {
        this.dataSupplier = dataSupplier;
        this.pollIntervalMillis = pollIntervalMillis;
        this.httpFallback = new JsonDataRequestHandler(new CachedRateLimitDataSupplier(dataSupplier, pollIntervalMillis));
    }

    @Override
    public HttpResponse handle(HttpRequest request) {
        WebSocketConnection connection = request.getWebSocket();
        if (connection == null) {
            return httpFallback.handle(request);
        }

        String data = null;
        try {
            // TODO: this doesn't call connection.readLoop() so will ignore
            // any input from the client (including not responding to PINGs)
            while (!connection.isClosed()) {
                String newData = dataSupplier.get();
                if (!newData.equals(data)) {
                    data = newData;
                    connection.send(data);
                }
                Thread.sleep(pollIntervalMillis);
            }
        }
        catch (IOException ignored) {}
        catch (InterruptedException e) {Thread.currentThread().interrupt();}
        return null;
    }

}
