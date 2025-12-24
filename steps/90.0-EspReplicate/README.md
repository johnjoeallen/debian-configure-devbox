# ESP Replication and GRUB Install

Replicates the EFI System Partition (ESP) from the currently mounted `/boot/efi`
to matching ESP partitions on sibling disks, then installs GRUB to each ESP.

## What it does
- Verifies UEFI boot mode and the source ESP at `/boot/efi`.
- Finds disks with an identical partition layout to the source disk, then
  selects the matching ESP partition on each disk by:
  - the same partition number
  - the same size in bytes
  - the EFI System Partition GUID
- Refuses to touch mounted target partitions.
- Prompts for approval before cloning or installing.
- Clones the ESP with `dd`, installs GRUB to each target, runs `update-grub`, and
  installs the fallback `EFI/BOOT/BOOTX64.EFI`.
- Reorders EFI BootOrder so managed GRUB entries (matching `bootloaderId`) are
  ordered by disk name (sda, sdb, ...).

## Configuration (`steps.EspReplicate`)
- `enabled` (bool or string): `true`, `false`, or `info` (show plan only). Default `false`.
- `bootloaderId` (string): GRUB bootloader ID (default `debian`).
- `autoApprove` (bool): Skip interactive approval prompt (default `false`).
