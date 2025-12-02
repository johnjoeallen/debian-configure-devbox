#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install Google Chrome Stable from Google's apt repository.
// Config keys: GoogleChromeInstall (map)
// Notes: Adds signing key, ensures repo list, installs package, and leaves consistent logging.

import lib.ConfigLoader
import static lib.StepUtils.sh

final String stepKey = "GoogleChromeInstall"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}

boolean alreadyInstalled = sh("dpkg -s google-chrome-stable >/dev/null 2>&1").code == 0
if (alreadyInstalled) {
  println "Google Chrome already installed."
  System.exit(0)
}

def ensureKey = {
  def keyPath = "/etc/apt/trusted.gpg.d/google-chrome.gpg"
  sh("install -d -m 0755 -o root -g root /etc/apt/trusted.gpg.d")
  def needKey = sh("test -f '${keyPath}'").code != 0
  if (needKey) {
    def fetchKey = sh("curl -fsSL https://dl.google.com/linux/linux_signing_key.pub | gpg --dearmor -o '${keyPath}'")
    if (fetchKey.code != 0) {
      System.err.println("Failed to install Google signing key.")
      if (fetchKey.out) System.err.println(fetchKey.out)
      if (fetchKey.err) System.err.println(fetchKey.err)
      System.exit(1)
    }
  }
}

def ensureRepo = {
  def listPath = "/etc/apt/sources.list.d/google-chrome.list"
  def desired = "deb [arch=amd64 signed-by=/etc/apt/trusted.gpg.d/google-chrome.gpg] https://dl.google.com/linux/chrome/deb/ stable main\n"
  def current = new File(listPath)
  sh("install -d -m 0755 -o root -g root /etc/apt/sources.list.d")
  if (!current.exists() || current.text != desired) {
    def writeCmd = """cat <<'EOF' | tee ${listPath} >/dev/null
${desired.trim()}
EOF"""
    def write = sh(writeCmd)
    if (write.code != 0) {
      System.err.println("Failed to write Google Chrome apt repo.")
      if (write.out) System.err.println(write.out)
      if (write.err) System.err.println(write.err)
      System.exit(1)
    }
  }
}

ensureKey()
ensureRepo()

if (sh("apt-get update -y").code != 0) {
  System.err.println("apt-get update failed for Google Chrome")
  System.exit(1)
}

if (sh("DEBIAN_FRONTEND=noninteractive apt-get install -y google-chrome-stable").code != 0) {
  System.err.println("Failed to install google-chrome-stable")
  System.exit(1)
}

println "Installed Google Chrome Stable. 🎉"
System.exit(10)
