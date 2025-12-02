# Step 80.0 - ExtendSuspend

## Overview
Extends the system idle suspend timeout by managing a systemd-logind drop-in configuration file.

## Flow
1. Read `idleAction` and duration settings from merged configuration.
2. Parse the configured timeout as an ISO-8601 duration (falling back to seconds when needed).
3. Write `/etc/systemd/logind.conf.d/50-devbox-idle.conf` with the desired action and timeout when content changes.
4. Signal `systemd-logind` with `SIGHUP` to reload settings immediately.
5. Exit with code 10 once configuration is updated; exit 0 if already compliant.

## Configuration
- `idleAction`: Logind idle action to apply (for example `suspend`).
- `timeout`: Idle timeout in ISO-8601 duration format such as `PT1H`.
- `timeoutSeconds`: Legacy numeric timeout value used when `timeout` is not provided.
