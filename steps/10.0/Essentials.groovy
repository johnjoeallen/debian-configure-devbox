#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install baseline CLI packages required for provisioning.
// Config keys: none
// Notes: Uses apt to install any missing packages only.

import static lib.StepUtils.sh

def pkgs = ["curl","wget","zip","unzip","rsync","ca-certificates","gnupg","apt-transport-https"]
def need = pkgs.findAll { sh("dpkg -s ${it} >/dev/null 2>&1").code!=0 }
if (need) {
  def r = sh("sudo apt update && sudo apt install -y " + need.join(" "))
  if (r.code!=0) { System.err.println(r.err); System.exit(1) }
  System.exit(10)
}
System.exit(0)
