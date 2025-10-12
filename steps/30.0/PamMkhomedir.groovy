#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Enforce pam_mkhomedir so home directories are created on login.
// Config keys: none
// Notes: Installs required PAM modules and ensures session lines exist once.

def sh(String cmd) {
  def p = ["bash","-lc",cmd].execute()
  def out = new StringBuffer(); def err = new StringBuffer()
  p.consumeProcessOutput(out, err); p.waitFor()
  [code:p.exitValue(), out:out.toString().trim(), err:err.toString().trim()]
}
def writeText(String path, String content) { new File(path).withWriter { it << content } }
def backup(String path) {
  def src = new File(path)
  if (!src.exists()) return null
  def bak = path + ".bak." + System.currentTimeMillis()
  src.withInputStream{ i -> new File(bak).withOutputStream{ o -> o << i } }
  return bak
}

def changed=false
def runOrFail = { String cmd ->
  def result = sh(cmd)
  if (result.code != 0) {
    System.err.println("Command failed (${cmd}):")
    if (result.out) System.err.println(result.out)
    if (result.err) System.err.println(result.err)
    System.exit(1)
  }
  result
}
def mkhomedirModule = new File("/lib/x86_64-linux-gnu/security/pam_mkhomedir.so")
if (!mkhomedirModule.exists()) {
  runOrFail("sudo apt update -y && sudo apt install -y libpam-modules libpam-modules-extra")
  if (!mkhomedirModule.exists()) {
    System.err.println("pam_mkhomedir.so still missing after install")
    System.exit(1)
  }
  changed=true
}
def enforce = { path ->
  def file = new File(path); if (!file.exists()) return
  def original = file.text
  def patternOptional = ~/^\s*session\s+optional\s+pam_systemd\.so.*$/
  def patternRequired = ~/^\s*session\s+required\s+pam_mkhomedir\.so.*$/
  def lines = original.readLines()
  def seenOptional = false
  def seenRequired = false
  def processed = []
  lines.each { line ->
    if (line ==~ patternOptional) {
      if (!seenOptional) {
        processed << line
        seenOptional = true
      } else {
        changed = true
      }
    } else if (line ==~ patternRequired) {
      if (!seenRequired) {
        processed << line
        seenRequired = true
      } else {
        changed = true
      }
    } else {
      processed << line
    }
  }
  if (!seenOptional) {
    processed << "session optional pam_systemd.so"
    changed = true
  }
  if (!seenRequired) {
    processed << "session required pam_mkhomedir.so skel=/etc/skel umask=0022"
    changed = true
  }
  def updated = processed.join("\n")
  if (!updated.endsWith("\n")) {
    updated += "\n"
  }
  if (updated != original) {
    backup(path)
    writeText(path, updated)
  }
}
["/etc/pam.d/common-session","/etc/pam.d/common-session-noninteractive"].each{ enforce(it) }
System.exit(changed?10:0)
