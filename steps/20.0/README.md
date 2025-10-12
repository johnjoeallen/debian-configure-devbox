# Step 20.0 - NisSetup

## Overview
Configures Network Information Service (NIS) by setting the domain and ypserver so directory lookups can leverage the network source.

## Flow
1. Load merged configuration values for `domain` and `server`.
2. Skip execution when either value is blank.
3. Install the `nis` and `nscd` packages when they are missing.
4. Set the system NIS domain, write `/etc/defaultdomain`, and update `/etc/yp.conf` with the chosen server.
5. Restart `ypbind` and `nscd` to apply changes.
6. Exit with code 10 when packages are installed or configuration changes occur.

## Configuration
- `domain`: The desired NIS domain name.
- `server`: Either a hostname/IP or full `ypserver <host>` line written to `yp.conf`.
