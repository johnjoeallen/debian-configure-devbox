#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install Docker Engine from the official Docker apt repository.
// Config keys: none
// Notes: Adds Docker apt repo, installs engine components, and enables the service.

import static lib.StepUtils.sh

if (sh("which docker").code==0) System.exit(0)
def cmds = [
  'sudo apt install -y ca-certificates curl gnupg',
  'sudo install -m 0755 -d /etc/apt/keyrings',
  'curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg',
  'sudo chmod a+r /etc/apt/keyrings/docker.gpg',
  'bash -lc \'echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null\'',
  'sudo apt update',
  'sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin',
  'sudo systemctl enable --now docker'
]
for (c in cmds) { if (sh(c).code!=0) System.exit(1) }
System.exit(10)
