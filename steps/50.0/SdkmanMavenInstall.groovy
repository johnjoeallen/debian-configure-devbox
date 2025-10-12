#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install Maven via SDKMAN and set the requested default version.
// Config keys: version (string)
// Notes: Skips if SDKMAN is not present and only installs when the version differs.

def sh(String cmd) {
  def p = ["bash","-lc",cmd].execute()
  def out = new StringBuffer(); def err = new StringBuffer()
  p.consumeProcessOutput(out, err); p.waitFor()
  [code:p.exitValue(), out:out.toString().trim(), err:err.toString().trim()]
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
def stepKey = "sdkmanMaven"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

def home = System.getenv("HOME")
if (!home) {
  System.err.println("HOME not set; aborting")
  System.exit(1)
}

if (!new File("${home}/.sdkman").exists()) {
  println "SDKMAN not installed. Skipping Maven install."
  System.exit(0)
}

def init = "source ${home}/.sdkman/bin/sdkman-init.sh"

def runOrFail = { String cmd ->
  def res = sh(cmd)
  if (res.code != 0) {
    System.err.println("Command failed (${cmd}):")
    if (res.out) System.err.println(res.out)
    if (res.err) System.err.println(res.err)
    System.exit(1)
  }
  res
}

def changed = false

def mavenVersion = stepConfig.version?.toString()?.trim()

def ensureCurrent = {
  def current = sh("bash -lc '${init} && sdk current maven'")
  return current.code == 0 && current.out
}

if (mavenVersion) {
  def versionDir = new File("${home}/.sdkman/candidates/maven/${mavenVersion}")
  if (!versionDir.exists()) {
    runOrFail("bash -lc '${init} && sdk install maven ${mavenVersion}'")
    changed = true
  }
  def current = sh("bash -lc '${init} && sdk current maven'")
  if (current.code != 0 || !current.out.contains(mavenVersion)) {
    runOrFail("bash -lc '${init} && sdk default maven ${mavenVersion}'")
    changed = true
  }
} else {
  if (!ensureCurrent()) {
    runOrFail("bash -lc '${init} && sdk install maven'")
    if (!ensureCurrent()) {
      System.err.println("Failed to confirm Maven installation")
      System.exit(1)
    }
    changed = true
  }
}

System.exit(changed ? 10 : 0)
