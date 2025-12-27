#!/usr/bin/env groovy
// RUN_VIA_SUDO
// --- Documentation ---
// Summary: Manage Shorewall configuration (zones, interfaces, policy, NAT, rules) entirely from YAML.
// Config keys: enabled, zonesFile, interfacesFile, policyFile, masqFile, rulesFile, sysctlFile, sysctl, zones, interfaces, policy, masq, rules, enableRpFilter
// Notes: The step installs Shorewall, enables the service, writes the requested Shorewall tables, and applies the sysctl snippet.

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

def zonesFile = stringValue(cfg.zonesFile) ?: "/etc/shorewall/zones"
def interfacesFile = stringValue(cfg.interfacesFile) ?: "/etc/shorewall/interfaces"
def policyFile = stringValue(cfg.policyFile) ?: "/etc/shorewall/policy"
def masqFile = stringValue(cfg.masqFile) ?: "/etc/shorewall/masq"
def rulesFile = stringValue(cfg.rulesFile) ?: "/etc/shorewall/rules"
def sysctlFile = stringValue(cfg.sysctlFile) ?: "/etc/sysctl.d/98-shorewall.conf"
def enableRpFilter = boolFlag(cfg.enableRpFilter, true)
def sysctlOverrides = cfg.sysctl instanceof Map ? (Map) cfg.sysctl : [:]

def defaultZones = [
  [zone: "loc", type: "ipv4"],
  [zone: "net", type: "ipv4"],
  [zone: "fw", type: "firewall"]
]
def defaultInterfaces = [
  [interface: "enp3s0", zone: "loc"],
  [interface: "enp6s0", zone: "net"]
]
def defaultPolicy = [
  [source: "loc", dest: "net", policy: "ACCEPT"],
  [source: "loc", dest: "fw", policy: "ACCEPT"],
  [source: "fw", dest: "loc", policy: "ACCEPT"],
  [source: "fw", dest: "net", policy: "ACCEPT"],
  [source: "net", dest: "fw", policy: "DROP"],
  [source: "net", dest: "loc", policy: "DROP"]
]
def defaultMasq = [
  [interface: "enp6s0", source: "10.0.0.0/24"],
  [interface: "enp6s0", source: "172.17.0.0/16"]
]
def defaultRules = [
  [action: "ACCEPT", source: "net", dest: "fw", proto: "tcp", port: "80", comment: "Allow HTTP to the gateway"],
  [action: "ACCEPT", source: "net", dest: "fw", proto: "tcp", port: "443", comment: "Allow HTTPS to the gateway"]
]

def zoneEntries = entriesOrDefault(cfg.zones, defaultZones)
def interfaceEntries = entriesOrDefault(cfg.interfaces, defaultInterfaces)
def policyEntries = entriesOrDefault(cfg.policy, defaultPolicy)
def masqEntries = entriesOrDefault(cfg.masq ?: cfg.masquerade, defaultMasq)
def ruleEntries = entriesOrDefault(cfg.rules, defaultRules)

ensurePackages()
def changed = false
changed |= ensureSysctl(sysctlFile, buildSysctlContent(sysctlOverrides, enableRpFilter))
changed |= ensureStructuredFile(zonesFile, zoneEntries, "zones") { renderZone(it) }
changed |= ensureStructuredFile(interfacesFile, interfaceEntries, "interfaces") { renderInterface(it) }
changed |= ensureStructuredFile(policyFile, policyEntries, "policy") { renderPolicy(it) }
changed |= ensureStructuredFile(masqFile, masqEntries, "masq") { renderMasq(it) }
changed |= ensureStructuredFile(rulesFile, ruleEntries, "rules") { renderRule(it) }

