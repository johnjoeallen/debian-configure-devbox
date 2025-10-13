#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Move Docker's data-root to the configured path and update daemon.json.
// Config keys: target (string)
// Notes: Stops Docker, copies data, rewrites config, then restarts the service.

import lib.ConfigLoader
import static lib.StepUtils.sh

def stepKey = "dockerDataRoot"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

def target = stepConfig.target?.toString()?.trim()
if (!target) {
  target = "/data/docker"
}

def need = sh("""bash -lc 'docker info 2>/dev/null | grep -q "Docker Root Dir: ${target}"'""").code!=0
if (!need) System.exit(0)

def cmds = [
  "sudo systemctl stop docker",
  "sudo install -d -m 0770 -o root -g docker '${target}'",
  "sudo rsync -aP /var/lib/docker/ '${target}/'",
  "sudo chown -R root:docker '${target}'",
  "sudo chmod -R g+rwX '${target}'",
  "sudo mv /var/lib/docker /var/lib/docker.bak.\$(date +%s)",
  "sudo install -d -m 0711 -o root -g root /etc/docker",
  """bash -lc 'cat > /etc/docker/daemon.json <<EOF
{
  "data-root": "${target}",
  "log-driver": "local",
  "log-opts": {"max-size":"100m","max-file":"3"},
  "storage-driver": "overlay2"
}
EOF'""",
  "sudo systemctl daemon-reload",
  "sudo systemctl start docker"
]
for (c in cmds) { if (sh(c).code!=0) System.exit(1) }
println sh("""bash -lc 'docker info | grep -E "Docker Root Dir|Storage Driver|Logging Driver"'""").out
System.exit(10)
