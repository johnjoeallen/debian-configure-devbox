# Debian Mirror

Provisions a local Debian mirror and publishes it through Apache.

## What it does
- Installs `apt-mirror`, Apache, and curl when missing.
- Writes `/etc/apt/mirror.list` and `/etc/apt/mirror-security.list` using the configured distributions and components.
- Drops a post-mirror hook to fetch `Contents-all.gz` indexes, creates a daily cron refresh job, and optionally performs an initial sync when repository data is absent.
- Configures an Apache virtual host that serves the mirror under `/debian` and `/debian-security` with helpful MIME types and caching headers.

## Configuration (`steps.DebianMirror`)
- `enabled` (bool): Toggle the step (default `false`).
- `siteFqdn` (string): Virtual host name for Apache. Also used by curl health checks.
- `mirrorRoot` (string): Directory that will contain the mirror tree.
- `distributions` (list): Debian releases to mirror (default `bookworm`, `trixie`).
- `components` (list): Repository components to include (default `main`, `contrib`, `non-free`, `non-free-firmware`).
- `includeUpdates` / `includeBackports` (bool): Add `-updates` and `-backports` suites for each distribution (default `true`).
- `includeContents` (bool): Mirror `Contents-*` indexes; the post-mirror hook also pulls `Contents-all.gz` (default `true`).
- `securitySuites` (list): Debian security suites to mirror (default `<distribution>-security`).
- `threads` (int): Thread count for apt-mirror downloads (default `20`).
- `runInitialSync` (bool): Run apt-mirror if the mirror tree does not yet exist (default `true`).
- `cronEnabled` (bool): Install `/etc/cron.daily/debian-mirror` to refresh daily (default `true`).
- `apacheAdmin` (string, optional): Email for `ServerAdmin` (default `webmaster@<siteFqdn>`).
- `apacheLogDir` (string, optional): Override Apache log directory (default `${APACHE_LOG_DIR}`).
- `mainAlias` / `securityAliasPath` (string, optional): Override Apache aliases if custom layout is needed.

## Client usage
See `docs/debian-mirror-sources.md` for sample `sources.list` entries targeting this mirror.
