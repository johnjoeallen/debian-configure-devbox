#!/usr/bin/env groovy
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.SafeConstructor

import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.io.BufferedReader
import java.io.InputStreamReader

def configPath = args.length > 0 ? args[0] : "/etc/joker.yaml"
def configFile = new File(configPath)
if (!configFile.exists()) {
  System.err.println("⚠️  Joker config not found: ${configPath}")
  System.exit(1)
}

def yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
def raw = yaml.load(configFile.text)
if (!(raw instanceof Map)) {
  System.err.println("⚠️  Joker config invalid: ${configPath}")
  System.exit(1)
}
Map cfg = (Map) raw

def endpoint = stringValue(cfg.jokerEndpoint) ?: "https://svc.joker.com/nic/update"
def timeoutSeconds = Math.max(numberValue(cfg.timeoutSeconds) ?: 10, 1) as int
def globalUser = stringValue(cfg.user)
def globalPassword = stringValue(cfg.password)
def hostEntries = collectHostEntries(cfg)

if (hostEntries.isEmpty()) {
  println "ℹ️  Joker config has no hosts to update."
  System.exit(0)
}

boolean anySuccess = false
boolean hadFailures = false

hostEntries.each { Map entry ->
  if (boolFlag(entry.disabled, false)) {
    return
  }
  def hostname = stringValue(entry.hostname) ?: stringValue(entry.domain) ?: stringValue(entry.host)
  if (!hostname) {
    System.err.println("⚠️  Skipping Joker entry without a hostname: ${entry}")
    hadFailures = true
    return
  }
  def user = stringValue(entry.user) ?: globalUser
  def password = stringValue(entry.password) ?: globalPassword
  if (!user || !password) {
    System.err.println("⚠️  Missing credentials for ${hostname}; define credentials globally or per-host.")
    hadFailures = true
    return
  }
  def result = callJoker(endpoint, user, password, hostname, timeoutSeconds * 1000)
  println("→ ${hostname}: ${result}")
  if (result ==~ /(?i)^(good|nochg|nochg.*)/) {
    anySuccess = true
  } else {
    hadFailures = true
  }
}

if (!anySuccess && hadFailures) {
  System.exit(1)
}
System.exit(0)

def collectHostEntries(Map cfg) {
  def rawHosts = cfg.hosts ?: cfg.updates ?: []
  def entries = []
  if (rawHosts instanceof Collection) {
    rawHosts.each { entry ->
      if (entry == null) {
        return
      }
      if (entry instanceof Map) {
        entries << new LinkedHashMap(entry)
      } else {
        def host = entry?.toString()?.trim()
        if (host) {
          entries << [hostname: host]
        }
      }
    }
  } else if (rawHosts != null) {
    entries << [hostname: rawHosts.toString().trim()]
  }
  entries.findAll { it.hostname }
}

def callJoker(String endpoint, String user, String password, String hostname, int timeoutMs) {
  try {
    def query = [
      username: user,
      password: password,
      hostname: hostname
    ].collect { k, v ->
      "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
    }.join("&")
    def url = new URL("${endpoint}?${query}")
    def conn = (HttpURLConnection) url.openConnection()
    conn.setConnectTimeout(timeoutMs)
    conn.setReadTimeout(timeoutMs)
    conn.setRequestProperty("User-Agent", "joker-updater/1.0")
    def code = conn.responseCode
    def responseStream = code >= 400 ? (conn.errorStream ?: conn.inputStream) : conn.inputStream
    if (responseStream == null) {
      return "no response (${code})"
    }
    responseStream.withCloseable { stream ->
      new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).withCloseable { reader ->
        return reader.readLine()?.trim() ?: "no response (${code})"
      }
    }
  } catch (Exception e) {
    return "error (${e.message})"
  }
}

def stringValue(Object value) {
  if (value == null) {
    return null
  }
  def text = value.toString().trim()
  return text ? text : null
}

def numberValue(Object value) {
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

def boolFlag(Object value, boolean defaultValue) {
  if (value == null) {
    return defaultValue
  }
  if (value instanceof Boolean) {
    return value
  }
  def text = value.toString().trim().toLowerCase()
  if (!text) {
    return defaultValue
  }
  return ["1", "true", "yes", "y"].contains(text)
}
