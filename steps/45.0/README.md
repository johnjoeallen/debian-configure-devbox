# Step 45.0 - DockerGroup

## Overview
Adds the invoking user and any configured accounts to the `docker` Unix group so Docker commands can run without sudo.

## Flow
1. Ensure the `docker` group exists, creating it if needed.
2. Check whether the target user is already a member of the group.
3. Add the user to the group and print a reminder to re-login when membership changes.
4. Exit with code 10 if group membership was modified.

## Configuration
This step currently reads no configuration; it operates on the invoking user.
