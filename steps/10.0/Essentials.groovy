#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install baseline CLI packages required for provisioning.
// Config keys: packages (list of package names)
// Notes: Uses apt to install any missing packages only.

import lib.ConfigLoader
import static lib.StepUtils.sh

def stepKey = "essentials"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

def defaultPackages = ["curl", "wget", "zip", "unzip", "rsync", "ca-certificates", "gnupg", "apt-transport-https"]

def collectPackages = { value ->
  def collected = []
  if (value instanceof Collection) {
    value.each { pkg ->
      def name = pkg?.toString()?.trim()
      if (name) {
        collected << name
      }
    }
  } else if (value != null) {
    def name = value.toString().trim()
    if (name) {
      collected << name
    }
  }
  collected
}

def packages = new LinkedHashSet<String>()
def configuredPackages = collectPackages(stepConfig.packages)
if (configuredPackages) {
  packages.addAll(configuredPackages)
} else {
  packages.addAll(defaultPackages)
}

if (packages.isEmpty()) {
  println "No packages defined for ${stepKey}; skipping"
  System.exit(0)
}

def dpkgStatusFormat = "\${Status}"

def missing = packages.findAll { pkg ->
  def status = sh("dpkg-query -W -f='${dpkgStatusFormat}' ${pkg} 2>/dev/null")
  if (status.code != 0) {
    return true
  }
  def normalized = status.out?.trim()?.toLowerCase()
  return normalized != 'install ok installed'
}

if (missing) {
  def installCmd = "sudo apt update && sudo apt install -y ${missing.join(' ')}"
  def result = sh(installCmd)
  if (result.code != 0) {
    if (result.out) {
      System.err.println(result.out)
    }
    if (result.err) {
      System.err.println(result.err)
    }
    System.exit(1)
  }
  System.exit(10)
}

System.exit(0)
