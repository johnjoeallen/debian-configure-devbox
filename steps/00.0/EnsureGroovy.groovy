#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Ensure Groovy is present so provisioning scripts can execute.
// Config keys: none
// Notes: Installs Groovy via apt if the command is missing.

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

def have = sh("command -v groovy || true").out
if (have) {
  println "Groovy OK: " + sh("groovy -version").out
  System.exit(0)
}
println "Groovy missing (unexpected here). Installing via apt…"
def r = sh("sudo apt update -y && sudo apt install -y groovy")
if (r.code != 0) {
  System.err.println("Install failed: "+r.err)
  System.exit(1)
}
println "Installed: " + sh("groovy -version").out
System.exit(10)
