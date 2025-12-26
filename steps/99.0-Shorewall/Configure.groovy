#!/usr/bin/env groovy
// RUN_VIA_SUDO
// --- Documentation ---
// Summary: Install Shorewall and ensure HTTP/S ports are open.
// Config keys: rulesFile, rules, allowIncomingHttp
// Notes: Appends ACCEPT rules to `/etc/shorewall/rules` and reloads Shorewall when new lines appear.

import lib.ConfigLoader
import static lib.StepUtils.backup
import static lib.StepUtils.sh
import static lib.StepUtils.writeText

final String stepKey = "Shorewall"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
Map cfg = ConfigLoader.stepConfig(stepKey)

def rulesFilePath = stringValue(cfg.rulesFile) ?: "/etc/shorewall/rules"
def allowIncomingHttp = boolFlag(cfg.allowIncomingHttp, true)
def configuredRules = (cfg.rules instanceof Collection && !cfg.rules.isEmpty()) ? cfg.rules :
  (allowIncomingHttp ? defaultRules() : [])

def enableRpFilter = boolFlag(cfg.enableRpFilter, true)
def sysctlFilePath = stringValue(cfg.sysctlFile) ?: "/etc/sysctl.d/98-shorewall.conf"

String sysctlContent(boolean rp) {
  def lines = [
    "net.ipv4.ip_forward = 1",
    "net.ipv4.conf.all.send_redirects = 0",
    "net.ipv4.conf.default.send_redirects = 0"
  ]
  if (rp) {
    lines += [
      "net.ipv4.conf.all.rp_filter = 1",
      "net.ipv4.conf.default.rp_filter = 1"
    ]
  }
  return lines.join("\n") + "\n"
}

ensureSysctl(sysctlFilePath, enableRpFilter)
ensurePackages()
def rulesChanged = ensureRules(rulesFilePath, configuredRules)
if (rulesChanged) {
  restartShorewall()
}

def defaultRules() {
  return [
    [port: 80, comment: "Allow HTTP"],
    [port: 443, comment: "Allow HTTPS"]
  ]
}

def ensurePackages() {
  def required = ["shorewall"]
  def missing = required.findAll { pkg -> sh("dpkg -s ${pkg} >/dev/null 2>&1").code != 0 }
  if (!missing.isEmpty()) {
    println "🚀 Installing Shorewall packages: ${missing.join(' ')}"
    sh("DEBIAN_FRONTEND=noninteractive apt-get update >/dev/null")
    def result = sh("DEBIAN_FRONTEND=noninteractive apt-get install -y ${missing.join(' ')}")
    if (result.code != 0) {
      System.err.println(result.out)
      System.err.println(result.err)
      System.exit(result.code)
    }
  }
  sh("systemctl enable --now shorewall")
}

def ensureSysctl(String path, boolean rpFilter) {
  def file = new File(path)
  file.parentFile?.mkdirs()
  def content = sysctlContent(rpFilter)
  if (!file.exists() || file.text != content) {
    backup(path)
    writeText(path, content)
    println "✅ Sysctl config updated (${path})"
  }
  sh("sysctl --system >/dev/null") // apply changes
}

def ensureRules(String path, Collection ruleDefs) {
  def file = new File(path)
  file.parentFile?.mkdirs()
  if (!file.exists()) {
    file.createNewFile()
  }
  def existingNormalized = file.readLines()
    .collect { line -> line.split("#")[0].trim() }
    .findAll { it }
    .toSet()

  def toAppend = []
  ruleDefs.each { raw ->
    if (!(raw instanceof Map)) {
      return
    }
    if (boolFlag(raw.disabled, false)) {
      return
    }
    def source = stringValue(raw.source) ?: "net"
    def target = stringValue(raw.target) ?: "fw"
    def proto = stringValue(raw.proto) ?: "tcp"
    def port = stringValue(raw.port) ?: stringValue(raw.ports) ?: ""
    if (!port) {
      return
    }
    def options = stringValue(raw.options)
    def comment = stringValue(raw.comment)
    def normalized = ["ACCEPT", source, target, proto, port].join(" ")
    if (options) {
      normalized += " ${options}"
    }
    if (existingNormalized.contains(normalized)) {
      return
    }
    def line = normalized + (comment ? " # ${comment}" : "")
    toAppend << line
  }
  if (toAppend.isEmpty()) {
    return false
  }
  backup(path)
  file.withWriterAppend { writer ->
    toAppend.each { writer.writeLine(it) }
  }
  println "✅ Shorewall rules updated (${path})"
  return true
}

def restartShorewall() {
  def check = sh("shorewall check")
  if (check.code != 0) {
    System.err.println("⚠️  Shorewall check failed")
    System.err.println(check.out)
    System.err.println(check.err)
    System.exit(check.code)
  }
  def reload = sh("systemctl reload shorewall")
  if (reload.code != 0) {
    System.err.println("⚠️  Failed to reload Shorewall")
    System.err.println(reload.out)
    System.err.println(reload.err)
    System.exit(reload.code)
  }
}

def stringValue(Object value) {
  if (value == null) {
    return null
  }
  def text = value.toString().trim()
  return text ? text : null
}

def boolFlag(Object value, boolean defaultValue) {
  if (value == null) {
    return defaultValue
  }
  if (value instanceof Boolean) {
    return (boolean) value
  }
  def text = value.toString().trim().toLowerCase()
  if (!text) {
    return defaultValue
  }
  return ["1", "true", "yes", "y"].contains(text)
}
