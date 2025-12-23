# NVIDIA Driver Installation - Debian 13 (Trixie)

This runbook describes the correct, reproducible, Debian-native procedure for
installing NVIDIA drivers on Debian 13 systems with DKMS, replicated `/boot`,
and optional Secure Boot. It is safe for Xorg or headless systems.

It explicitly addresses common failure modes:
- Missing `contrib` / `non-free` / `non-free-firmware`
- Missing kernel headers
- DKMS built but not installed
- Secure Boot blocking modules
- Nouveau conflicts
- Xorg failing with `Connection refused`

## Supported scope
- Debian GNU/Linux 13 (Trixie)
- Kernel `6.12+deb13`
- NVIDIA GPUs (Maxwell through Ada)
- BIOS or UEFI
- Secure Boot on or off

## Repository requirements

NVIDIA drivers require all of these components:
- `contrib`
- `non-free`
- `non-free-firmware`

Example deb822 components stanza:

```text
Components: main contrib non-free non-free-firmware
```

If `non-free` is missing, `nvidia-driver-full` will not be available.

## Correct installation order (important)

1. Ensure repositories are correct.
2. Install matching kernel headers.
3. Install NVIDIA driver + DKMS.
4. Resolve Secure Boot (if enabled).
5. Reboot and validate.
6. Replicate `/boot` only after a successful boot.

Never replicate `/boot` before the system successfully boots with NVIDIA.

## Validation checklist

After install and reboot:

```bash
lsmod | grep nvidia
nvidia-smi
dkms status
```

Expected:
- `nvidia`, `nvidia_drm`, `nvidia_modeset` loaded
- `nvidia-smi` shows the GPU
- DKMS reports installed

## Common failures and meaning

| Symptom | Likely cause |
| --- | --- |
| `nvidia-driver-full has no installation candidate` | `non-free` missing |
| `lsmod | grep nvidia` empty | driver not loaded |
| Xorg `Connection refused` | GPU driver not loaded |
| `module verification failed` | Secure Boot blocking module |
| `nouveau` loaded | blacklist missing |

## Secure Boot notes

If Secure Boot is enabled:
- NVIDIA DKMS modules must be signed.
- Debian will prompt for a MOK; enroll it at the next boot.
- You can re-trigger the prompt with:

```bash
dpkg-reconfigure nvidia-kernel-dkms
```

## Files modified by NVIDIA install

- `/boot/initrd.img-*` (replicate after validation)
- `/lib/modules/*/updates/dkms/*`
- `/etc/modprobe.d/*`
- `/usr/lib/x86_64-linux-gnu/nvidia/*`

EFI is not modified unless Secure Boot keys are enrolled.

## Final rule

Only replicate `/boot` after:
- NVIDIA loads
- System boots cleanly
- `nvidia-smi` works

This prevents copying a broken initramfs to all disks.

## Groovy step

Enable and run the provisioning step instead of a shell script:

1. Set `steps.NvidiaDriverInstall.enabled: true` in `config.yaml` or `<hostname>.yaml`.
2. Run `./configure.sh` as a sudo-capable user (do not invoke it as root directly):

```bash
./configure.sh
```
