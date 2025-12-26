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
          accessLog: /var/log/apache2/jellyfin.dublinux.net-access.log
          errorLog: /var/log/apache2/jellyfin.dublinux.net-error.log
        - host: internal.jellyfin.lan
          https: false
          certbot: false
          accessLog: /var/log/apache2/internal.jellyfin.lan-access.log
          errorLog: /var/log/apache2/internal.jellyfin.lan-error.log
      certbotEnabled: true
      certbotEmail: admin@dublinux.net
      certbotStaging: false
      certbotExtraArgs:
        - "--force-renewal"
```

The step ensures `/etc/apt/sources.list.d/jellyfin.list` exists, stores the key at `/usr/share/keyrings/jellyfin-archive-keyring.gpg`, runs `apt-get update` when the repo definition changes, and installs the requested packages before enabling and starting `jellyfin.service`.

When `apacheProxy.enabled` is true, the step installs Apache, enables the required modules, writes a separate HTTP vhost for each domain, enables the site, reloads Apache, and optionally lets `certbot --apache` obtain certificates for the domains that set `certbot: true`. Entries that also specify `redirect: true` pass `--redirect` to certbot so the challenge-free HTTPS config is generated automatically; you can still give each host its own `accessLog`/`errorLog` path. Set `certbotEnabled: false` in the step config to skip certbot entirely (useful for lan hosts or when the CA can't reach the server). Use `certbotEmail`, `certbotStaging`, and `certbotExtraArgs` in the step config to tweak the certbot invocation if you do enable it.
