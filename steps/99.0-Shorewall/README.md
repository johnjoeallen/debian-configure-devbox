# Shorewall gateway firewall

This step installs Shorewall, enables the service, writes the requested Shorewall tables, and applies a sysctl snippet that enables IPv4 forwarding and (optionally) `rp_filter`. All table contents are driven by your YAML configuration, but you can omit any of the `zones`/`interfaces`/`policy`/`masq`/`rules` sections: the step injects sane defaults (the sample entries below) whenever a section is missing. Override only the slices you need and the rest stay on the defaults.

## Step configuration (`steps.Shorewall`)

```yaml
steps:
  Shorewall:
    enabled: true
    sysctlFile: /etc/sysctl.d/98-shorewall.conf
    enableRpFilter: true
    sysctl:
      net.ipv4.ip_forward: 1
      net.ipv4.conf.all.send_redirects: 0
    zonesFile: /etc/shorewall/zones
    interfacesFile: /etc/shorewall/interfaces
    policyFile: /etc/shorewall/policy
    masqFile: /etc/shorewall/masq
    rulesFile: /etc/shorewall/rules
    zones:
      - zone: loc
        type: ipv4
      - zone: net
        type: ipv4
      - zone: fw
        type: firewall
    interfaces:
      - interface: enp3s0
        zone: loc
      - interface: enp6s0
        zone: net
    policy:
      - source: loc
        dest: net
        policy: ACCEPT
      - source: loc
        dest: fw
        policy: ACCEPT
      - source: fw
        dest: loc
        policy: ACCEPT
      - source: fw
        dest: net
        policy: ACCEPT
      - source: net
        dest: fw
        policy: DROP
      - source: net
        dest: loc
        policy: DROP
    masq:
      - interface: enp6s0
        source: 10.0.0.0/24
      - interface: enp6s0
        source: 172.17.0.0/16
    rules:
      - action: ACCEPT
        source: net
        dest: fw
        proto: tcp
        port: 80
        comment: Allow HTTP to the gateway
      - action: ACCEPT
        source: net
        dest: fw
        proto: tcp
        port: 443
        comment: Allow HTTPS to the gateway
```

### Entry structure

- `zones`, `interfaces`, `policy`, `masq`, and `rules` accept a list of maps. Each map can either include a `line` string that is written verbatim or specific fields mentioned above. Use `disabled: true` to skip a line without removing it from the YAML.
- `zones` must provide `zone` and optionally `type`, `interfaces`, `options`, and `comment`.
- `interfaces` entries require `zone` and `interface`.
- `policy` entries require `source`, `dest`, and `policy`; add `logLevel` or `options` as needed.
- `masq` entries require `interface` (egress) and `source`; `address/dest` and `opts/options` are optional.
- `rules` entries require `action`, `source`, `dest`, and `port(s)` (action defaults to `ACCEPT`). `proto` defaults to `tcp`, and `options` allow extra parameters. Use `dest` to clarify the Shorewall DEST column.
- `sysctl` allows you to override the generated sysctl keys; the defaults enable forwarding, disable send_redirects, and optionally enable `rp_filter`.

The step installs Shorewall via `apt`, enables `shorewall.service`, writes each table to the configured file path (only when entries exist), and runs `sysctl --system` before reloading Shorewall. If a file’s contents changed, the step runs `shorewall check` and `systemctl reload shorewall`.
