# Jellyfin

Installs Jellyfin Server from the official repository, adds the repository key, and keeps `jellyfin.service` enabled.

## Configuration (`steps.Jellyfin`)

```yaml
steps:
  Jellyfin:
    enabled: true
    # Optional: override the release codename used in the repo line.
    release: bookworm
    # Optional: override the upstream repository URL.
    repoUrl: https://repo.jellyfin.org/debian
    # Optional: override the signing key URL.
    keyUrl: https://repo.jellyfin.org/debian/jellyfin_team.gpg.key
    # Optional: install additional packages alongside jellyfin.
    packages:
      - jellyfin
      - jellyfin-ffmpeg
```

The step ensures `/etc/apt/sources.list.d/jellyfin.list` exists, stores the key at `/usr/share/keyrings/jellyfin-archive-keyring.gpg`, runs `apt-get update` when the repo definition changes, and installs the requested packages before enabling and starting `jellyfin.service`.
