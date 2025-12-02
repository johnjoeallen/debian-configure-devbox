# Step 05.0 - AdminGroupNopass

## Overview
Creates a passwordless sudo group and adds specified users so privileged commands can run without prompting for credentials.

## Flow
1. Determine the target admin group (default `admin`) from configuration.
2. Ensure the group exists and required sudoers drop-ins are present.
3. Remove legacy sudoers files that conflict with the desired policy.
4. Add configured users along with the invoking user (when enabled) to the admin group.
5. Validate sudoers syntax before exiting and report changes via exit code 10.

## Configuration
- `adminGroup`: Name of the Unix group to grant passwordless sudo.
- `addCurrentUser`: When true, add the user running the provisioner to the group.
- `users`: List of additional user accounts to include.
