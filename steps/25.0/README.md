# Step 25.0 - Nsswitch

## Overview
Augments `/etc/nsswitch.conf` so NIS is consulted for account and group lookups once NIS has been configured.

## Flow
1. Confirm the `nisSetup` step is enabled and that a domain/server is configured.
2. Inspect existing `nsswitch.conf` entries for `passwd`, `group`, `shadow`, `netgroup`, and `initgroups`.
3. Append `nis` to the lookup order where needed, ensuring duplicate entries are avoided.
4. Write the updated file with a backup and restart `ypbind` and `nscd` to apply changes.
5. Exit with code 10 when file content changes.

## Configuration
This step does not use custom keys; it only checks whether `nisSetup` is enabled.
