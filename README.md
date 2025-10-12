# debian-configure-devbox

Automation scripts for provisioning Debian-based development machines with a repeatable set of tooling.

## Running the provisioner

1. Copy `config-template.yaml` to `config.yaml` or to `<hostname>.yaml` (where `<hostname>` matches `hostname -s`) and adjust values as needed.
2. Execute `./configure.sh` from the repository root. The script installs Groovy if required, then runs each step in sorted order.
3. Steps exit with `0` when no changes are needed and `10` when modifications were made. The driver script pauses on changes or errors unless `PAUSE_ON_CHANGED=0` or `PAUSE_ON_ERROR=0` are exported.

## Configuration basics

Configuration files are merged with `config.yaml` acting as defaults and an optional host-specific override file. If you prefer to manage everything per-host, you can omit `config.yaml` entirely and create `<hostname>.yaml` instead. Each step can be disabled by setting `steps.<stepKey>.enabled: false`.

## Step documentation

Step-specific READMEs live alongside the Groovy scripts inside each `steps/<order>/` directory. Refer to those files for a narrative description of what the step configures and how it runs.
