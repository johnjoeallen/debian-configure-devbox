#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install the Insomnia desktop client at a pinned version.
// Config keys: none
// Notes: Installs runtime dependencies and fetches the .deb from GitHub releases.

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import lib.ConfigLoader
import static lib.StepUtils.sh

def resolveUserContext = {
  def sudoUser = System.getenv('SUDO_USER')?.trim()
  def envUser = System.getenv('USER')?.trim()
  def user = sudoUser ?: envUser ?: 'root'

  def groupLookup = sh("id -gn ${user}")
  def group = (groupLookup.code == 0 && groupLookup.out?.trim()) ? groupLookup.out.trim() : user

  [user: user, group: group]
}

def createStepTempDir = { Map ctx, String prefix ->
  def safePrefix = prefix.replaceAll(/[^A-Za-z0-9._-]/, '_')
  Path tempPath
  try {
    tempPath = Files.createTempDirectory(Paths.get('/tmp'), safePrefix)
  } catch (Exception e) {
    System.err.println("Unable to create temporary directory in /tmp: ${e.message}")
    System.exit(1)
  }
  def tmpDir = tempPath.toFile()
  println "Using temporary directory ${tmpDir.absolutePath}"
  if (ctx.user && ctx.user != 'root') {
    def chown = sh("chown ${ctx.user}:${ctx.group ?: ctx.user} '${tmpDir.absolutePath}'")
    if (chown.code != 0) {
      System.err.println("Failed to chown ${tmpDir} to ${ctx.user}")
      if (chown.out) System.err.println(chown.out)
      if (chown.err) System.err.println(chown.err)
      System.exit(1)
    }
  }
  tmpDir
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

def stepKey = "Insomnia"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}

def userCtx = resolveUserContext()
def tmpPrefix = "insomnia-${userCtx.user ?: 'user'}-"
def tmpDir = createStepTempDir(userCtx, tmpPrefix)

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

String debFile = new File(tmpDir, "Insomnia.Core-${targetVersion}.deb").absolutePath
String url = "https://github.com/Kong/insomnia/releases/download/core%40${targetVersion}/Insomnia.Core-${targetVersion}.deb"
runOrFail("wget -O '${debFile}' '${url}'", "Download Insomnia ${targetVersion}")

def installCmd = "DEBIAN_FRONTEND=noninteractive apt-get install -y '${debFile}'"
runOrFail(installCmd, "Install Insomnia ${targetVersion}")
changed = true

println "Installed Insomnia ${targetVersion}."

if (!tmpDir.deleteDir()) {
  def cleanup = sh("rm -rf '${tmpDir.absolutePath}'")
  if (cleanup.code != 0) {
    System.err.println("Warning: unable to remove temporary directory ${tmpDir.absolutePath}")
  }
}

System.exit(changed ? 10 : 0)
