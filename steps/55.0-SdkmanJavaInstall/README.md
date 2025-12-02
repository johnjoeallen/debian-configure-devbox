# Step 55.0 - SdkmanJavaInstall

## Overview
Installs one or more Java runtimes via SDKMAN and sets the configured default version.

## Flow
1. Ensure SDKMAN is present; skip execution otherwise.
2. Collect desired Java version identifiers from configuration.
3. Install each version that is not already available.
4. Set the configured default Java version using `sdk default` when needed.
5. Exit with code 10 whenever installations or default changes occur.

## Configuration
- `javaVersions`: List of Java identifiers to ensure are installed.
- `javaVersion`: Legacy single version value accepted for compatibility.
- `defaultJava`: Identifier to set as the SDKMAN default JVM.
