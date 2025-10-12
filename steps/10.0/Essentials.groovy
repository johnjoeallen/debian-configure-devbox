#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install baseline CLI packages required for provisioning.
// Config keys: none
// Notes: Uses apt to install any missing packages only.

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

def pkgs = ["curl","wget","zip","unzip","rsync","ca-certificates","gnupg","apt-transport-https"]
def need = pkgs.findAll { sh("dpkg -s ${it} >/dev/null 2>&1").code!=0 }
if (need) {
  def r = sh("sudo apt update && sudo apt install -y " + need.join(" "))
  if (r.code!=0) { System.err.println(r.err); System.exit(1) }
  System.exit(10)
}
System.exit(0)
