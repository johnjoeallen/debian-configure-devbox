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

System.exit(changed ? 10 : 0)
