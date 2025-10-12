#!/usr/bin/env groovy
// --- Documentation ---
// Summary: Ensure the invoking user belongs to the docker Unix group.
// Config keys: none
// Notes: Adds the group if needed and reminds the user to re-login for membership.

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

def user = System.getenv("USER")
def inGroup = (sh("id -nG ${user} | tr ' ' '\\n' | grep -qx docker").code==0)
if (inGroup) System.exit(0)
if (sh("sudo groupadd -f docker").code!=0) System.exit(1)
if (sh("sudo usermod -aG docker ${user}").code!=0) System.exit(1)
println "Added ${user} to docker group. Log out/in to apply."
System.exit(10)
