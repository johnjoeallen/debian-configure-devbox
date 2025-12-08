# Step 70.0 - GitPrompt

## Overview
Creates a standalone Git-aware prompt script in the user's home directory and ensures `.bashrc` sources it so interactive shells show repository status.

## Flow
1. Expand configured paths for the prompt file and `.bashrc`, defaulting to `~/.git-prompt.sh` and `~/.bashrc`.
2. Write the provided Git prompt snippet to the prompt file when it is missing or outdated.
3. Append a guarded source block to `.bashrc` when it does not already reference the prompt file.
4. Exit with code 10 when changes are made; otherwise exit 0.

## Configuration
- `promptFile`: Optional path for the prompt snippet file (default `~/.git-prompt.sh`).
- `bashrcPath`: Optional path to the `.bashrc` file to update (default `~/.bashrc`).
- `enabled`: Disable the step when set to `false`.
