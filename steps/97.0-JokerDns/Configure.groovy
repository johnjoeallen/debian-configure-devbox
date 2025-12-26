#!/usr/bin/env groovy
// RUN_VIA_SUDO
// --- Documentation ---
// Summary: Update Joker Dynamic DNS entries when the public IP changes.
// Config keys: configFile, jokerEndpoint, timeoutSeconds, scriptPath, cronSchedule, cronLogFile, cronDisabled, sdkmanDir
// Notes: Reads the external IP, compares it to the cached value, and issues Joker updates via their NIC API.

import lib.ConfigLoader
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.BufferedReader
import java.io.InputStreamReader
import static lib.StepUtils.writeText

final String stepKey = "JokerDns"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def cfg = ConfigLoader.stepConfig(stepKey)

def configPath = cfg.configFile?.toString()?.trim() ?: "/etc/joker.yaml"
def configFile = new File(configPath)
if (!configFile.exists()) {
  println "⚠️  Joker config not found at ${configPath}; skipping."
  System.exit(0)
}

def yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
def raw = yaml.load(configFile.text)
if (!(raw instanceof Map)) {
  System.err.println("⚠️  Invalid Joker config structure (${configPath})")
  System.exit(1)
}
Map data = raw as Map

def jokerEndpoint = stringValue(data.jokerEndpoint) ?: stringValue(cfg.jokerEndpoint) ?: "https://svc.joker.com/nic/update"
def timeoutSeconds = (numberValue(data.timeoutSeconds) ?: numberValue(cfg.timeoutSeconds) ?: 10) as int
def timeoutMs = timeoutSeconds * 1000
def updates = (data.updates instanceof Collection) ? (Collection) data.updates : []
def globalUser = stringValue(data.user) ?: stringValue(cfg.user)
def globalPassword = stringValue(data.password) ?: stringValue(cfg.password)

def repoRoot = new File('.').canonicalPath
def scriptPath = stringValue(cfg.scriptPath) ?: "/usr/local/bin/joker-dns-update"
def cronPath = stringValue(cfg.cronPath) ?: "/etc/cron.d/joker-dns"
def cronSchedule = stringValue(cfg.cronSchedule) ?: "* * * * *"
def cronLogFile = stringValue(cfg.cronLogFile) ?: "/var/log/joker-dns.log"
def cronDisabled = boolFlag(cfg.cronDisabled, false)
def sdkmanDir = stringValue(cfg.sdkmanDir) ?: "/root/.sdkman"

ensureRunnerScript(repoRoot, configPath, scriptPath, sdkmanDir)
ensureCron(cronPath, cronSchedule, cronLogFile, scriptPath, configPath, cronDisabled)


def eligible = updates.collect { entry ->
  if (entry instanceof String) {
    return [hostname: entry]
  } else if (entry instanceof Map) {
    return entry
  }
  return null
}.findAll { entry ->
  entry instanceof Map && !boolFlag(entry.disabled, false)
}

if (eligible.isEmpty()) {
  println "ℹ️  Joker config has no enabled updates, nothing to do."
  System.exit(0)
}

eligible.each { Map entry ->
  def user = stringValue(entry.user) ?: globalUser
  def password = stringValue(entry.password) ?: globalPassword
  def domain = stringValue(entry.hostname) ?: stringValue(entry.domain)
  if (!user || !password) {
    System.err.println("⚠️  Missing Joker credentials (global or entry-level) for ${domain ?: entry}; skipping.")
    return
  }
  if (!user || !password || !domain) {
    System.err.println("⚠️  Skipping incomplete entry: ${entry}")
    return
  }
  def result = callJoker(jokerEndpoint, user, password, domain, timeoutMs)
  println("→ ${domain}: ${result}")
}

static String stringValue(Object value) {
  if (value == null) {
    return null
  }
  def text = value.toString().trim()
  return text ? text : null
}

static Number numberValue(Object value) {
  if (value == null) {
    return null
  }
  if (value instanceof Number) {
    return value
  }
  def text = value.toString().trim()
  if (!text) {
    return null
  }
  try {
    return Integer.parseInt(text)
  } catch (NumberFormatException e) {
    return null
  }
}

static String callJoker(String endpoint, String user, String password, String domain, int timeout) {
  try {
    def query = [
      username: user,
      password: password,
      hostname: domain
    ].collect { k, v ->
      "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }.join("&")
    def url = new URL("${endpoint}?${query}")
    def conn = (HttpURLConnection) url.openConnection()
    conn.connectTimeout = timeout
    conn.readTimeout = timeout
    conn.setRequestProperty("User-Agent", "joker-updater/1.0")
    conn.inputStream.withCloseable { stream ->
      new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).withCloseable { reader ->
        return reader.readLine()?.trim() ?: "no response"
      }
    }
  } catch (Exception e) {
    return "error (${e.message})"
  }
}

static boolean boolFlag(Object value, boolean defaultValue) {
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

def ensureRunnerScript(String repoRoot, String configPath, String scriptPath, String sdkmanDir) {
  def file = new File(scriptPath)
  file.parentFile?.mkdirs()
  def content = """#!/bin/sh
set -euo pipefail
ROOT_DIR='${repoRoot}'
CONFIG_FILE="\${1:-${configPath}}"
if [ -n "\${JOKER_CONFIG:-}" ]; then
  CONFIG_FILE="\${JOKER_CONFIG}"
fi
SDKMAN_DIR="${sdkmanDir}"
PATH="${sdkmanDir}/candidates/groovy/current/bin:/usr/local/bin:/usr/bin:/bin"
GROOVY_BIN="\$(command -v groovy || true)"
if [ -z "\$GROOVY_BIN" ]; then
  echo "⚠️  groovy not found; install Groovy via SDKMAN before running this script." >&2
  exit 1
fi
cd "\$ROOT_DIR"
exec "\$GROOVY_BIN" -cp "\$ROOT_DIR/lib/snakeyaml-2.2.jar" "\$ROOT_DIR/steps/97.0-JokerDns/Configure.groovy" "\$CONFIG_FILE"
"""
  if (!file.exists() || file.text != content) {
    writeText(scriptPath, content)
  }
  file.setExecutable(true, false)
}

def ensureCron(String cronPath, String schedule, String logPath, String scriptPath, String configPath, boolean disabled) {
  def cronFile = new File(cronPath)
  if (disabled) {
    if (cronFile.exists()) {
      cronFile.delete()
    }
    println "ℹ️  Joker cron disabled (removed ${cronPath})."
    return
  }
  def logFile = new File(logPath)
  logFile.parentFile?.mkdirs()
  def content = """# Joker DNS updater
${schedule} root ${scriptPath} ${configPath} >> ${logPath} 2>&1
"""
  if (!cronFile.exists() || cronFile.text != content) {
    writeText(cronPath, content)
    println "✅ Joker cron scheduled (${schedule} → ${cronPath})."
  }
}
