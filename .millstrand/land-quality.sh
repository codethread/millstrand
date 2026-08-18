#!/bin/sh
# This is the target repository's trusted land-quality contract. The generic
# workflow wrapper validates the branch, pushed HEAD, and clean tree before and
# after this file runs. The Make target builds and runs the repository-owned DAG.
set -eu

exec make land-quality
