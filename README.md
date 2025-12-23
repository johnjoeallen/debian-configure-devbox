# debian-configure-devbox

Automation scripts for provisioning Debian-based development machines with a repeatable set of tooling.

## Getting started

1. Run the provisioner once as root to configure prerequisite system access (sudo group, baseline packages, etc).
2. Then rerun as your normal user so user-context steps can apply (SDKMAN, JetBrains Toolbox, Git prompt, etc).
3. Ensure your account can run commands with `sudo`. If not, ask an administrator to add you to the `sudo` group (or run the first pass as root and then log out/in after it adds you).
4. Install Git if it is not already present:
   ```bash
   sudo apt update
   sudo apt install -y git
   ```
5. Clone this repository and change into the project directory:
   ```bash
   git clone https://github.com/johnjoeallen/debian-configure-devbox.git
   cd debian-configure-devbox
   ```
6. Copy `config-template.yaml` to `config.yaml` or to `<hostname>.yaml` (where `<hostname>` matches `hostname -s`) and adjust values as needed.
7. Run the provisioner:
   ```bash
   sudo ./configure.sh
   ./configure.sh
   ```
   The script installs Groovy if required, then runs each step in sorted order.
5. Steps exit with `0` when no changes are needed and `10` when modifications were made. The driver script pauses on changes or errors unless `PAUSE_ON_CHANGED=0` or `PAUSE_ON_ERROR=0` are exported.

### Using profiles

You can bundle step selections into reusable profiles. Profiles live in `profiles/<name>.yaml` and look just like regular configuration files. By convention they set `steps.<stepKey>.enabled: true` so the referenced steps are active by default.

Run the provisioner with one or more profiles:

```bash
./configure.sh --profiles=server,dev
```

You can also declare profiles in your `config.yaml` or `<hostname>.yaml` by adding a top-level `profiles` list. When `--profiles` is supplied, it overrides the config-file list.

The loader merges configuration in this order (later wins):

1. Profile files (in the order specified on the command line)
2. `config.yaml`
3. `<hostname>.yaml`

If `enabled` is defined in `config.yaml` or `<hostname>.yaml`, it overrides the profile defaults.

> **Note**: `config.yaml` (or `<hostname>.yaml`) must exist even when using profiles, and every step that remains enabled needs a corresponding `steps.<stepKey>` entry in the merged configuration.

### User-step controls

Use `--root-only` to skip all `RUN_AS_USER` steps:

```bash
./configure.sh --root-only
```

You can also restrict user-context steps to a list of allowed accounts by setting `userStepUsers` in `config.yaml` or `<hostname>.yaml`:

```yaml
userStepUsers:
  - jallen
  - devuser
```

## Configuration basics

Configuration files are merged with `config.yaml` acting as defaults and an optional host-specific override file. If you prefer to manage everything per-host, you can omit `config.yaml` entirely and create `<hostname>.yaml` instead. Each step can be disabled by setting `steps.<stepKey>.enabled: false`.

## Step documentation

Step-specific READMEs live alongside the Groovy scripts inside each `steps/<order>/` directory. Refer to those files for a narrative description of what the step configures and how it runs.
