#!/usr/bin/env groovy
// RUN_AS_USER
// --- Documentation ---
// Summary: Install JetBrains Toolbox launcher for managing IDEs.
// Config keys: JetbrainsToolbox (map)
// Notes: Downloads the latest tarball, extracts, and runs the installer once.

import lib.ConfigLoader
import static lib.StepUtils.sh

final String stepKey = "JetbrainsToolbox"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
ConfigLoader.stepConfig(stepKey)

def home = System.getenv("HOME") ?: System.getProperty("user.home")
if (!home) {
  System.err.println("HOME not set; cannot determine installation path")
  System.exit(1)
}

def marker = new File("${home}/.local/share/JetBrains/Toolbox")
if (marker.exists()) {
  System.exit(0)
}

def installDir = new File(home, "jetbrains")
if (!installDir.exists() && !installDir.mkdirs()) {
  System.err.println("Unable to create installation directory ${installDir.absolutePath}")
  System.exit(1)
}
println "Using installation directory ${installDir.absolutePath}"

def archiveFile = new File(installDir, "toolbox.tar.gz")
if (archiveFile.exists() && !archiveFile.delete()) {
  System.err.println("Unable to remove existing archive ${archiveFile.absolutePath}")
  System.exit(1)
}
def workDirFile = new File(installDir, "toolbox")
if (workDirFile.exists() && !workDirFile.deleteDir()) {
  def cleanup = sh("rm -rf '${workDirFile.absolutePath}'")
  if (cleanup.code != 0) {
    System.err.println("Unable to clean previous extraction directory ${workDirFile.absolutePath}")
    if (cleanup.out) System.err.println(cleanup.out)
    if (cleanup.err) System.err.println(cleanup.err)
    System.exit(1)
  }
}
if (!workDirFile.mkdirs()) {
  System.err.println("Unable to create extraction directory ${workDirFile.absolutePath}")
  System.exit(1)
}

def archivePath = archiveFile.absolutePath
def workDir = workDirFile.absolutePath

def download = sh("wget -O '${archivePath}' 'https://data.services.jetbrains.com/products/download?code=TBA&platform=linux'")
if (download.code != 0) {
  if (download.out) System.err.println(download.out)
  if (download.err) System.err.println(download.err)
  System.exit(1)
}

def extractCmd = "tar -xzf '${archivePath}' -C '${workDir}' && cd '${workDir}'/jetbrains-toolbox* && nohup ./bin/jetbrains-toolbox >/dev/null 2>&1 &"
def extract = sh(extractCmd)
if (extract.code != 0) {
  if (extract.out) System.err.println(extract.out)
  if (extract.err) System.err.println(extract.err)
  System.exit(1)
}

System.exit(10)
