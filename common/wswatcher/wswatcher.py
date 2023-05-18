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
    r"maps/(?P<world>[^/]+)/tiles/(?P<lod>\d+)/x(?P<x>[-\d]+)/z(?P<z>[-\d]+)\..*"
)


async def serve(*, webroot, bind_address, port):
    webroot = os.path.abspath(webroot)

    __log__.info("Will watch webroot '%s' for changes", webroot)

    if not (tile_dirs := glob.glob(os.path.join(webroot, "maps", "*", "tiles"))):
        raise ValueError("No tile folders to watch found")

    __log__.debug("Found tile directories: %s", tile_dirs)

    # Set a stop event for the file watcher when receiving SIGINT/TERM
    loop = asyncio.get_running_loop()
    stop = asyncio.Event()

    def _stop(sig):
        __log__.critical("Handled signal, shutting down")
        stop.set()
        loop.remove_signal_handler(sig)

    loop.add_signal_handler(signal.SIGINT, _stop, signal.SIGINT)
    loop.add_signal_handler(signal.SIGINT, _stop, signal.SIGINT)

    # Set up a websocket connection handler that just keeps track of the currently-connected clients.
    connected = set()

    async def handler(websocket):
        connected.add(websocket)
        try:
            await websocket.wait_closed()
        finally:
            connected.remove(websocket)

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
                __log__.debug("Detected change at path %s (%s)", path, type.name)
                if m := PATH_EXTRACT_RE.fullmatch(relpath):
                    data = m.groupdict()
                    for x in ("lod", "x", "z"):
                        data[x] = int(data[x])

                    __log__.info(
                        "Detected change in world '%(world)s': lod=%(lod)s x=%(x)s z=%(z)s",
                        data,
                    )
                    websockets.broadcast(connected, json.dumps(data))
                else:
                    __log__.error("Failed to extract data from path '%s'", relpath)
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
