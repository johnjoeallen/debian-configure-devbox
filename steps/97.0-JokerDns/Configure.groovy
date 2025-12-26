#!/usr/bin/env groovy
// RUN_VIA_SUDO
// --- Documentation ---
// Summary: Update Joker Dynamic DNS entries when the public IP changes.
// Config keys: configFile, ipSource, cacheFile, jokerEndpoint, timeoutSeconds
// Notes: Reads the external IP, compares it to the cached value, and issues Joker updates via their NIC API.

import lib.ConfigLoader
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

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

def ipSource = stringValue(data.ipSource) ?: stringValue(cfg.ipSource) ?: "https://checkip.amazonaws.com"
def cacheFilePath = stringValue(data.cacheFile) ?: stringValue(cfg.cacheFile) ?: "/etc/cachedexternalip"
def jokerEndpoint = stringValue(data.jokerEndpoint) ?: stringValue(cfg.jokerEndpoint) ?: "https://svc.joker.com/nic/update"
def timeoutSeconds = (numberValue(data.timeoutSeconds) ?: numberValue(cfg.timeoutSeconds) ?: 10) as int
def timeoutMs = timeoutSeconds * 1000
def updates = (data.updates instanceof Collection) ? (Collection) data.updates : []
def globalUser = stringValue(data.user) ?: stringValue(cfg.user)
def globalPassword = stringValue(data.password) ?: stringValue(cfg.password)

def cacheFile = new File(cacheFilePath)
cacheFile.parentFile?.mkdirs()
if (!cacheFile.exists()) {
  cacheFile.createNewFile()
}
def cachedIp = cacheFile.text?.trim()
def externalIp = fetchExternalIp(ipSource, timeoutMs)
if (!externalIp) {
  System.err.println("⚠️  Unable to determine external IP from ${ipSource}")
  System.exit(1)
}
if (externalIp == cachedIp) {
  println "✔️  External IP unchanged (${externalIp})"
  System.exit(0)
}

cacheFile.text = "${externalIp}\n"

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
  def result = callJoker(jokerEndpoint, user, password, domain, externalIp, timeoutMs)
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

static String fetchExternalIp(String source, int timeout) {
  try {
    def url = new URL(source)
    def conn = (HttpURLConnection) url.openConnection()
    conn.connectTimeout = timeout
    conn.readTimeout = timeout
    conn.setRequestProperty("User-Agent", "joker-updater/1.0")
    conn.inputStream.withReader(StandardCharsets.UTF_8) { reader ->
      return reader.readLine()?.trim()
    }
  } catch (Exception e) {
    System.err.println("⚠️  Failed to fetch IP: ${e.message}")
    return null
  }
}

static String callJoker(String endpoint, String user, String password, String domain, String ip, int timeout) {
  try {
    def query = [
      username: user,
      password: password,
      hostname: domain,
      myip: ip
    ].collect { k, v ->
      "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }.join("&")
    def url = new URL("${endpoint}?${query}")
    def conn = (HttpURLConnection) url.openConnection()
    conn.connectTimeout = timeout
    conn.readTimeout = timeout
    conn.setRequestProperty("User-Agent", "joker-updater/1.0")
    conn.inputStream.withReader(StandardCharsets.UTF_8) { reader ->
      return reader.readLine()?.trim() ?: "no response"
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
