# Samba Shares

Installs and configures Samba for standalone share-level access with a list of
named shares.

## What it does
- Installs Samba packages.
- Writes `/etc/samba/smb.conf` with a standalone server configuration.
- Ensures defined Samba users exist in the Samba passdb.
- Validates config with `testparm`.
- Enables and restarts `smbd`/`nmbd` when configuration changes.

## Configuration (`steps.SambaShares`)
- `enabled` (bool): Toggle the step (default `false`).
- `workgroup` (string): Workgroup name (default `WORKGROUP`).
- `serverString` (string): Server string (default `Samba Server`).
- `disablePrinters` (bool): Disable printer sharing (default `true`).
- `sambaUsers` (list): Samba users to ensure in the passdb (maps with `name`, optional `password`).
- `shares` (list): Share definitions.

### Share fields
- `name` (string, required)
- `path` (string, required)
- `comment` (string, optional)
- `browseable` (bool, default `true`)
- `readOnly` (bool, default `false`)
- `validUsers` (list or comma-separated string, optional)
- `writeList` (list or comma-separated string, optional)
- `forceUser` (string, optional)
- `forceGroup` (string, optional)
- `createMask` (string, optional)
- `directoryMask` (string, optional)

## Notes
- Samba authentication uses its own passdb. If you want Samba passwords to match
  Unix passwords, set them to the same value when creating users. If no
  password is provided for a new Samba user, the step prompts interactively.
