#!/usr/bin/env groovy
// RUN_VIA_SUDO
// --- Documentation ---
// Summary: Install an NFS server and publish configured directories.
// Config keys: exports (list), defaultOptions (string)
// Notes: Writes /etc/exports, ensures each directory exists, and reloads the NFS export table.

import lib.ConfigLoader
import static lib.StepUtils.backup
import static lib.StepUtils.sh
import static lib.StepUtils.writeText

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

final String stepKey = "NfsServer"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}

Map cfg = ConfigLoader.stepConfig(stepKey)
def normalizeClients = { Object value ->
  def out = []
  if (value instanceof Collection) {
    value.each { entry ->
      def text = entry?.toString()?.trim()
      if (text) {
        out << text
      }
    }
  } else if (value != null) {
    value.toString().split('[,\\s]+').each { token ->
      def text = token?.trim()
      if (text) {
        out << text
      }
    }
  }
  out
}

def exportsCfg = cfg.exports instanceof Collection ? (Collection) cfg.exports : []
if (exportsCfg.isEmpty()) {
  println "No exports configured for ${stepKey}; skipping"
  System.exit(0)
}

String defaultOptions = cfg.defaultOptions?.toString()?.trim() ?: "rw,sync,no_subtree_check,no_root_squash"

def ensurePackages = {
  def required = ["nfs-kernel-server"]
  def missing = required.findAll { pkg -> sh("dpkg -s ${pkg} >/dev/null 2>&1").code != 0 }
  if (!missing.isEmpty()) {
    def update = sh("DEBIAN_FRONTEND=noninteractive apt-get update -y")
    if (update.code != 0) {
      System.err.println("apt-get update failed")
      System.exit(1)
    }
    def install = sh("DEBIAN_FRONTEND=noninteractive apt-get install -y ${missing.join(' ')}")
    if (install.code != 0) {
      System.err.println("apt-get install failed")
      System.exit(1)
    }
  }
}

def ensureDir = { String path ->
  Path dir = Paths.get(path)
  if (!Files.exists(dir)) {
    Files.createDirectories(dir)
  }
}

def ensureFile = { String path, String content ->
  File file = new File(path)
  if (!file.exists() || file.text != content) {
    backup(path)
    writeText(path, content)
    println "Updating file content: ${path}"
  }
}

ensurePackages()

def exportLines = []
exportsCfg.eachWithIndex { entry, idx ->
  if (!(entry instanceof Map)) {
    System.err.println("Export entry ${idx + 1} must be a map with 'path' and 'clients'")
    System.exit(1)
  }
  def path = entry.path?.toString()?.trim()
  if (!path) {
    System.err.println("Export entry ${idx + 1} missing 'path'")
    System.exit(1)
  }
  def clients = normalizeClients(entry.clients)
  if (clients.isEmpty()) {
    System.err.println("Export entry ${idx + 1} missing 'clients'")
    System.exit(1)
  }
  clients.each { client ->
    def opts = entry.options?.toString()?.trim() ?: defaultOptions
    exportLines << "${path} ${client}(${opts})"
  }
  ensureDir(path)
}

if (exportLines.isEmpty()) {
  println "No export lines generated for ${stepKey}; skipping"
  System.exit(0)
}

def exportsPath = "/etc/exports"
def sortedLines = exportLines.sort()
def exportsContent = sortedLines.join("\n") + "\n"
ensureFile(exportsPath, exportsContent)

def reload = sh("exportfs -ra")
if (reload.code != 0) {
  System.err.println("exportfs failed")
  if (reload.out) System.err.println(reload.out)
  if (reload.err) System.err.println(reload.err)
  System.exit(1)
}

def enable = sh("systemctl enable --now nfs-server")
if (enable.code != 0) {
  System.err.println("Failed to enable nfs-server service")
  if (enable.out) System.err.println(enable.out)
  if (enable.err) System.err.println(enable.err)
  System.exit(1)
}

System.exit(10)
