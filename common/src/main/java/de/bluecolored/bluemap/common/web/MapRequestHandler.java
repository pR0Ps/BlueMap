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

import de.bluecolored.bluemap.common.config.PluginConfig;
import de.bluecolored.bluemap.common.live.LiveMarkersDataSupplier;
import de.bluecolored.bluemap.common.live.LivePlayersDataSupplier;
import de.bluecolored.bluemap.common.serverinterface.Server;
import de.bluecolored.bluemap.common.web.http.HttpRequestHandler;
import de.bluecolored.bluemap.common.web.http.HttpResponse;
import de.bluecolored.bluemap.common.web.http.HttpStatusCode;
import de.bluecolored.bluemap.common.web.http.WebSocketConnection;
import de.bluecolored.bluemap.core.map.BmMap;
import de.bluecolored.bluemap.core.storage.MapStorage;
import org.jetbrains.annotations.Nullable;

import com.flowpowered.math.vector.Vector2i;

import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class MapRequestHandler extends RoutingRequestHandler {

    private final WebSocketConnectionManager tileUpdateConnections = new WebSocketConnectionManager();

    public MapRequestHandler(BmMap map, Server serverInterface, PluginConfig pluginConfig, Predicate<UUID> playerFilter) {
        this(map.getStorage(),
                new LivePlayersDataSupplier(serverInterface, pluginConfig, map.getWorld(), playerFilter),
                new LiveMarkersDataSupplier(map.getMarkerSets()));

        // only register the handler for map updates if we're given the actual map
        // instance from the plugin (ie. not running standalone)
        map.getHiresModelManager().addTileUpdateListener(tile -> onTileUpdate(tile, 0));
        map.getLowresTileManager().addTileUpdateListener((tile, lod) -> onTileUpdate(tile, lod));

        register("live/tileupdates", "", (HttpRequestHandler) request -> {
            WebSocketConnection connection = request.getWebSocket();
            if (connection == null) return new HttpResponse(HttpStatusCode.BAD_REQUEST);
            tileUpdateConnections.add(connection);
            try {
                connection.readLoop();
            } finally {
                tileUpdateConnections.remove(connection);
            }
            return null;
        });
    }

    private void onTileUpdate(Vector2i tile, int lod) {
        // since the data is all ints there's no escaping issues so just build the JSON the hacky fast way
        tileUpdateConnections.broadcast("{\"x\":" + tile.getX() + ",\"y\":" + tile.getY() + ",\"lod\":" + lod + "}");
    }

    public MapRequestHandler(MapStorage mapStorage) {
        this(mapStorage, null, null);
    }

    public MapRequestHandler(MapStorage mapStorage,
                             @Nullable Supplier<String> livePlayersDataSupplier,
                             @Nullable Supplier<String> liveMarkerDataSupplier) {

        register(".*", new MapStorageRequestHandler(mapStorage));

        if (livePlayersDataSupplier != null) {
            register("live/players\\.json", "", new LiveJsonDataRequestHandler(livePlayersDataSupplier));
        }

        if (liveMarkerDataSupplier != null) {
            register("live/markers\\.json", "", new LiveJsonDataRequestHandler(liveMarkerDataSupplier));
        }
    }

}
