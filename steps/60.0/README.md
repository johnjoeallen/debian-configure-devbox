# Step 60.0 - JetbrainsToolbox

## Overview
Installs JetBrains Toolbox so IDEs can be managed from a GUI launcher.

## Flow
1. Exit early when the Toolbox data directory already exists in the user's home.
2. Download the latest Toolbox tarball using JetBrains' product data service into a dedicated directory under `/tmp`.
3. Extract the archive within that temporary directory and launch the installer using `nohup`.
4. Remove the temporary directory on success (leaving it intact on failure for inspection) and exit with code 10 after triggering the installation.

## Configuration
No configuration options are consumed by this step.
