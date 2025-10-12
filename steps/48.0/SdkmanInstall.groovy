#!/usr/bin/env groovy
// --- Documentation ---
// Summary: Install SDKMAN in the invoking user's home directory.
// Config keys: none
// Notes: Uses non-interactive install and enforces helpful SDKMAN settings.

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
def stepKey = "sdkmanInstall"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}

def home = System.getenv("HOME")
if (!home) {
  System.err.println("HOME not set; aborting")
  System.exit(1)
}

def sdkDir = new File("${home}/.sdkman")
if (sdkDir.exists()) {
  println "SDKMAN already installed at ${sdkDir}"
  System.exit(0)
}

def install = sh("bash -lc 'export SDKMAN_DIR=\"${home}/.sdkman\"; export SDKMAN_NON_INTERACTIVE=true; curl -s \"https://get.sdkman.io\" | bash'")
if (install.code != 0) {
  System.err.println("Failed to install SDKMAN:")
  if (install.out) System.err.println(install.out)
  if (install.err) System.err.println(install.err)
  System.exit(1)
}

def configFile = new File("${home}/.sdkman/etc/config")
if (configFile.exists()) {
  def lines = configFile.readLines()
  def updated = false
  def ensureSetting = { key, value ->
    def prefix = "${key}="
    def idx = lines.findIndexOf { it.startsWith(prefix) }
    def desired = "${key}=${value}"
    if (idx >= 0) {
      if (lines[idx] != desired) {
        lines[idx] = desired
        updated = true
      }
    } else {
      lines << desired
      updated = true
    }
  }
  ensureSetting('sdkman_auto_answer', 'true')
  ensureSetting('sdkman_selfupdate_feature', 'true')
  if (updated) {
    configFile.text = lines.join('\n') + '\n'
  }
}

System.exit(10)
