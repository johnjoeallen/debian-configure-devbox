#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Create a passwordless admin sudo group and add configured users.
// Config keys: adminGroup (string), addCurrentUser (boolean), users (list of usernames)
// Notes: Validates sudoers syntax after writing drop-in files.

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
def stepKey = "adminGroupNopass"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

def adminGroup = stepConfig.adminGroup?.toString()?.trim()
if (!adminGroup) {
  adminGroup = "admin"
}
def addCurrentUser = stepConfig.containsKey('addCurrentUser') ? (stepConfig.addCurrentUser != false) : true

def collectUsers = { value ->
  def result = []
  if (value instanceof Collection) {
    value.each { v ->
      def s = v?.toString()?.trim()
      if (s) result << s
    }
  } else if (value != null) {
    def s = value.toString().trim()
    if (s) result << s
  }
  result
}

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

def changed=false
if (sh("getent group ${adminGroup}").code != 0) {
  runOrFail("sudo groupadd ${adminGroup}")
  changed = true
}

def sudoersMain = new File("/etc/sudoers")
def hasSudoLine = sudoersMain.exists() && sudoersMain.readLines().any { line ->
  line.trim() ==~ /^%sudo\s+ALL=\(ALL(?::ALL)?\)\s+ALL$/
}
if (!hasSudoLine) {
  def sudoFilePath = "/etc/sudoers.d/00-sudo-group"
  def sudoContent = "%sudo ALL=(ALL:ALL) ALL\n"
  def sudoFile = new File(sudoFilePath)
  if (!sudoFile.exists() || sudoFile.text != sudoContent) {
    backup(sudoFilePath)
    writeText(sudoFilePath, sudoContent)
    runOrFail("sudo chmod 0440 ${sudoFilePath}")
    changed = true
  }
}

def legacyPath = "/etc/sudoers.d/nopass"
def legacyFile = new File(legacyPath)
if (legacyFile.exists() && legacyFile.text.contains("%sysadmin")) {
  backup(legacyPath)
  if (!legacyFile.delete()) {
    System.err.println("Unable to remove legacy sudoers file: ${legacyPath}")
    System.exit(1)
  }
  changed = true
}

def path="/etc/sudoers.d/99-admin-nopass"
def content="%${adminGroup} ALL=(ALL) NOPASSWD: ALL\n"
def file = new File(path)
if (!file.exists() || file.text != content) {
  backup(path)
  writeText(path, content)
  runOrFail("sudo chmod 0440 ${path}")
  changed=true
}

def desiredUsers = new LinkedHashSet<String>(collectUsers(stepConfig.users))
if (addCurrentUser) {
  def targetUser = System.getenv("SUDO_USER") ?: System.getenv("USER")
  if (targetUser && targetUser != "root") {
    desiredUsers << targetUser
  }
}

desiredUsers.each { user ->
  if (sh("id -u ${user} >/dev/null 2>&1").code != 0) {
    println("Skipping user ${user}; account not found.")
    return
  }
  def inAdmin = sh("id -nG ${user} | tr ' ' '\n' | grep -qx ${adminGroup}")
  if (inAdmin.code != 0) {
    runOrFail("sudo usermod -aG ${adminGroup} ${user}")
    println("Added ${user} to ${adminGroup}. Please log out and back in for group membership to apply.")
    changed = true
  }
}

def v = sh("sudo visudo -c")
if (v.code!=0) { System.err.println(v.err); System.exit(1) }
System.exit(changed?10:0)
