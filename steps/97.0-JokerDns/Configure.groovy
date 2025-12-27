#!/usr/bin/env groovy
// RUN_VIA_SUDO
// --- Documentation ---
// Summary: Install the Joker cron runner script and ensure the updater runs on schedule.
// Config keys: configFile, scriptPath, cronPath, cronSchedule, cronLogFile, cronDisabled, snakeyamlVersion
// Notes: Actual DNS updates happen in `Update.groovy`; this step only writes the helper script and cron entry.

import lib.ConfigLoader
import static lib.StepUtils.backup
import static lib.StepUtils.writeText

final String stepKey = "JokerDns"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
Map cfg = ConfigLoader.stepConfig(stepKey)

def repoRoot = new File('.').canonicalPath
def configPath = stringValue(cfg.configFile) ?: "/etc/joker.yaml"
def scriptPath = stringValue(cfg.scriptPath) ?: "/usr/local/bin/joker-dns-update"
def cronPath = stringValue(cfg.cronPath) ?: "/etc/cron.d/joker-dns"
def cronSchedule = stringValue(cfg.cronSchedule) ?: "* * * * *"
def cronLogFile = stringValue(cfg.cronLogFile) ?: "/var/log/joker-dns.log"
def cronDisabled = boolFlag(cfg.cronDisabled, false)
def snakeyamlVersion = stringValue(cfg.snakeyamlVersion) ?: System.getenv('SNAKEYAML_VERSION') ?: "2.2"

ensureRunnerScript(repoRoot, scriptPath, configPath, snakeyamlVersion)
ensureCron(cronPath, cronSchedule, cronLogFile, scriptPath, configPath, cronDisabled)

def ensureRunnerScript(String rootDir, String scriptPath, String configPath, String yamlVersion) {
  def file = new File(scriptPath)
  file.parentFile?.mkdirs()
  def content = """#!/bin/sh
set -euo pipefail
ROOT_DIR='${rootDir}'
CONFIG_FILE='${configPath}'
if [ -n "${1:-}" ]; then
  CONFIG_FILE="${1}"
fi
if [ -n "${JOKER_CONFIG:-}" ]; then
  CONFIG_FILE="${JOKER_CONFIG}"
fi
PATH="/usr/local/bin:/usr/bin:/bin"
GROOVY_BIN="$(command -v groovy || true)"
if [ -z "$GROOVY_BIN" ]; then
  echo "⚠️  groovy not found; install it before running the Joker updater." >&2
  exit 1
fi
cd "$ROOT_DIR"
exec "$GROOVY_BIN" -cp "$ROOT_DIR/lib/snakeyaml-${yamlVersion}.jar" "$ROOT_DIR/steps/97.0-JokerDns/Update.groovy" "$CONFIG_FILE"
"""
  if (!file.exists() || file.text != content) {
    backup(scriptPath)
    writeText(scriptPath, content)
    println "✅ Joker updater script written (${scriptPath})"
  }
  file.setExecutable(true, false)
}

def ensureCron(String cronPath, String schedule, String logPath, String scriptPath, String configPath, boolean disabled) {
  def cronFile = new File(cronPath)
  if (disabled) {
    if (cronFile.exists()) {
      backup(cronPath)
      cronFile.delete()
      println "ℹ️  Joker cron disabled (removed ${cronPath})."
    }
    return
  }
  def logFile = new File(logPath)
  logFile.parentFile?.mkdirs()
  def content = """# Joker DNS updater
${schedule} root ${scriptPath} ${configPath} >> ${logPath} 2>&1
"""
  if (!cronFile.exists() || cronFile.text != content) {
    backup(cronPath)
    writeText(cronPath, content)
    println "✅ Joker cron scheduled (${schedule} → ${cronPath})."
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
