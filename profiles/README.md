# Profiles

Profiles bundle step settings so you can quickly target common machine roles. Each file here is merged before `config.yaml` and `<hostname>.yaml`.

Usage example:

```bash
./configure.sh --profiles=server,dev
```

Add new profiles by creating additional YAML files in this directory.
