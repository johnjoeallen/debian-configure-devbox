# Step 10.0 - Essentials

## Overview
Installs baseline command line tools that other steps rely on.

## Flow
1. Build the list of required packages.
2. Query `dpkg` for each package and collect the missing ones.
3. Run `apt update` and `apt install` only when packages are missing.
4. Exit with code 10 when new packages are installed; otherwise exit 0.

## Configuration
This step currently reads no custom settings. Edit the script to adjust the package list if your environment needs more tools.
