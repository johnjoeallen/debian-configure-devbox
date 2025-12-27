# Joker Dynamic DNS (Joker)

This step keeps Joker-managed hostnames in sync with the system’s current public IP by writing `joker-dns-update` (a Groovy helper) and a cron entry. The cron job calls `steps/97.0-JokerDns/Update.groovy` every minute (or whatever you configure) to read `/etc/joker.yaml` and fire the Joker NIC update API.

## Sample configuration

Copy `steps/97.0-JokerDns/joker-config.sample.yaml` to `/etc/joker.yaml` (or another path you pass to `steps.JokerDns.configFile`) and edit the credentials:

```yaml
jokerEndpoint: https://svc.joker.com/nic/update
timeoutSeconds: 15
user: joker-user-xxxx
password: placeholder-password
hosts:
  - dublinux.net
  - mirror.dublinux.net
  - ssh.dublinux.net
  - netnanny.dublinux.net
  - jellyfin.dublinux.net
```

Each entry under `hosts` may be a plain hostname (strings are trimmed and skipped if empty) or an object:

```yaml
hosts:
  - hostname: mirror.example.com
    disabled: true
  - hostname: special.example.com
    user: special-user
    password: override-secret
```

`disabled: true` keeps an entry in the list without calling Joker, while host-level credentials override the global `user` and `password`.

## Update script

The cron job pattern (`cronSchedule`) defaults to `* * * * *` so updates occur every minute. The helper script respects the `JOKER_CONFIG` environment variable (or the first argument) so you can run it manually against different files.

`steps/97.0-JokerDns/Update.groovy` loads the YAML, skips empty hosts, and issues `https://…/nic/update?hostname=…` requests for every enabled entry. The response (`good`, `nochg`, `badauth`, etc.) is printed so you can troubleshoot failures in `/var/log/joker-dns.log`.

## Step configuration (`steps.JokerDns`)

```yaml
steps:
  JokerDns:
    enabled: true
    configFile: /etc/joker.yaml                 # where your YAML lives
    scriptPath: /usr/local/bin/joker-dns-update  # helper installed by this step
    cronPath: /etc/cron.d/joker-dns
    cronSchedule: "* * * * *"                    # adjust if needed
    cronLogFile: /var/log/joker-dns.log
    cronDisabled: false
    snakeyamlVersion: 2.2                        # override if you want a different jar
```

The step never reads `/etc/joker.yaml` itself; it only ensures the runner script and cron entry point at `Update.groovy`. If you disable the cron (`cronDisabled: true`) the script is still written so you can run it manually or from a different scheduler.
