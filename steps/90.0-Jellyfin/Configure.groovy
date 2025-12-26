#!/usr/bin/env groovy
// RUN_VIA_SUDO
// --- Documentation ---
// Summary: Install Jellyfin from the official Debian repo and keep the service running.
// Config keys: packages, repoUrl, keyUrl, release
// Notes: Adds the Jellyfin apt repository, installs the requested packages, and enables jellyfin.service.

import lib.ConfigLoader
import static lib.StepUtils.backup
import static lib.StepUtils.sh
import static lib.StepUtils.writeText

import java.util.Arrays

final String stepKey = "Jellyfin"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}

Map cfg = ConfigLoader.stepConfig(stepKey)

def isBlank = { Object value -> value == null || value.toString().trim().isEmpty() }
def boolFlag = { Object value, boolean defaultValue ->
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

def shellQuote = { String text ->
  def safe = text.replace("'", "'\\''")
  return "'${safe}'"
}

def resolveRelease = {
  def osRelease = new File("/etc/os-release")
  if (osRelease.exists()) {
    def matcher = (osRelease.text =~ /VERSION_CODENAME\s*=\s*(\S+)/)
    if (matcher.find()) {
      return matcher.group(1).trim()
    }
  }
  def codename = sh("lsb_release -cs")
  if (codename.code == 0 && codename.out) {
    return codename.out.trim()
  }
  return null
}

def determinePackages = {
  def pkgs = new LinkedHashSet<String>()
  if (cfg.packages instanceof Collection) {
    cfg.packages.each { entry ->
      def pkg = entry?.toString()?.trim()
      if (pkg) {
        pkgs << pkg
      }
    }
  }
  if (pkgs.isEmpty()) {
    pkgs << "jellyfin"
  }
  pkgs
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

def resolveProxyDomains = { Map proxyCfg ->
  def entries = []
  def seen = new LinkedHashSet<String>()
  def addDomain = { Map raw ->
    def host = raw.host ?: raw.hostname ?: raw.serverName ?: raw.domain
    host = host?.toString()?.trim()
    if (isBlank(host) || seen.contains(host)) {
      return
    }
    seen << host
    def httpsEnabled = boolFlag(raw.https, true)
    def shouldRedirect = boolFlag(raw.redirect, httpsEnabled)
    def certbotEnabled = boolFlag(raw.certbot, httpsEnabled)
    def defaultAccessLog = "/var/log/apache2/${host}-access.log"
    def defaultErrorLog = "/var/log/apache2/${host}-error.log"
    def accessLog = raw.accessLog?.toString()?.trim() ?: defaultAccessLog
    def errorLog = raw.errorLog?.toString()?.trim() ?: defaultErrorLog
    entries << [host: host, https: httpsEnabled, redirect: shouldRedirect, certbot: certbotEnabled, accessLog: accessLog, errorLog: errorLog]
  }

  if (proxyCfg.domains instanceof Collection) {
    proxyCfg.domains.each { entry ->
      if (entry instanceof Map) {
        addDomain(entry as Map)
      }
    }
  }

  if (entries.isEmpty()) {
    def fallbackHost = proxyCfg.serverName?.toString()?.trim()
    if (!isBlank(fallbackHost)) {
      def httpsEnabled = boolFlag(proxyCfg.httpsEnabled, true)
      def redirectEnabled = boolFlag(proxyCfg.redirectToHttps, httpsEnabled)
      def fallbackCertbot = boolFlag(proxyCfg.certbotEnabled, httpsEnabled)
      addDomain([
        host: fallbackHost,
        https: httpsEnabled,
        redirect: redirectEnabled,
        certbot: fallbackCertbot
      ])
    }
  }
  if (entries.isEmpty()) {
    System.err.println("Jellyfin apacheProxy requires either 'domains' or 'serverName'")
    System.exit(1)
  }
  entries
}

def ensureApacheProxy = { Map proxyCfg ->
  if (!boolFlag(proxyCfg.enabled, false)) {
    return false
  }
  def domainEntries = resolveProxyDomains(proxyCfg)
  def documentRoot = proxyCfg.documentRoot?.toString()?.trim() ?: "/var/www/hosts/jellyfin"
  def backendHost = proxyCfg.backendHost?.toString()?.trim() ?: "127.0.0.1"
  def backendPort = proxyCfg.backendPort?.toString()?.trim() ?: "8096"
  def websocketPath = proxyCfg.websocketPath?.toString()?.trim() ?: "/socket"

  runOrFail("DEBIAN_FRONTEND=noninteractive apt-get install -y apache2", "install apache2")
  runOrFail("a2enmod proxy proxy_http proxy_wstunnel rewrite", "enable apache proxy modules")

  runOrFail("mkdir -p ${shellQuote(documentRoot)}", "create document root")

  def wsBackend = "ws://${backendHost}:${backendPort}${websocketPath}"
  def httpBackend = "http://${backendHost}:${backendPort}/"
  def changed = false

  domainEntries.each { entry ->
    def host = entry.host
  def lines = []
  lines << "<VirtualHost *:80>"
  lines << "    ServerName ${host}"
  lines << "    DocumentRoot ${documentRoot}"
  lines << ""
  lines << "    ProxyPass \"${websocketPath}\" \"${wsBackend}\""
  lines << "    ProxyPassReverse \"${websocketPath}\" \"${wsBackend}\""
  lines << ""
  lines << "    ProxyPass \"/\" \"${httpBackend}\""
  lines << "    ProxyPassReverse \"/\" \"${httpBackend}\""
  lines << ""
  lines << "    ErrorLog ${entry.errorLog}"
  lines << "    CustomLog ${entry.accessLog} combined"
    lines << "</VirtualHost>"

    def slug = host.replaceAll('[^A-Za-z0-9._-]', '_')
    def siteName = "${slug}.conf"
    def sitePath = "/etc/apache2/sites-available/${siteName}"
    def siteFile = new File(sitePath)
    def content = lines.join("\n") + "\n"
    if (!siteFile.exists() || siteFile.text != content) {
      backup(sitePath)
      writeText(sitePath, content)
      println "Updating file content: ${sitePath}"
      changed = true
    }

    def enabledPath = "/etc/apache2/sites-enabled/${siteName}"
    def enabledFile = new File(enabledPath)
    if (!enabledFile.exists()) {
      runOrFail("a2ensite ${shellQuote(siteName)}", "enable apache site ${siteName}")
      changed = true
    }
  }

  def certbotEnabledGlobally = boolFlag(proxyCfg.certbotEnabled, true)
  def certbotEmail = proxyCfg.certbotEmail?.toString()?.trim()
  def certbotStaging = boolFlag(proxyCfg.certbotStaging, false)
  def certbotExtraArgs = proxyCfg.certbotExtraArgs
  def certbotHosts = domainEntries.findAll { it.https && it.certbot }.collect { it.host }
  def certbotChanged = false
  def certbotTargetHost = domainEntries[0]?.host ?: "jellyfin"
  def certbotRedirect = domainEntries.any { it.redirect }
  if (!certbotHosts.isEmpty() && certbotEnabledGlobally) {
    if (isBlank(certbotEmail)) {
      System.err.println("Jellyfin apacheProxy.certbotEmail is required when certbot is requested")
      System.exit(1)
    }
    def staging = certbotStaging
    runOrFail("DEBIAN_FRONTEND=noninteractive apt-get install -y certbot python3-certbot", "install certbot")
    def args = ["certbot", "--apache", "--non-interactive", "--agree-tos", "--email", certbotEmail]
    if (staging) {
      args << "--staging"
    }
    if (certbotRedirect) {
      args << "--redirect"
    }
    certbotHosts.each { host ->
      args << "-d"
      args << host
    }
    if (certbotExtraArgs instanceof Collection) {
      certbotExtraArgs.each { entry ->
        def token = entry?.toString()?.trim()
        if (token) {
          args << token
        }
      }
    } else if (certbotExtraArgs instanceof String) {
      certbotExtraArgs.split('\\s+').each { token ->
        if (token) {
          args << token
        }
      }
    }
    def cmd = args.collect { shellQuote(it) }.join(" ")
    runOrFail(cmd, "run certbot for ${certbotTargetHost}")
    certbotChanged = true
  }

  if (changed || certbotChanged) {
    runOrFail("systemctl reload apache2", "reload apache2")
  }
  return changed || certbotChanged
}

String release = cfg.release?.toString()?.trim()
if (isBlank(release)) {
  release = resolveRelease()
}
if (isBlank(release)) {
  System.err.println("Unable to determine release codename for Jellyfin repository")
  System.exit(1)
}

String repoUrl = (cfg.repoUrl?.toString()?.trim() ?: "https://repo.jellyfin.org/debian")
String keyUrl = (cfg.keyUrl?.toString()?.trim() ?: "https://repo.jellyfin.org/debian/jellyfin_team.gpg.key")
String keyringPath = "/usr/share/keyrings/jellyfin-archive-keyring.gpg"
String repoFile = "/etc/apt/sources.list.d/jellyfin.list"
String repoLine = "deb [signed-by=${keyringPath}] ${repoUrl} ${release} main"

boolean changed = false

def ensureKeyring = {
  def parent = new File(keyringPath).parentFile
  if (parent && !parent.exists()) {
    parent.mkdirs()
  }
  def tempKey = File.createTempFile("jellyfin-key", ".asc")
  def tempRing = new File("${keyringPath}.tmp")
  boolean updated = false
  try {
    runOrFail("curl -fsSL -o '${tempKey.path}' '${keyUrl}'", "download Jellyfin GPG key")
    runOrFail("gpg --dearmor --yes --output '${tempRing.path}' '${tempKey.path}'", "convert Jellyfin GPG key")
    def target = new File(keyringPath)
    byte[] existingBytes = target.exists() ? target.bytes : null
    byte[] newBytes = tempRing.exists() ? tempRing.bytes : null
    if (existingBytes == null || newBytes == null || !Arrays.equals(existingBytes, newBytes)) {
      backup(keyringPath)
      tempRing.withInputStream { ins ->
        target.withOutputStream { outs ->
          outs << ins
        }
      }
      updated = true
    }
  } finally {
    tempKey.delete()
    tempRing.delete()
  }
  if (updated) {
    println "Updating file content: ${keyringPath}"
  }
  return updated
}

def ensureRepoList = {
  def file = new File(repoFile)
  def content = repoLine + "\n"
  if (!file.exists() || file.text != content) {
    backup(repoFile)
    writeText(repoFile, content)
    println "Updating file content: ${repoFile}"
    return true
  }
  return false
}

boolean keyChanged = ensureKeyring()
boolean repoChanged = ensureRepoList()

if (keyChanged || repoChanged) {
  runOrFail("DEBIAN_FRONTEND=noninteractive apt-get update -y", "apt-get update")
  changed = true
}

def packages = determinePackages()
def missingPackages = packages.findAll { pkg ->
  sh("dpkg -s ${pkg} >/dev/null 2>&1").code != 0
}
if (!missingPackages.isEmpty()) {
  runOrFail("DEBIAN_FRONTEND=noninteractive apt-get install -y ${missingPackages.join(' ')}", "apt-get install Jellyfin packages")
  changed = true
}

runOrFail("systemctl enable --now jellyfin", "enable jellyfin service")
changed = ensureApacheProxy((cfg.apacheProxy instanceof Map) ? (Map) cfg.apacheProxy : [:]) || changed

System.exit(changed ? 10 : 0)
