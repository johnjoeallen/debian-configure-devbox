# Step 00.0 - EnsureGroovy

## Overview
Ensures the Groovy runtime is available before any other provisioning scripts execute.

## Flow
1. Check for the `groovy` command in `PATH`.
2. Install Groovy with `apt` when it is missing.
3. Print the installed version and exit with code 10 to signal a change when installation occurs.

## Configuration
This step has no configuration knobs; it always runs when enabled.
