#!/usr/bin/env groovy
// RUN_VIA_SUDO
// --- Documentation ---
// Summary: Move Docker's data-root to the configured path and update daemon.json.
// Config keys: target (string)
// Notes: Stops Docker, copies data, rewrites config, then restarts the service.

import lib.ConfigLoader
import static lib.StepUtils.sh

def stepKey = "DockerDataRoot"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

def target = stepConfig.target?.toString()?.trim()
if (!target) {
  target = "/data/docker"
}

def normalizePath = { String path ->
  if (!path) {
    return null
  }
  def trimmed = path.trim()
  if (!trimmed) {
    return null
  }
  if (trimmed == "/") {
    return "/"
  }
  return trimmed.replaceAll(/\/+$/, '')
}

def dockerRootResult = sh("docker info --format '{{ .DockerRootDir }}' 2>/dev/null || true")
def currentRoot = dockerRootResult.out?.trim()
def normalizedTarget = normalizePath(target)
def normalizedCurrent = normalizePath(currentRoot)
boolean needMove = normalizedCurrent == null || normalizedTarget == null || normalizedCurrent != normalizedTarget
boolean changed = false

if (needMove) {
  def cmds = [
    "systemctl stop docker",
    "install -d -m 0770 -o root -g docker '${target}'",
    "rsync -aP /var/lib/docker/ '${target}/'",
    "chown -R root:docker '${target}'",
    "chmod -R g+rwX '${target}'",
    "mv /var/lib/docker /var/lib/docker.bak.\$(date +%s)",
    "install -d -m 0711 -o root -g root /etc/docker",
    """bash -lc 'cat > /etc/docker/daemon.json <<EOF
{
  "data-root": "${target}",
  "log-driver": "local",
  "log-opts": {"max-size":"100m","max-file":"3"},
  "storage-driver": "overlay2"
}
EOF'""",
    "systemctl daemon-reload",
    "systemctl start docker"
  ]
  for (c in cmds) {
    if (sh(c).code != 0) {
      System.exit(1)
    }
  }
  println sh("""bash -lc 'docker info | grep -E "Docker Root Dir|Storage Driver|Logging Driver"'""").out
  changed = true
  currentRoot = target
  normalizedCurrent = normalizePath(currentRoot)
}

def pathToVerify = normalizedCurrent ? currentRoot : target
def desiredMode = "770"
if (pathToVerify) {
  def statRes = sh("stat -c '%U:%G %a' '${pathToVerify}' 2>/dev/null || true")
  boolean ownerMatches = false
  boolean modeMatches = false
  if (statRes.code == 0) {
    def tokens = statRes.out?.trim()?.split(/\s+/)
    if (tokens?.size() >= 1) {
      ownerMatches = tokens[0] == "root:docker"
    }
    if (tokens?.size() >= 2) {
      modeMatches = tokens[1] == desiredMode
    }
  }
  if (!ownerMatches || !modeMatches) {
    def installRes = sh("install -d -m 0770 -o root -g docker '${pathToVerify}'")
    if (installRes.code != 0) {
      System.exit(1)
    }
    changed = true
    println "Adjusted ownership for ${pathToVerify} to root:docker"
  }
} else {
  System.err.println("Unable to determine Docker data directory for ownership check")
}

System.exit(changed ? 10 : 0)
