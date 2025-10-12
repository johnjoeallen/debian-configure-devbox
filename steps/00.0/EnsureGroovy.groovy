#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Ensure Groovy is present so provisioning scripts can execute.
// Config keys: none
// Notes: Installs Groovy via apt if the command is missing.

import static lib.StepUtils.sh

def have = sh("command -v groovy || true").out
if (have) {
  println "Groovy OK ✅: " + sh("groovy -version").out
  System.exit(0)
}
println "Groovy missing (unexpected here). Installing via apt… 🚧"
def r = sh("sudo apt update -y && sudo apt install -y groovy")
if (r.code != 0) {
  System.err.println("Install failed: "+r.err)
  System.exit(1)
}
println "Installed: " + sh("groovy -version").out + " 🎉"
System.exit(10)
