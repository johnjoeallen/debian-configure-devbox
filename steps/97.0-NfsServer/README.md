# NfsServer

Provides an `nfs-kernel-server` configuration driven by declarative exports.

## Configuration (`steps.NfsServer`)

```yaml
steps:
  NfsServer:
    enabled: true
    defaultOptions: rw,sync,no_subtree_check,no_root_squash
    exports:
      - path: /data
        clients:
          - tornado
        options: rw,sync,no_subtree_check,no_root_squash
      - path: /home
        clients: 10.0.0.0/16
        options: rw,sync,no_root_squash
```

- `exports` (list): Each entry must provide `path` and `clients`. The step ensures the directory exists, writes the combined `/etc/exports`, reloads the export table, and enables `nfs-server`.
- `defaultOptions` (string): Applied when an export entry omits `options` (default: `rw,sync,no_subtree_check,no_root_squash`).
