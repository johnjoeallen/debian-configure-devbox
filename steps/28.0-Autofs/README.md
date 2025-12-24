# Autofs

Installs and configures `autofs` to mount remote filesystems on demand. The step writes a fragment under `/etc/auto.master.d` along with per-map files, then enables and restarts the service when changes are detected.

## Configuration

```yaml
steps:
  Autofs:
    enabled: true
    # Optional; defaults to /etc/auto.master.d/devbox.autofs
    masterFile: /etc/auto.master.d/devbox.autofs
    maps:
      /mnt/auto:
        # Optional; defaults to /etc/auto.<name> where name derives from mount or name.
        mapFile: /etc/auto.devbox
        # Options applied on the master line, e.g. --timeout, --ghost.
        options: "--timeout=300 --ghost"
        entries:
          data:
            options: "-fstype=nfs,rw"
            location: "nas.lan:/exports/data"
          tools: "nas.lan:/exports/tools"
      /home:
        name: home
        options: "--timeout=120"
        entries:
          "*":
            options: "-fstype=nfs,rw"
            location: "nas.lan:/exports/home/&"
```

Each map entry can also be provided as a raw string value (the right-hand side of the map) or as a list of raw string lines if you need full control over the autofs syntax.
