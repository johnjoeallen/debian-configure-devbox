# Joker Dynamic DNS updater

This repository now ships with a Groovy-based replacement for the old shell updater. The actual configuration file stays outside version control (`/etc/joker.yaml` or similar), but `config/joker-config.sample.yaml` shows the shape of the data you should keep locally.

## Configuration

Copy the sample to `/etc/joker.yaml` (or anywhere outside the repo) and edit the `updates` list:

```yaml
ipSource: https://checkip.amazonaws.com
cacheFile: /etc/cachedexternalip
jokerEndpoint: https://svc.joker.com/nic/update
timeoutSeconds: 15
updates:
  - user: joker-user
    password: sadf78a9
    hostname: example.com
  - user: joker-user
    password: sadf78a9
    hostname: mirror.example.com
  - user: joker-user
    password: sadf78a9
    hostname: ssh.example.com
  - user: joker-user
    password: sadf78a9
    hostname: netnanny.example.com
  - user: joker-user
    password: sadf78a9
    hostname: jellyfin.example.net
```

The same placeholder values live in `config/joker-config.sample.yaml`; replace them with your actual Joker credentials/hosts when you copy the file to `/etc/joker.yaml`.

Each `updates` entry may use either `hostname` or `domain`. Setting `disabled: true` skips that entry.

## Running the updater

Install the required dependency via the normal bootstrap flow (SnakeYAML is already downloaded by `configure.sh`). Then run:

```sh
groovy -cp lib/snakeyaml-2.2.jar scripts/joker-update.groovy /etc/joker.yaml
```

Or set `JOKER_CONFIG=/etc/joker.yaml` and call `groovy scripts/joker-update.groovy`.

The script ensures the cached external IP is updated, skips Joker calls when the IP hasn't changed, and prints the response from each domain. If you want to schedule it with `cron`, point the job at this Groovy script and keep the real config in `/etc`.
