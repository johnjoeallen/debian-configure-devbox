# Step 35.0 - DockerInstall

## Overview
Installs Docker Engine and associated tooling from the official Docker apt repository.

## Flow
1. Exit early if the `docker` CLI is already present.
2. Install prerequisite packages and add Docker's apt signing key and repository.
3. Refresh apt metadata and install the Docker engine, CLI, containerd, and plugin packages.
4. Enable and start the Docker service.
5. Exit with code 10 after performing the installation sequence.

## Configuration
This step does not read configuration values; modify the script if you need to add repository mirrors or change package selections.
