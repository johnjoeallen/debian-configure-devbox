#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Ensure systemd-timesyncd is installed and enabled for NTP synchronization.
// Config keys: none
// Notes: Installs the package if missing and enables the service immediately.

import static lib.StepUtils.sh

def changed=false
if (sh("dpkg -s systemd-timesyncd >/dev/null 2>&1").code!=0) {
  if (sh("sudo apt install -y systemd-timesyncd").code!=0) System.exit(1)
  changed=true
}
if (sh("systemctl is-enabled systemd-timesyncd || true").out != "enabled") {
  if (sh("sudo systemctl enable --now systemd-timesyncd").code!=0) System.exit(1)
  changed=true
}
System.exit(changed?10:0)
