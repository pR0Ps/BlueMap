#!/usr/bin/env python

from setuptools import setup

setup(
    name="bluemap-wswatcher",
    version="0.0.1",
    description=(
        "Watches a BlueMap tile directory and broadcasts updates via "
        "a websocket when tiles are updated"
    ),
    license="MIT",
    py_modules=["wswatcher"],
    entry_points={
        "console_scripts": ["bluemap-wswatcher=wswatcher:main"]
    },
    python_requires=">=3.8.0",
    install_requires=[
        "watchfiles>=0.19.0,<0.25.0",
        "websockets>=11.0.3,<14.0.0",
    ],
)
