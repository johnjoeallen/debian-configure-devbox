#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Configure this host as an NIS master server.
// Config keys: domain (string), maps (list)
// Notes: Installs required packages, configures domain, builds maps, and enables ypserv.

import lib.ConfigLoader
import static lib.StepUtils.backup
import static lib.StepUtils.sh
import static lib.StepUtils.writeText

def stepKey = "NisServer"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration ⏭️"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

def isBlank = { v -> v == null || v.toString().trim().isEmpty() }

def desiredDomain = stepConfig.domain?.toString()?.trim()
if (isBlank(desiredDomain)) {
  println "NIS server configuration missing domain in merged configuration. Skipping. ⚠️"
  System.exit(0)
}

def maps = []
if (stepConfig.maps instanceof Collection) {
  maps = stepConfig.maps.collect { it?.toString()?.trim() }.findAll { it }
}
if (maps.isEmpty()) {
  maps = ["passwd", "group"]
}

def runOrFail = { String cmd, String context ->
  def res = sh(cmd)
  if (res.code != 0) {
    System.err.println("${context} failed")
    if (res.out) System.err.println(res.out)
    if (res.err) System.err.println(res.err)
    System.exit(1)
  }
  res
}

def changed = false

def requiredPkgs = ["nis", "rpcbind"]
def missingPkgs = requiredPkgs.findAll { name ->
  sh("dpkg -s ${name} >/dev/null 2>&1").code != 0
}
if (missingPkgs) {
  runOrFail("apt-get update -y", "apt-get update")
  runOrFail("DEBIAN_FRONTEND=noninteractive apt-get install -y ${missingPkgs.join(' ')}", "apt-get install ${missingPkgs.join(' ')}")
  changed = true
}

def hostname = sh("hostname -f || hostname").out?.trim()

def curDom = sh("domainname || true").out
def normalizedCurDom = curDom?.trim()
if (normalizedCurDom?.equalsIgnoreCase("(none)") || normalizedCurDom?.equalsIgnoreCase("localdomain")) {
  normalizedCurDom = ""
}
boolean domainMatches = normalizedCurDom != null && !normalizedCurDom.isEmpty() && normalizedCurDom.equalsIgnoreCase(desiredDomain)
if (!domainMatches) {
  runOrFail("domainname ${desiredDomain}", "set domainname")
  changed = true
}

def defaultdomain = new File("/etc/defaultdomain")
if (!defaultdomain.exists() || !defaultdomain.text.trim().equalsIgnoreCase(desiredDomain)) {
  backup("/etc/defaultdomain")
  writeText("/etc/defaultdomain", desiredDomain + "\n")
  changed = true
}

def defaultNis = new File("/etc/default/nis")
if (defaultNis.exists()) {
  def lines = defaultNis.readLines()
  def updated = false
  def ensureSetting = { key, value ->
    def prefix = "${key}="
    def idx = lines.findIndexOf { it.startsWith(prefix) }
    def desired = "${key}=${value}"
    if (idx >= 0) {
      if (lines[idx] != desired) {
        lines[idx] = desired
        updated = true
      }
    } else {
      lines << desired
      updated = true
    }
  }
  ensureSetting("NISSERVER", "master")
  if (updated) {
    backup("/etc/default/nis")
    defaultNis.text = lines.join('\n') + '\n'
    changed = true
  }
}

def domainDir = new File("/var/yp/${desiredDomain}")
if (!domainDir.exists() && hostname) {
  def ypinitCmd = "bash -lc \"printf '%s\\n\\n' '${hostname}' | /usr/lib/yp/ypinit -m\""
  runOrFail(ypinitCmd, "initialize NIS domain")
  changed = true
}

if (hostname) {
  def ypservers = new File("/var/yp/ypservers")
  if (!ypservers.exists() || !ypservers.text.readLines().any { it.trim() == hostname }) {
    def lines = ypservers.exists() ? ypservers.readLines() : []
    lines << hostname
    backup("/var/yp/ypservers")
    ypservers.text = lines.join('\n') + '\n'
    changed = true
  }
}

def mapTargets = maps.collect { it.replaceAll("[^a-zA-Z0-9_]", "") }.findAll { it }
def makeCmd = "make -C /var/yp " + mapTargets.join(' ')
runOrFail(makeCmd, "build NIS maps (${mapTargets.join(', ')})")

def ensureEnabled = { String service ->
  def status = sh("systemctl is-enabled ${service} 2>/dev/null")
  def normalized = status.out?.trim()?.toLowerCase()
  def acceptable = ['enabled', 'static', 'linked', 'alias', 'enabled-runtime']
  if (status.code != 0 || !(normalized in acceptable)) {
    runOrFail("systemctl enable ${service}", "enable ${service}")
    changed = true
  }
}

ensureEnabled("rpcbind")
ensureEnabled("ypserv")

def needsRpcbindRestart = changed || sh("systemctl is-active --quiet rpcbind").code != 0
if (needsRpcbindRestart) {
  runOrFail("systemctl restart rpcbind", "restart rpcbind")
}
def needsYpservRestart = changed || sh("systemctl is-active --quiet ypserv").code != 0
if (needsYpservRestart) {
  runOrFail("systemctl restart ypserv", "restart ypserv")
}

System.exit(changed ? 10 : 0)
