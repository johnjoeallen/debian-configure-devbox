# Step 15.0 - Ntp

## Overview
Makes sure systemd-timesyncd is installed and actively synchronizing time.

## Flow
1. Verify the `systemd-timesyncd` package is installed; install it if missing.
2. Enable and start the service when it is not already active.
3. Exit with code 10 if a package is installed or the service state changes; otherwise exit 0.

## Configuration
This step has no custom options.
