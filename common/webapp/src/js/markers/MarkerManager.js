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
import {MarkerSet} from "./MarkerSet";
import {alert, httpToWebsocketUrl} from "../util/Utils";
import {RevalidatingFileLoader} from "../util/RevalidatingFileLoader";

/**
 * A manager for loading and updating markers from a file
 */
export class MarkerManager {

    /**
     * @constructor
     * @param root {MarkerSet} - The scene to which all markers will be added
     * @param fileUrl {string} - The marker file from which this manager updates its markers
     * @param events {EventTarget}
     */
    constructor(root, fileUrl, events = null) {
        Object.defineProperty(this, 'isMarkerManager', {value: true});

        this.root = root;
        this.fileUrl = fileUrl;
        this.events = events;
        this.disposed = false;

        /** @type {NodeJS.Timeout} */
        this._updateInterval = null;
        /** @type {WebSocket|null} */
        this._websocket = null;
    }

    /**
     * Sets the automatic-update frequency, setting this to 0 or negative disables automatic updates (default).
     * First attempts to use a WebSocket at the fileUrl for event-driven updates; falls back to polling if the
     * connection cannot be established or fails.
     * @param ms - polling fallback interval in milliseconds
     */
    setAutoUpdateInterval(ms) {
        if (this._updateInterval) clearTimeout(this._updateInterval);
        if (this._websocket) {
            this._websocket.close();
            this._websocket = null;
        }

        if (ms <= 0) return;

        // try websocket first if possible, fall back to http polling
        const wsUrl = httpToWebsocketUrl(this.fileUrl);
        if (!wsUrl){
            this._startPolling(ms);
            return;
        }

        let opened = false;
        const ws = new WebSocket(wsUrl);
        this._websocket = ws;

        ws.addEventListener("open", () => {opened = true;});
        ws.addEventListener("message", ({data}) => {
            if (this.disposed) return;
            try {
                this.updateFromData(JSON.parse(data));
            }
            catch (e){
                alert(this.events, e, "warning");
                this.clear()
            }
        });
        ws.addEventListener("close", () => {
            if (this._websocket !== ws) return;  // superseded by a later call
            if (!opened && !this.disposed) {
                this._websocket = null;
                this._startPolling(ms);
            }
        });
    }

    /**
     * @private
     */
    _startPolling(ms) {
        let autoUpdate = () => {
            if (this.disposed) return;
            this.update()
                .then(success => {
                    if (success) {
                        this._updateInterval = setTimeout(autoUpdate, ms);
                    } else {
                        this._updateInterval = setTimeout(autoUpdate, Math.max(ms, 1000 * 15));
                    }
                })
                .catch(e => {
                    alert(this.events, e, "warning");
                    this._updateInterval = setTimeout(autoUpdate, Math.max(ms, 1000 * 15));
                });
        };

        this._updateInterval = setTimeout(autoUpdate, ms);
    }

    /**
     * Loads the marker-file and updates all managed markers.
     * @returns {Promise<object>} - A promise completing when the markers finished updating
     */
    update() {
        return this.loadMarkerFile()
            .then(markerFileData => this.updateFromData(markerFileData))
            .catch(() => this.clear());
    }

    /**
     * @protected
     * @param markerData
     */
    updateFromData(markerData) {}

    /**
     * Stops automatic-updates and disposes all markersets and markers managed by this manager
     */
    dispose() {
        this.disposed = true;
        this.setAutoUpdateInterval(0);
        this.clear();
    }

    /**
     * Removes all markers managed by this marker-manager
     */
    clear() {
        this.root.clear();
    }

    /**
     * @private
     * Loads the marker file
     * @returns {Promise<Object>} - A promise completing with the parsed json object from the loaded file
     */
    loadMarkerFile() {
        return new Promise((resolve, reject) => {
            let loader = new RevalidatingFileLoader();
            loader.setRevalidatedUrls(new Set()); // force no-cache requests
            loader.setResponseType("json");
            loader.load(this.fileUrl,
                markerFileData => {
                    if (!markerFileData) reject(`Failed to parse '${this.fileUrl}'!`);
                    else resolve(markerFileData);
                },
                () => {},
                () => reject(`Failed to load '${this.fileUrl}'!`)
            )
        });
    }

}
