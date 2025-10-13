# Step 40.0 - DockerDataRoot

## Overview
Relocates Docker's data directory to a configurable target and updates daemon settings to match.

## Flow
1. Load the desired data-root path from configuration.
2. Inspect `docker info` to determine whether the data-root already matches.
3. Stop Docker, create the target directory, and copy existing data with `rsync` when a move is required.
4. Archive the old `/var/lib/docker` directory, write `/etc/docker/daemon.json`, and restart Docker.
5. On every run, verify the Docker data directory is owned by `root:docker` with `0770` permissions and fix it when needed.
6. Exit with code 10 and print summary details after data has been moved or configuration updated.

## Configuration
- `target`: Filesystem path where Docker's data-root should live (default `/data/docker`).
