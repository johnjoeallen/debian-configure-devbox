# NIS server setup

This step configures the host as an NIS master server. It installs the required
packages, sets the NIS domain, ensures `ypserv` is enabled, and builds the
requested maps.

## Configuration

```yaml
steps:
  NisServer:
    enabled: true
    domain: example
    maps:
      - passwd
      - group
```

If `maps` is omitted, the step builds `passwd` and `group` by default.
