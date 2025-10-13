# Step 45.0 - DockerGroup

## Overview
Adds configured accounts to the `docker` Unix group so Docker commands can run without sudo.

## Flow
1. Ensure the `docker` group exists, creating it if needed.
2. Determine the desired user list from configuration and the invoking account (when `ensureUser` is true).
3. Check whether each user is already a member of the group.
4. Add missing members and print a reminder to re-login when membership changes.
5. Exit with code 10 if group membership was modified.

## Configuration
- `ensureUser` (boolean, default `true`): include the invoking account.
- `users` (list of strings): additional usernames to add to the group.
