# Joker Dynamic DNS (Joker)

This step keeps Joker-managed hostnames in sync with the system’s current public IP. It runs the Groovy updater (`Configure.groovy`) and expects a YAML configuration file with credentials and hostnames. Keep the real `/etc/joker.yaml` (or whichever path you set via `configFile`) outside the repository; the sample `joker-config.sample.yaml` shows the format.

## Sample configuration

Copy `steps/97.0-JokerDns/joker-config.sample.yaml` to `/etc/joker.yaml` (or another location you specify via `steps.JokerDns.configFile`) and edit the `updates` block:

```yaml
ipSource: https://checkip.amazonaws.com
cacheFile: /etc/cachedexternalip
jokerEndpoint: https://svc.joker.com/nic/update
timeoutSeconds: 15
updates:
  - user: joker-user
    password: placeholder
    hostname: example.com
  - user: joker-user
    password: placeholder
    hostname: mirror.example.com
```

Each `updates` entry may use either `hostname` or `domain`. Set `disabled: true` to skip an entry without deleting it.

## Step configuration (`steps.JokerDns`)

```yaml
steps:
  JokerDns:
    enabled: true
    configFile: /etc/joker.yaml
```

Other overrides such as `ipSource`, `cacheFile`, `jokerEndpoint`, and `timeoutSeconds` can be defined in this step entry if you prefer not to store them in the external YAML file.
