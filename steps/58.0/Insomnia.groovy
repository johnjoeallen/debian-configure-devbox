#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install the Insomnia desktop client at a pinned version.
// Config keys: none
// Notes: Installs runtime dependencies and fetches the .deb from GitHub releases.

def sh(String cmd) {
  def p = ["bash","-lc",cmd].execute()
  def out = new StringBuffer(); def err = new StringBuffer()
  p.consumeProcessOutput(out, err); p.waitFor()
  [code:p.exitValue(), out:out.toString().trim(), err:err.toString().trim()]
}

def runOrFail = { String cmd, String context ->
  def res = sh(cmd)
  if (res.code != 0) {
    System.err.println("${context} failed")
    if (res.out) System.err.println(res.out)
    if (res.err) System.err.println(res.err)
    System.exit(1)
  }
  res
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
def stepKey = "insomnia"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}

def targetVersion = "11.6.1"
def installedVersion = sh("dpkg-query -W -f='\${Version}' insomnia 2>/dev/null || true")
if (installedVersion.code == 0 && installedVersion.out?.trim()) {
  if (installedVersion.out.trim().startsWith(targetVersion)) {
    println "Insomnia ${targetVersion} already installed."
    System.exit(0)
  }
}

boolean changed = false

runOrFail("apt-get update -y", "apt-get update")

def dependencies = [
  "libgtk-3-0",
  "libnotify4",
  "libnss3",
  "libxss1",
  "libxtst6",
  "xdg-utils",
  "libatspi2.0-0",
  "libuuid1",
  "libsecret-1-0"
].join(' ')
runOrFail("DEBIAN_FRONTEND=noninteractive apt-get install -y ${dependencies}", "Dependency installation")

String debFile = "/tmp/Insomnia.Core-${targetVersion}.deb"
String url = "https://github.com/Kong/insomnia/releases/download/core%40${targetVersion}/Insomnia.Core-${targetVersion}.deb"
runOrFail("wget -O '${debFile}' '${url}'", "Download Insomnia ${targetVersion}")

def installCmd = "DEBIAN_FRONTEND=noninteractive apt-get install -y '${debFile}'"
runOrFail(installCmd, "Install Insomnia ${targetVersion}")
changed = true

sh("rm -f '${debFile}'")

println "Installed Insomnia ${targetVersion}."

System.exit(changed ? 10 : 0)
