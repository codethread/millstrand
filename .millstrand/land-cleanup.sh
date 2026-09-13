#!/bin/sh
set -eu

if [ -e .test-repl.pid ] || [ -e .test-repl-port ]; then
  exec make test-warm-stop
fi
