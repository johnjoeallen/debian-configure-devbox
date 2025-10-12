#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Ensure systemd-timesyncd is installed and enabled for NTP synchronization.
// Config keys: none
// Notes: Installs the package if missing and enables the service immediately.

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
if (sh("dpkg -s systemd-timesyncd >/dev/null 2>&1").code!=0) {
  if (sh("sudo apt install -y systemd-timesyncd").code!=0) System.exit(1)
  changed=true
}
if (sh("systemctl is-enabled systemd-timesyncd || true").out != "enabled") {
  if (sh("sudo systemctl enable --now systemd-timesyncd").code!=0) System.exit(1)
  changed=true
}
System.exit(changed?10:0)
