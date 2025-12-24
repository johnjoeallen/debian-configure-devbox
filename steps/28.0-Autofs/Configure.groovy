#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install and configure autofs maps for on-demand mounts.
// Config keys: maps (list), masterFile (string)
// Notes: Writes an auto.master.d fragment and per-map files, then restarts autofs if needed.

import lib.ConfigLoader
import static lib.StepUtils.backup
import static lib.StepUtils.sh
import static lib.StepUtils.writeText

def stepKey = "Autofs"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

def isBlank = { v -> v == null || v.toString().trim().isEmpty() }

def rawMaps = stepConfig.maps

def normalizeMaps = { input ->
  def list = []
  if (input instanceof Map) {
    input.each { k, v ->
      def mapConfig = [:]
      if (v instanceof Map) {
        mapConfig.putAll(v)
      }
      mapConfig.mount = k
      list << mapConfig
    }
  } else if (input instanceof Collection) {
    input.each { entry ->
      if (entry instanceof Map) {
        list << entry
      } else if (entry != null) {
        list << [mount: entry.toString().trim()]
      }
    }
  }
  return list
}

def maps = normalizeMaps(rawMaps)
if (!maps || maps.isEmpty()) {
  println "No autofs maps configured for ${stepKey}; skipping"
  System.exit(0)
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

def ensureEnabled = { String service ->
  def status = sh("systemctl is-enabled ${service} 2>/dev/null")
  def normalized = status.out?.trim()?.toLowerCase()
  def acceptable = ['enabled', 'static', 'linked', 'alias', 'enabled-runtime']
  if (status.code != 0 || !(normalized in acceptable)) {
    runOrFail("systemctl enable ${service}", "enable ${service}")
  }
}

def deriveName = { String mountPath, String explicitName ->
  if (!isBlank(explicitName)) {
    return explicitName
  }
  def normalized = mountPath?.replaceAll("^/+", "")
  if (!normalized) {
    normalized = "root"
  }
  normalized = normalized.replaceAll("[^A-Za-z0-9]+", "-")
  if (!normalized) {
    normalized = "map"
  }
  return normalized
}

def entryLine = { String key, Object value ->
  if (isBlank(key)) {
    System.err.println("Autofs map entries require a non-empty key")
    System.exit(1)
  }
  if (value instanceof Map) {
    def location = value.location?.toString()?.trim()
    if (isBlank(location)) {
      System.err.println("Autofs map entries require 'location' for key '${key}'")
      System.exit(1)
    }
    def options = value.options?.toString()?.trim()
    return options ? "${key} ${options} ${location}" : "${key} ${location}"
  }
  def raw = value?.toString()?.trim()
  if (isBlank(raw)) {
    System.err.println("Autofs map entries require a value for key '${key}'")
    System.exit(1)
  }
  return "${key} ${raw}"
}

def entryLineFromMap = { Map entry ->
  def key = entry.key?.toString()?.trim()
  def location = entry.location?.toString()?.trim()
  if (isBlank(key) || isBlank(location)) {
    System.err.println("Autofs map entries require both 'key' and 'location'")
    System.exit(1)
  }
  def options = entry.options?.toString()?.trim()
  return options ? "${key} ${options} ${location}" : "${key} ${location}"
}

def normalizeEntries = { entries ->
  def lines = []
  if (entries instanceof Collection) {
    entries.each { entry ->
      if (entry instanceof Map) {
        lines << entryLineFromMap(entry)
      } else if (entry != null) {
        def line = entry.toString().trim()
        if (line) {
          lines << line
        }
      }
    }
  } else if (entries instanceof Map) {
    entries.each { k, v ->
      lines << entryLine(k?.toString(), v)
    }
  } else if (entries != null) {
    def line = entries.toString().trim()
    if (line) {
      lines << line
    }
  }
  return lines
}

boolean changed = false

def requiredPkgs = ["autofs"]
def missingPkgs = requiredPkgs.findAll { name ->
  sh("dpkg -s ${name} >/dev/null 2>&1").code != 0
}
if (missingPkgs) {
  runOrFail("apt-get update -y", "apt-get update")
  runOrFail("DEBIAN_FRONTEND=noninteractive apt-get install -y ${missingPkgs.join(' ')}", "apt-get install ${missingPkgs.join(' ')}")
  changed = true
}

def masterFile = stepConfig.masterFile?.toString()?.trim()
if (isBlank(masterFile)) {
  masterFile = "/etc/auto.master.d/devbox.autofs"
}

def masterDir = new File(masterFile).parentFile
if (masterDir && !masterDir.exists()) {
  runOrFail("mkdir -p ${masterDir.path}", "create ${masterDir.path}")
  changed = true
}

def masterLines = []

maps.eachWithIndex { mapConfig, idx ->
  if (!(mapConfig instanceof Map)) {
    System.err.println("Autofs maps entries must be objects; entry ${idx + 1} is invalid")
    System.exit(1)
  }
  def mountPath = mapConfig.mount?.toString()?.trim()
  if (isBlank(mountPath)) {
    System.err.println("Autofs maps require 'mount' for entry ${idx + 1}")
    System.exit(1)
  }
  def mapName = mapConfig.name?.toString()?.trim()
  def mapFile = mapConfig.mapFile?.toString()?.trim()
  if (isBlank(mapFile)) {
    mapFile = "/etc/auto.${deriveName(mountPath, mapName)}"
  }
  def options = mapConfig.options?.toString()?.trim()
  def masterLine = "${mountPath} ${mapFile}" + (options ? " ${options}" : "")
  masterLines << masterLine

  def entries = normalizeEntries(mapConfig.entries)
  def mapContent = entries ? entries.join("\n") + "\n" : ""
  def mapFileHandle = new File(mapFile)
  if (!mapFileHandle.exists() || mapFileHandle.text != mapContent) {
    backup(mapFile)
    writeText(mapFile, mapContent)
    changed = true
  }

  def mountDir = new File(mountPath)
  if (!mountDir.exists()) {
    def mkdirResult = sh("mkdir -p ${mountPath}")
    if (mkdirResult.code != 0) {
      def errMsg = mkdirResult.err ?: mkdirResult.out
      if (errMsg?.toLowerCase()?.contains("permission denied")) {
        println "Warning: unable to create ${mountPath} (permission denied); autofs may already manage it"
      } else {
        System.err.println("create ${mountPath} failed")
        if (mkdirResult.out) System.err.println(mkdirResult.out)
        if (mkdirResult.err) System.err.println(mkdirResult.err)
        System.exit(1)
      }
    } else {
      changed = true
    }
  }
}

def masterContent = masterLines.join("\n") + "\n"
def masterHandle = new File(masterFile)
if (!masterHandle.exists() || masterHandle.text != masterContent) {
  backup(masterFile)
  writeText(masterFile, masterContent)
  changed = true
}

ensureEnabled("autofs")
def needsRestart = changed || sh("systemctl is-active --quiet autofs").code != 0
if (needsRestart) {
  runOrFail("systemctl restart autofs", "restart autofs")
}

System.exit(changed ? 10 : 0)
