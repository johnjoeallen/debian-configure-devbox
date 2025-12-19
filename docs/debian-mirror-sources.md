# Debian Mirror Usage

Point client machines at the Apache-served mirror once the `steps.DebianMirror` provisioning step has completed successfully. Substitute the host name below if you customized `siteFqdn` in the configuration.

## Bookworm (Debian 12)

Add the following entries to `/etc/apt/sources.list`:

```
deb http://mirror.dublinux.lan/debian bookworm main contrib non-free non-free-firmware
deb http://mirror.dublinux.lan/debian bookworm-updates main contrib non-free non-free-firmware
deb http://mirror.dublinux.lan/debian-security bookworm-security main contrib non-free non-free-firmware
# Optional source repositories
# deb-src http://mirror.dublinux.lan/debian bookworm main contrib non-free non-free-firmware
# deb-src http://mirror.dublinux.lan/debian bookworm-updates main contrib non-free non-free-firmware
# deb-src http://mirror.dublinux.lan/debian-security bookworm-security main contrib non-free non-free-firmware
```

## Trixie (Debian 13)

Add the following entries to `/etc/apt/sources.list`:

```
deb http://mirror.dublinux.lan/debian trixie main contrib non-free non-free-firmware
deb http://mirror.dublinux.lan/debian trixie-updates main contrib non-free non-free-firmware
deb http://mirror.dublinux.lan/debian-security trixie-security main contrib non-free non-free-firmware
# Optional source repositories
# deb-src http://mirror.dublinux.lan/debian trixie main contrib non-free non-free-firmware
# deb-src http://mirror.dublinux.lan/debian trixie-updates main contrib non-free non-free-firmware
# deb-src http://mirror.dublinux.lan/debian-security trixie-security main contrib non-free non-free-firmware
```

After updating the file, run `sudo apt update`.
