# Step 58.0 - Insomnia

## Overview
Installs the Insomnia REST client at a pinned version along with its runtime dependencies.

## Flow
1. Check the currently installed Insomnia package version.
2. Run `apt-get update` and install required library dependencies.
3. Download the release `.deb` from GitHub and install it via `apt`.
4. Remove the temporary package and exit with code 10 after installation.

## Configuration
This step uses no configuration values. Update the script to change the pinned version.
