#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Configure NIS domain and ypserver based on merged configuration files.
// Config keys: domain (string), server (string)
// Notes: Installs required packages, skips when domain or server are absent, and restarts ypbind/nscd.

import lib.ConfigLoader
import static lib.StepUtils.backup
import static lib.StepUtils.sh
import static lib.StepUtils.writeText

def stepKey = "nisSetup"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration ⏭️"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

def isBlank = { v -> v == null || v.toString().trim().isEmpty() }

def desiredDomain = stepConfig.domain?.toString()?.trim()
def serverValue = stepConfig.server?.toString()?.trim()
if (isBlank(desiredDomain) || isBlank(serverValue)) {
  println "NIS configuration missing domain or server in merged configuration. Skipping. ⚠️"
  System.exit(0)
}

def configMeta = ConfigLoader.meta()
def sourceMsg = []
if (configMeta?.base && configMeta.base.exists()) sourceMsg << configMeta.base.path
if (configMeta?.host && configMeta.host.exists()) sourceMsg << configMeta.host.path
if (sourceMsg) {
  println "Using NIS config from ${sourceMsg.join(' + ')} 📁"
}
def serverLine = serverValue.startsWith("ypserver") ? serverValue : "ypserver ${serverValue}"

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

def ensureEnabled = { String service ->
  def status = sh("systemctl is-enabled ${service} 2>/dev/null")
  def normalized = status.out?.trim()?.toLowerCase()
  def acceptable = ['enabled', 'static', 'linked', 'alias', 'enabled-runtime']
  if (status.code != 0 || !(normalized in acceptable)) {
    runOrFail("systemctl enable ${service}", "enable ${service}")
    changed = true
  }
}

def requiredPkgs = ["nis", "nscd"]
def missingPkgs = requiredPkgs.findAll { name ->
  sh("dpkg -s ${name} >/dev/null 2>&1").code != 0
}
if (missingPkgs) {
  runOrFail("apt-get update -y", "apt-get update")
  runOrFail("DEBIAN_FRONTEND=noninteractive apt-get install -y ${missingPkgs.join(' ')}", "apt-get install ${missingPkgs.join(' ')}")
  changed = true
}
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
def ypconf = new File("/etc/yp.conf")
if (!ypconf.exists() || !(ypconf.text.contains(serverLine))) {
  backup("/etc/yp.conf")
  writeText("/etc/yp.conf", serverLine + "\n")
  changed = true
}
ensureEnabled('ypbind')
ensureEnabled('nscd')
def needsYpbindRestart = changed || sh("systemctl is-active --quiet ypbind").code != 0
if (needsYpbindRestart) {
  runOrFail("systemctl restart ypbind", "restart ypbind")
}
def needsNscdRestart = changed || sh("systemctl is-active --quiet nscd").code != 0
if (needsNscdRestart) {
  sh("systemctl restart nscd 2>/dev/null || true")
}
System.exit(changed ? 10 : 0)
