#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install Docker Engine from the official Docker apt repository.
// Config keys: none
// Notes: Adds Docker apt repo, installs engine components, and enables the service.

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

if (sh("which docker").code==0) System.exit(0)
def cmds = [
  'sudo apt install -y ca-certificates curl gnupg',
  'sudo install -m 0755 -d /etc/apt/keyrings',
  'curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg',
  'sudo chmod a+r /etc/apt/keyrings/docker.gpg',
  'bash -lc \'echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" | sudo tee /etc/apt/sources.list.d/docker.list >/dev/null\'',
  'sudo apt update',
  'sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin',
  'sudo systemctl enable --now docker'
]
for (c in cmds) { if (sh(c).code!=0) System.exit(1) }
System.exit(10)
