# Jellyfin

Installs Jellyfin Server from the official repository, adds the repository key, and keeps `jellyfin.service` enabled.

## Configuration (`steps.Jellyfin`)

```yaml
steps:
  Jellyfin:
    enabled: true
    release: bookworm
    packages:
      - jellyfin
      - jellyfin-ffmpeg
    apacheProxy:
      enabled: true
      documentRoot: /var/www/hosts/jellyfin
      backendHost: 10.0.0.1
      backendPort: 8096
      websocketPath: /socket
      siteName: jellyfin.dublinux.net
      accessLog: /var/log/apache2/jellyfin.dublinux.net-access.log
      errorLog: /var/log/apache2/jellyfin.dublinux.net-error.log
      domains:
        - host: jellyfin.dublinux.net
          https: true
          certbot: true
          redirect: true
        - host: internal.jellyfin.lan
          https: false
          certbot: false
      certbot:
        enabled: true
        email: admin@dublinux.net
        domains:
          - jellyfin.dublinux.net
        staging: false
        extraArgs:
          - "--force-renewal"
```

The step ensures `/etc/apt/sources.list.d/jellyfin.list` exists, stores the key at `/usr/share/keyrings/jellyfin-archive-keyring.gpg`, runs `apt-get update` when the repo definition changes, and installs the requested packages before enabling and starting `jellyfin.service`.

When `apacheProxy.enabled` is true, the step installs Apache, enables the required modules, writes the HTTP vhost, enables the site, reloads Apache, and optionally requests a certificate with `certbot` using the webroot plugin. Certificates always cover every `domains.host` that declares `certbot: true`; HTTP requests to a domain that sets `redirect: true` are redirected to HTTPS.
