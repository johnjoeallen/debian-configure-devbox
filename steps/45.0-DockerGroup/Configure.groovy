#!/usr/bin/env groovy
// --- Documentation ---
// Summary: Ensure configured users belong to the docker Unix group.
// Config keys: ensureUser (boolean), users (list of usernames)
// Notes: Adds the group if needed and reminds users to re-login for membership.

import lib.ConfigLoader
import static lib.StepUtils.sh

def stepKey = "DockerGroup"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

def collectUsers = { value ->
  def result = []
  if (value instanceof Collection) {
    value.each { entry ->
      def name = entry?.toString()?.trim()
      if (name) {
        result << name
      }
    }
  } else if (value != null) {
    def name = value.toString().trim()
    if (name) {
      result << name
    }
  }
  result
}

def desiredUsers = new LinkedHashSet<String>(collectUsers(stepConfig.users))

def ensureUser = stepConfig.containsKey('ensureUser') ? (stepConfig.ensureUser != false) : true
if (ensureUser) {
  def currentUser = System.getenv("SUDO_USER") ?: System.getenv("USER")
  if (currentUser && currentUser != "root") {
    desiredUsers << currentUser
  }
}

if (desiredUsers.isEmpty()) {
  println "No users requested for ${stepKey}; skipping"
  System.exit(0)
}

def runOrFail = { String cmd ->
  def res = sh(cmd)
  if (res.code != 0) {
    System.err.println("Command failed (${cmd})")
    if (res.out) {
      System.err.println(res.out)
    }
    if (res.err) {
      System.err.println(res.err)
    }
    System.exit(1)
  }
  res
}

boolean changed = false
if (sh("getent group docker").code != 0) {
  runOrFail("groupadd docker")
  changed = true
}

desiredUsers.each { user ->
  if (sh("id -u ${user} >/dev/null 2>&1").code != 0) {
    println "Skipping user ${user}; account not found. ⚠️"
    return
  }
  def inGroup = sh("id -nG ${user} | tr ' ' '\\n' | grep -qx docker")
  if (inGroup.code != 0) {
    runOrFail("usermod -aG docker ${user}")
    println "Added ${user} to docker group. Log out/in to apply."
    changed = true
  }
}

System.exit(changed ? 10 : 0)
