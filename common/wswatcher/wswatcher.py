#!/usr/bin/env python

import argparse
import asyncio
import glob
import json
import logging
import os
import re
import signal

import watchfiles
import websockets
import websockets.server


__log__ = logging.getLogger(__name__)

DEBOUCE_MS = 5 * 1000  # time to wait between tile updates before re-reporting them
PATH_EXTRACT_RE = re.compile(
    r"maps/(?P<world>[^/]+)/tiles/(?P<lod>\d+)/x(?P<x>[-\d]+)/z(?P<z>[-\d]+)\.(?:png|json)(?:\.gz)?"
)


def make_tile_path(webroot, world):
    return os.path.join(webroot, "maps", world, "tiles")


async def serve(*, webroot, bind_address, port):
    webroot = os.path.abspath(webroot)

    __log__.info("Will watch webroot '%s' for changes", webroot)

    if not (tile_dirs := glob.glob(make_tile_path(webroot, "*"))):
        raise ValueError("No tile folders to watch found")

    __log__.debug("Found tile directories: %s", tile_dirs)

    # Set a stop event for the file watcher when receiving SIGINT/TERM
    loop = asyncio.get_running_loop()
    stop = asyncio.Event()

    def _stop(sig):
        __log__.critical("Handled signal, shutting down (send again to force)")
        stop.set()
        loop.remove_signal_handler(sig)

    loop.add_signal_handler(signal.SIGINT, _stop, signal.SIGINT)
    loop.add_signal_handler(signal.SIGINT, _stop, signal.SIGINT)

    # Set up a websocket connection handler that just keeps track of the currently-connected clients per-map.
    connected = dict()

    async def handler(websocket):
        path = websocket.path.strip("/")

        if not path or make_tile_path(webroot, path) not in tile_dirs:
            __log__.error("Client tried connect for unwatched world: '%s'", path)
            return  # close the connection

        if path not in connected:
            connected[path] = set()

        __log__.info("Client connected and subscribed to events from world: '%s'", path)
        connected[path].add(websocket)
        try:
            await websocket.wait_closed()
        finally:
            connected[path].remove(websocket)

    __log__.debug("Starting websocket server")
    async with websockets.server.serve(handler, bind_address, port):
        __log__.debug("Starting file watcher")
        async for changes in watchfiles.awatch(
            *tile_dirs,
            stop_event=stop,
            step=DEBOUCE_MS,
            debounce=DEBOUCE_MS,
            poll_delay_ms=DEBOUCE_MS,
        ):
            for type, path in changes:
                if type not in {watchfiles.Change.added, watchfiles.Change.modified}:
                    continue

                relpath = os.path.relpath(path, start=webroot)
                __log__.debug("Detected change at path %s (%s)", relpath, type.name)

                if m := PATH_EXTRACT_RE.fullmatch(relpath):
                    data = m.groupdict()
                    __log__.info(
                        "Detected change in world '%(world)s': lod=%(lod)s x=%(x)s z=%(z)s",
                        data,
                    )

                    world = data.pop("world")
                    if world in connected:
                        websockets.broadcast(
                            connected[world],
                            json.dumps({k: int(v) for k, v in data.items()}),
                        )
                else:
                    __log__.debug("Failed to extract data from path '%s'", relpath)
        __log__.debug("Stopped file watcher")
    __log__.debug("Stopped websocket server")


def main():
    parser = argparse.ArgumentParser(
        "bluemap-watcher",
        description="A server that emits JSON via a websocket when BlueMap tile data changes",
    )
    parser.add_argument("webroot", help="The BlueMap webroot to watch")
    parser.add_argument(
        "-b",
        "--bind",
        default="0.0.0.0",
        metavar="address",
        help="Address to bind to (default: '0.0.0.0')",
    )
    parser.add_argument(
        "-p", "--port", default=8765, help="Port to bind to (default: 8765)"
    )
    parser.add_argument(
        "-v",
        "--verbose",
        action="count",
        default=0,
        help="Increase log verbosity up to 3 times (default: ERROR, max: DEBUG)",
    )
    args = parser.parse_args()
    logging.basicConfig(
        level=(logging.ERROR, logging.WARNING, logging.INFO, logging.DEBUG)[
            min(3, max(0, args.verbose))
        ]
    )

    asyncio.run(serve(webroot=args.webroot, bind_address=args.bind, port=args.port))


if __name__ == "__main__":
    main()