if (changed) {
  restartShorewall()
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

def ensureSysctl(String path, List<String> lines) {
  def file = new File(path)
  file.parentFile?.mkdirs()
  def normalized = (lines ?: [])*.trim().findAll { it }
  def content = normalized ? normalized.join("\n") + "\n" : ""
  def changed = !file.exists() || file.text != content
  if (changed) {
    backup(path)
    writeText(path, content)
    println "✅ Sysctl config updated (${path})"
  }
  sh("sysctl --system >/dev/null")
  return changed
}

def buildSysctlContent(Map overrides, boolean rpFilter) {
  def lines = [
    "net.ipv4.ip_forward = 1",
    "net.ipv4.conf.all.send_redirects = 0",
    "net.ipv4.conf.default.send_redirects = 0"
  ]
  if (rpFilter) {
    lines += [
      "net.ipv4.conf.all.rp_filter = 1",
      "net.ipv4.conf.default.rp_filter = 1"
    ]
  }
  overrides.each { key, value ->
    def entry = "${key.toString()} = ${value.toString()}"
    def existingIndex = lines.findIndexOf { it.startsWith("${key} =") }
    if (existingIndex >= 0) {
      lines[existingIndex] = entry
    } else {
      lines << entry
    }
  }
  lines
}

def ensureStructuredFile(String path, List<Map> entries, String description, Closure<String> renderer) {
  def active = entries.findAll { !boolFlag(it.disabled, false) }
  if (active.isEmpty()) {
    return false
  }
  def lines = active.collect { renderer(it) }.findAll { it }
  if (lines.isEmpty()) {
    return false
  }
  def file = new File(path)
  file.parentFile?.mkdirs()
  def content = lines.join("\n") + "\n"
  if (!file.exists() || file.text != content) {
    backup(path)
    writeText(path, content)
    println "✅ Shorewall ${description} updated (${path})"
    return true
  }
  return false
}

def renderZone(Map entry) {
  if (entry.line) {
    return entry.line?.toString()?.trim()
  }
  def zone = stringValue(entry.zone ?: entry.name)
  if (!zone) {
    throw new IllegalArgumentException("Shorewall zone entry missing 'zone' name")
  }
  def tokens = [
    zone,
    stringValue(entry.type) ?: "ipv4",
    renderList(entry.interfaces),
    renderList(entry.options)
  ].findAll { it }
  return renderLine(tokens, entry.comment)
}

def renderInterface(Map entry) {
  if (entry.line) {
    return entry.line?.toString()?.trim()
  }
  def zone = stringValue(entry.zone)
  def iface = stringValue(entry.interface ?: entry.iface ?: entry.name)
  if (!zone || !iface) {
    throw new IllegalArgumentException("Shorewall interface entry requires 'zone' and 'interface'")
  }
  def tokens = [zone, iface, renderList(entry.options)]
  return renderLine(tokens, entry.comment)
}

def renderPolicy(Map entry) {
  if (entry.line) {
    return entry.line?.toString()?.trim()
  }
  def source = stringValue(entry.source)
  def dest = stringValue(entry.dest)
  def policy = stringValue(entry.policy)
  if (!source || !dest || !policy) {
    throw new IllegalArgumentException("Shorewall policy entry requires 'source', 'dest', and 'policy'")
  }
  def tokens = [source, dest, policy, renderList(entry.logLevel ?: entry.log), renderList(entry.options)]
  return renderLine(tokens, entry.comment)
}

def renderMasq(Map entry) {
  if (entry.line) {
    return entry.line?.toString()?.trim()
  }
  def iface = stringValue(entry.interface ?: entry.gateway)
  def source = stringValue(entry.source)
  if (!iface || !source) {
    throw new IllegalArgumentException("Shorewall masq entry requires 'interface' (egress) and 'source'")
  }
  def tokens = [
    iface,
    source,
    renderList(entry.address ?: entry.dest),
    renderList(entry.opts ?: entry.options)
  ].findAll { it }
  return renderLine(tokens, entry.comment)
}

def renderRule(Map entry) {
  if (entry.line) {
    return entry.line?.toString()?.trim()
  }
  def action = stringValue(entry.action ?: entry.accept ?: entry.ac ? "ACCEPT" : null)
  def source = stringValue(entry.source)
  def target = stringValue(entry.dest ?: entry.target ?: entry.destination)
  def proto = stringValue(entry.proto) ?: "tcp"
  def ports = stringValue(entry.port ?: entry.ports)
  if (!action) {
    action = "ACCEPT"
  }
  if (!source || !target || !ports) {
    throw new IllegalArgumentException("Shorewall rule entry requires 'source', 'target', and 'port(s)'")
  }
  def tokens = [
    action,
    source,
    target,
    proto,
    ports,
    renderList(entry.options)
  ].findAll { it }
  return renderLine(tokens, entry.comment)
}

def renderLine(List<String> tokens, String comment) {
  def trimmed = tokens.findAll { it?.trim() }
  if (trimmed.isEmpty()) {
    return null
  }
  def line = trimmed.join("\t")
  if (comment) {
    line += " # ${comment}"
  }
  return line
}

def renderList(Object value) {
  if (value == null) {
    return null
  }
  if (value instanceof Collection) {
    def normalized = value.collect { it?.toString()?.trim() }.findAll { it }
    return normalized.join(",")
  }
  def text = value.toString().trim()
  return text ? text : null
}

def normalizeEntries(Object raw) {
  def entries = []
  if (raw instanceof Collection) {
    raw.each { entry ->
      if (entry == null) {
        return
      }
      if (entry instanceof Map) {
        entries << new LinkedHashMap(entry)
      } else {
        def text = entry.toString().trim()
        if (text) {
          entries << [line: text]
        }
      }
    }
  } else if (raw != null) {
    def text = raw.toString().trim()
    if (text) {
      entries << [line: text]
    }
  }
  entries
}

def entriesOrDefault(Object raw, List<Map> fallback) {
  def entries = normalizeEntries(raw)
  if (!entries.isEmpty()) {
    return entries
  }
  return fallback.collect { new LinkedHashMap(it) }
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
