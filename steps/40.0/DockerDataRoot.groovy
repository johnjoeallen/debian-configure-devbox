#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Move Docker's data-root to the configured path and update daemon.json.
// Config keys: target (string)
// Notes: Stops Docker, copies data, rewrites config, then restarts the service.

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

def loadConfigLoader = {
  def scriptDir = new File(getClass().protectionDomain.codeSource.location.toURI()).parentFile
  def loader = new GroovyClassLoader(getClass().classLoader)
  def configPath = scriptDir.toPath().resolve("../../lib/ConfigLoader.groovy").normalize().toFile()
  if (!configPath.exists()) {
    System.err.println("Missing ConfigLoader at ${configPath}")
    System.exit(1)
  }
  loader.parseClass(configPath)
}

def ConfigLoader = loadConfigLoader()
def stepKey = "dockerDataRoot"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

def target = stepConfig.target?.toString()?.trim()
if (!target) {
  target = "/data/docker"
}

def need = sh("""bash -lc 'docker info 2>/dev/null | grep -q "Docker Root Dir: ${target}"'""").code!=0
if (!need) System.exit(0)

def cmds = [
  "sudo systemctl stop docker",
  "sudo mkdir -p '${target}'",
  "sudo rsync -aP /var/lib/docker/ '${target}/'",
  "sudo mv /var/lib/docker /var/lib/docker.bak.\$(date +%s)",
  "sudo install -d -m 0711 -o root -g root /etc/docker",
  """bash -lc 'cat > /etc/docker/daemon.json <<EOF
{
  "data-root": "${target}",
  "log-driver": "local",
  "log-opts": {"max-size":"100m","max-file":"3"},
  "storage-driver": "overlay2"
}
EOF'""",
  "sudo systemctl daemon-reload",
  "sudo systemctl start docker"
]
for (c in cmds) { if (sh(c).code!=0) System.exit(1) }
println sh("""bash -lc 'docker info | grep -E "Docker Root Dir|Storage Driver|Logging Driver"'""").out
System.exit(10)
