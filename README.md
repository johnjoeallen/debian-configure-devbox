# debian-configure-devbox

Automation scripts for provisioning Debian-based development machines with a repeatable set of tooling.

## Getting started

1. Ensure your account can run commands with `sudo`. If not, ask an administrator to add you to the `sudo` group (or run the provisioner from an account that already has elevated privileges).
2. Install Git if it is not already present:
   ```bash
   sudo apt update
   sudo apt install -y git
   ```
3. Clone this repository and change into the project directory:
   ```bash
   git clone https://github.com/johnjoeallen/debian-configure-devbox.git
   cd debian-configure-devbox
   ```
4. Copy `config-template.yaml` to `config.yaml` or to `<hostname>.yaml` (where `<hostname>` matches `hostname -s`) and adjust values as needed.
5. Run the provisioner:
   ```bash
   ./configure.sh
   ```
   The script installs Groovy if required, then runs each step in sorted order.
5. Steps exit with `0` when no changes are needed and `10` when modifications were made. The driver script pauses on changes or errors unless `PAUSE_ON_CHANGED=0` or `PAUSE_ON_ERROR=0` are exported.

## Configuration basics

Configuration files are merged with `config.yaml` acting as defaults and an optional host-specific override file. If you prefer to manage everything per-host, you can omit `config.yaml` entirely and create `<hostname>.yaml` instead. Each step can be disabled by setting `steps.<stepKey>.enabled: false`.

## Step documentation

Step-specific READMEs live alongside the Groovy scripts inside each `steps/<order>/` directory. Refer to those files for a narrative description of what the step configures and how it runs.
