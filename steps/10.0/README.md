# Step 10.0 - Essentials

## Overview
Installs baseline command line tools that other steps rely on.

## Flow
1. Build the list of required packages from configuration (falling back to defaults when not provided).
2. Query `dpkg` for each package and collect the missing ones.
3. Run `apt update` and `apt install` only when packages are missing.
4. Exit with code 10 when new packages are installed; otherwise exit 0.

## Configuration
- `packages`: Optional list of package names to ensure are installed. When omitted, the step installs the default baseline toolchain.
