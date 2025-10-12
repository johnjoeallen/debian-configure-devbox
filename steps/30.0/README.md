# Step 30.0 - PamMkhomedir

## Overview
Ensures home directories are created automatically at login by configuring `pam_mkhomedir`.

## Flow
1. Verify `pam_mkhomedir.so` exists; install necessary PAM packages if missing.
2. Inspect `common-session` and `common-session-noninteractive` for the required PAM lines.
3. Add or deduplicate `pam_systemd.so` and `pam_mkhomedir.so` entries as needed.
4. Backup and rewrite the files when changes are made, then exit with code 10.

## Configuration
This step has no configurable settings.
