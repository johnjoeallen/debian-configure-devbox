#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install Samba and configure share-level access with defined shares.
// Config keys: SambaShares (map)
// Notes: Writes /etc/samba/smb.conf, ensures Samba users, and restarts services when needed.

import lib.ConfigLoader
import static lib.StepUtils.backup
import static lib.StepUtils.sh
import static lib.StepUtils.writeText

import java.security.SecureRandom

final String stepKey = "SambaShares"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
Map cfg = ConfigLoader.stepConfig(stepKey)

def log = { String msg -> println "== ${msg}" }
def fail = { String msg ->
  System.err.println("ERROR: ${msg}")
  System.exit(1)
}

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

def normalizeList = { Object value ->
  def out = []
  if (value instanceof Collection) {
    value.each { entry ->
      def text = entry?.toString()?.trim()
      if (text) {
        out << text
      }
    }
  } else if (value != null) {
    value.toString().split(",").each { token ->
      def text = token.trim()
      if (text) {
        out << text
      }
    }
  }
  out
}

def ensurePackages = {
  def required = ["samba", "samba-common-bin"]
  def missing = required.findAll { pkg -> sh("dpkg -s ${pkg} >/dev/null 2>&1").code != 0 }
  if (!missing.isEmpty()) {
    log("Installing Samba packages: ${missing.join(' ')}")
    def result = sh("apt-get update")
    if (result.code != 0) {
      fail("apt-get update failed")
    }
    result = sh("apt-get install -y ${missing.join(' ')}")
    if (result.code != 0) {
      fail("apt-get install failed")
    }
  }
}

def ensureUserExists = { String user ->
  if (sh("id -u ${user} >/dev/null 2>&1").code != 0) {
    fail("User '${user}' does not exist")
  }
}

def generatePassword = {
  def random = new SecureRandom()
  def chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
  def sb = new StringBuilder()
  16.times {
    sb.append(chars.charAt(random.nextInt(chars.length())))
  }
  sb.toString()
}

def ensureSambaUser = { Map entry ->
  def name = entry.name?.toString()?.trim()
  if (!name) {
    fail("Samba user entry missing name")
  }
  ensureUserExists(name)

  def exists = sh("pdbedit -L -u ${name} >/dev/null 2>&1").code == 0
  if (exists) {
    return
  }

  def password = entry.password?.toString()
  if (password) {
    def safe = password.replace("'", "'\\''")
    def cmd = "printf '%s\\n%s\\n' '${safe}' '${safe}' | smbpasswd -s -a ${name}"
    def result = sh(cmd)
    if (result.code != 0) {
      fail("Failed to create Samba user '${name}'")
    }
    return
  }

  def generated = generatePassword()
  log("Generated Samba password for '${name}': ${generated}")
  def safe = generated.replace("'", "'\\''")
  def cmd = "printf '%s\\n%s\\n' '${safe}' '${safe}' | smbpasswd -s -a ${name}"
  def result = sh(cmd)
  if (result.code != 0) {
    fail("Failed to create Samba user '${name}'")
  }
}

def buildGlobalSection = {
  def workgroup = (cfg.workgroup ?: "WORKGROUP").toString().trim()
  def serverString = (cfg.serverString ?: "Samba Server").toString().trim()
  def disablePrinters = boolFlag(cfg.disablePrinters, true)

  def lines = []
  lines << "[global]"
  lines << "  workgroup = ${workgroup}"
  lines << "  server string = ${serverString}"
  lines << "  server role = standalone server"
  lines << "  security = user"
  lines << "  passdb backend = tdbsam"
  lines << "  obey pam restrictions = yes"
  lines << "  unix password sync = yes"
  lines << "  pam password change = yes"
  lines << "  logging = file"
  lines << "  log file = /var/log/samba/log.%m"
  lines << "  max log size = 1000"
  if (disablePrinters) {
    lines << "  load printers = no"
    lines << "  printing = bsd"
    lines << "  printcap name = /dev/null"
    lines << "  disable spoolss = yes"
  }
  lines.join("\n")
}

def buildShareSection = { Map share ->
  def name = share.name?.toString()?.trim()
  if (!name) {
    fail("Share entry missing name")
  }
  def path = share.path?.toString()?.trim()
  if (!path) {
    fail("Share '${name}' missing path")
  }

  def lines = []
  lines << "[${name}]"
  lines << "  path = ${path}"
  if (share.comment) {
    lines << "  comment = ${share.comment.toString().trim()}"
  }
  def browseable = boolFlag(share.browseable, true)
  def readOnly = boolFlag(share.readOnly, false)
  lines << "  browseable = ${browseable ? 'yes' : 'no'}"
  lines << "  read only = ${readOnly ? 'yes' : 'no'}"

  def validUsers = normalizeList(share.validUsers)
  if (!validUsers.isEmpty()) {
    lines << "  valid users = ${validUsers.join(' ')}"
  }
  def writeList = normalizeList(share.writeList)
  if (!writeList.isEmpty()) {
    lines << "  write list = ${writeList.join(' ')}"
  }
  if (share.forceUser) {
    lines << "  force user = ${share.forceUser.toString().trim()}"
  }
  if (share.forceGroup) {
    lines << "  force group = ${share.forceGroup.toString().trim()}"
  }
  if (share.createMask) {
    lines << "  create mask = ${share.createMask.toString().trim()}"
  }
  if (share.directoryMask) {
    lines << "  directory mask = ${share.directoryMask.toString().trim()}"
  }
  lines.join("\n")
}

def unwrapEntry = { Map entry, String wrapperKey ->
  if (entry.containsKey(wrapperKey)) {
    def inner = entry[wrapperKey]
    if (!(inner instanceof Map)) {
      fail("${wrapperKey} entry must be a map")
    }
    return inner as Map
  }
  entry
}

ensurePackages()

def shares = cfg.shares instanceof Collection ? (Collection) cfg.shares : []
if (shares.isEmpty()) {
  fail("No shares defined for ${stepKey}")
}

def shareUsers = cfg.sambaUsers instanceof Collection ? (Collection) cfg.sambaUsers : []

shareUsers.each { entry ->
  if (!(entry instanceof Map)) {
    fail("Samba user entries must be maps with name/password")
  }
  def userEntry = unwrapEntry(entry as Map, "user")
  ensureSambaUser(userEntry)
}

def contentSections = []
contentSections << buildGlobalSection()
shares.each { share ->
  if (!(share instanceof Map)) {
    fail("Share entries must be maps with name/path")
  }
  def shareEntry = unwrapEntry(share as Map, "share")
  contentSections << buildShareSection(shareEntry)
}
def smbConf = contentSections.join("\n\n") + "\n"

def smbConfPath = "/etc/samba/smb.conf"
def current = new File(smbConfPath)
boolean changed = false
if (!current.exists() || current.text != smbConf) {
  backup(smbConfPath)
  writeText(smbConfPath, smbConf)
  changed = true
}

def test = sh("testparm -s ${smbConfPath} >/dev/null 2>&1")
if (test.code != 0) {
  fail("smb.conf validation failed (testparm)")
}

def enable = sh("systemctl enable --now smbd nmbd")
if (enable.code != 0) {
  fail("Failed to enable Samba services")
}

if (changed) {
  def restart = sh("systemctl restart smbd nmbd")
  if (restart.code != 0) {
    fail("Failed to restart Samba services")
  }
  System.exit(10)
}

System.exit(0)
