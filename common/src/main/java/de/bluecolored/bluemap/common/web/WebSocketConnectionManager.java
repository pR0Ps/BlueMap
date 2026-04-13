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

import de.bluecolored.bluemap.common.web.http.WebSocketConnection;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks active {@link WebSocketConnection}s and provides thread-safe broadcast delivery.
 */
public class WebSocketConnectionManager {

    private final Set<WebSocketConnection> connections = ConcurrentHashMap.newKeySet();

    public void add(WebSocketConnection connection) {
        connections.add(connection);
    }

    public void remove(WebSocketConnection connection) {
        connections.remove(connection);
    }

    /**
     * Sends {@code message} to all live connections.
     * Dead or broken connections are removed automatically.
     */
    public void broadcast(String message) {
        if (connections.isEmpty()) return;

        connections.removeIf(conn -> {
            if (conn.isClosed()) return true;
            try {
                conn.send(message);
                return false;
            } catch (IOException e) {
                return true;  // remove broken connection
            }
        });
    }

    /**
     * Closes all registered connections and clears the registry.
     */
    public void closeAll() {
        for (WebSocketConnection conn : connections) {
            try { conn.close(); } catch (IOException ignore) {}
        }
        connections.clear();
    }

}
