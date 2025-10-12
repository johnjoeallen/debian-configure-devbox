#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Append NIS lookups to nsswitch.conf once NIS is configured.
// Config keys: none (enable or disable the step only)
// Notes: Requires nisSetup to provide domain/server and restarts ypbind/nscd.

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
if (!ConfigLoader.stepEnabled("nisSetup")) {
  println "nisSetup disabled; skipping nsswitch adjustments."
  System.exit(0)
}
def stepKey = "nsswitch"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def nisConfig = ConfigLoader.stepConfig("nisSetup")
def configDomain = nisConfig.domain?.toString()?.trim()
def configServer = nisConfig.server?.toString()?.trim()

def domainResult = sh("domainname || true").out?.trim()
boolean systemDomainSet = domainResult &&
  !domainResult.equalsIgnoreCase("(none)") &&
  !domainResult.equalsIgnoreCase("localdomain")

boolean systemServerSet = false
def ypConf = new File("/etc/yp.conf")
if (ypConf.exists()) {
  ypConf.readLines().each { raw ->
    def cleaned = raw.replaceAll(/#.*/, "").trim()
    if (!cleaned) {
      return
    }
    if (cleaned.startsWith("ypserver ")) {
      systemServerSet = true
      return
    }
    if (cleaned.startsWith("domain ") && cleaned.contains(" server ")) {
      systemServerSet = true
      systemDomainSet = true
    }
  }
}

boolean haveDomain = (configDomain && !configDomain.isEmpty()) || systemDomainSet
boolean haveServer = (configServer && !configServer.isEmpty()) || systemServerSet
if (!haveDomain || !haveServer) {
  println "NIS domain/server not detected; skipping nsswitch adjustments."
  System.exit(0)
}

def nss = "/etc/nsswitch.conf"
def f = new File(nss)
if (!f.exists()) { System.err.println("Missing "+nss); System.exit(1) }
def original = f.text
def lines = original.readLines()
def changed=false
def ensureKey = { key ->
  int idx = lines.findIndexOf { it.startsWith("${key}:") }
  if (idx >= 0) {
    if (!lines[idx].contains("nis")) {
      lines[idx] = lines[idx] + " nis"
      changed = true
    }
  } else {
    lines << "${key}: files nis"
    changed = true
  }
}
["passwd","group","shadow","netgroup"].each { ensureKey(it) }
if (!lines.any { it.startsWith("initgroups:") && it.contains("nis") }) {
  lines << "initgroups: files nis"
  changed = true
}
if (changed) {
  def content = lines.join("\n")
  if (!content.endsWith("\n")) {
    content += "\n"
  }
  backup(nss)
  writeText(nss, content)
}
sh("sudo systemctl restart ypbind")
sh("sudo systemctl restart nscd 2>/dev/null || true")
System.exit(changed?10:0)
