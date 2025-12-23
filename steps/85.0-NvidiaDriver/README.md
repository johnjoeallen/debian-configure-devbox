# NVIDIA Driver Install

Installs NVIDIA drivers on Debian 13 using a fully Groovy-based runbook step.

## What it does
- Validates Debian 13 and APT components.
- Ensures kernel headers, DKMS, and NVIDIA packages are installed.
- Handles Secure Boot prompts, DKMS verification, and Nouveau blacklisting.
- Rebuilds initramfs when changes are made and prints the post-reboot checklist.
- Requires sudo (do not run this step as root directly).

## Configuration (`steps.NvidiaDriverInstall`)
- `enabled` (bool): Toggle the step (default `false`).

## Runbook
See `infrastructure/docs/nvidia-driver-debian-13.md` for the full procedure and troubleshooting notes.
