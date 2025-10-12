# Step 48.0 - SdkmanInstall

## Overview
Installs SDKMAN in the provisioning user's home directory so later steps can manage language runtimes.

## Flow
1. Verify the `HOME` environment variable and target `.sdkman` directory.
2. Exit when SDKMAN already exists.
3. Run the non-interactive install script and write default configuration settings.
4. Exit with code 10 after installation or configuration changes.

## Configuration
This step does not accept configuration values.
