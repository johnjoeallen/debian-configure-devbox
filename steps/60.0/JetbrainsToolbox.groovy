#!/usr/bin/env groovy
// --- Documentation ---
// Summary: Install JetBrains Toolbox launcher for managing IDEs.
// Config keys: none
// Notes: Downloads the latest tarball, extracts, and runs the installer once.

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

def home = System.getenv("HOME")
def marker = new File("${home}/.local/share/JetBrains/Toolbox")
if (marker.exists()) System.exit(0)
def r = sh('bash -lc \'TOOLBOX_URL="https://data.services.jetbrains.com/products/download?code=TBA&platform=linux"; wget -O /tmp/toolbox.tar.gz "$TOOLBOX_URL"\'')
if (r.code!=0) { System.err.println(r.err); System.exit(1) }
def r2 = sh('bash -lc \'mkdir -p /tmp/jb && tar -xzf /tmp/toolbox.tar.gz -C /tmp/jb && cd /tmp/jb/jetbrains-toolbox* && nohup ./bin/jetbrains-toolbox >/dev/null 2>&1 &\'')
if (r2.code!=0) { System.err.println(r2.err); System.exit(1) }
System.exit(10)
