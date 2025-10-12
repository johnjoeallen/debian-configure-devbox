# Step 50.0 - SdkmanMavenInstall

## Overview
Uses SDKMAN to install Apache Maven and set the default version requested by configuration.

## Flow
1. Check that SDKMAN is installed; exit early if it is missing.
2. Determine the desired Maven version from configuration.
3. Install the version when it is not already present.
4. Set the requested version as the SDKMAN default when needed.
5. Exit with code 10 when an installation or default switch occurs.

## Configuration
- `version`: Target Maven release to install and set as default. When omitted, the latest SDKMAN offering is installed if Maven is not already present.
