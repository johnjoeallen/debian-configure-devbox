# Shorewall firewall rules

This step keeps Shorewall installed, ensures the firewall service is enabled, and adds explicit `ACCEPT net fw tcp <port>` rules so HTTP/HTTPS traffic can reach the machine.

## Configuration (`steps.Shorewall`)

By default the step adds entries for ports 80 and 443. Customize via `steps.Shorewall.rules`:

```yaml
steps:
  Shorewall:
    enabled: true
    rulesFile: /etc/shorewall/rules
    rules:
      - port: 80
        comment: Allow HTTP
      - port: 443
        comment: Allow HTTPS
```
Set `allowIncomingHttp: false` in the step config when you only want the firewall glue without automatically opening 80/443; otherwise the defaults ensure those ports are permitted on the WAN.

Each rule can override `source`/`target`/`proto`, append `options`, or set `disabled: true` to skip it. If you need a different rules file (e.g. `/etc/shorewall/rules.local`), set `rulesFile`.

The step installs `shorewall`, enables the service, appends any missing lines at the end of the specified rules file, runs `shorewall check`, and reloads the service when it added new lines.
