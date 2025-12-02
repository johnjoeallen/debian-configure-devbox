#!/usr/bin/env groovy
// RUN_AS_USER
// --- Documentation ---
// Summary: Install and set default Java runtimes via SDKMAN according to config.
// Config keys: javaVersions (list), javaVersion (string, legacy), defaultJava (string)
// Notes: Requires SdkmanInstall and skips when SDKMAN is missing.

import lib.ConfigLoader
import static lib.StepUtils.sh

if (!ConfigLoader.stepEnabled("SdkmanInstall")) {
  println "SdkmanInstall disabled; skipping Java installs."
  System.exit(0)
}
def stepKey = "SdkmanJavaInstall"
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

def sdkDir = new File("${home}/.sdkman")
if (!sdkDir.exists()) {
  println "SDKMAN not installed. Skipping Java installs."
  System.exit(0)
}

def init = "source ${home}/.sdkman/bin/sdkman-init.sh"

def collectStrings = { value ->
  def result = []
  if (value instanceof Collection) {
    value.each { v ->
      def s = v?.toString()?.trim()
      if (s) result << s
    }
  } else {
    def s = value?.toString()?.trim()
    if (s) result << s
  }
  result
}

def javaVersions = collectStrings(stepConfig.javaVersions)
if (javaVersions.isEmpty()) {
  javaVersions = collectStrings(stepConfig.javaVersion)
}
if (javaVersions.isEmpty()) {
  javaVersions = ['17.0.6-tem']
}

def defaultJava = stepConfig.defaultJava?.toString()?.trim()
if (!defaultJava && stepConfig.javaVersion) {
  defaultJava = stepConfig.javaVersion?.toString()?.trim()
}
if (!defaultJava) {
  defaultJava = javaVersions[0]
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

def changed = false

def ensureJavaVersion = { String version ->
  def candidateDir = new File("${home}/.sdkman/candidates/java/${version}")
  if (!candidateDir.exists()) {
    runOrFail("bash -lc '${init} && yes | sdk install java ${version}'")
    changed = true
  }
}

javaVersions.each { ensureJavaVersion(it) }
if (defaultJava && !javaVersions.contains(defaultJava)) {
  ensureJavaVersion(defaultJava)
}

if (defaultJava) {
  def currentJava = sh("bash -lc '${init} && sdk current java'")
  if (currentJava.code != 0 || !currentJava.out.contains(defaultJava)) {
    runOrFail("bash -lc '${init} && sdk default java ${defaultJava}'")
    changed = true
  }
}

System.exit(changed ? 10 : 0)
