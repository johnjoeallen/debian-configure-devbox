#!/usr/bin/env groovy
// --- Documentation ---
// Summary: Install JetBrains Toolbox launcher for managing IDEs.
// Config keys: none
// Notes: Downloads the latest tarball, extracts, and runs the installer once.

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import static lib.StepUtils.sh

def home = System.getenv("HOME") ?: System.getProperty("user.home")
if (!home) {
  System.err.println("HOME not set; cannot determine installation path")
  System.exit(1)
}

def marker = new File("${home}/.local/share/JetBrains/Toolbox")
if (marker.exists()) {
  System.exit(0)
}

Path tempPath
try {
  def userLabel = (System.getenv('USER') ?: 'user').replaceAll(/[^A-Za-z0-9._-]/, '_')
  tempPath = Files.createTempDirectory(Paths.get('/tmp'), "jetbrains-toolbox-${userLabel}-")
} catch (Exception e) {
  System.err.println("Unable to create temporary directory in /tmp: ${e.message}")
  System.exit(1)
}
def tmpDir = tempPath.toFile()
println "Using temporary directory ${tmpDir.absolutePath}"

def archivePath = new File(tmpDir, "toolbox.tar.gz").absolutePath
def workDir = new File(tmpDir, "jb").absolutePath

def download = sh("wget -O '${archivePath}' 'https://data.services.jetbrains.com/products/download?code=TBA&platform=linux'")
if (download.code != 0) {
  if (download.out) System.err.println(download.out)
  if (download.err) System.err.println(download.err)
  System.exit(1)
}

def extractCmd = "mkdir -p '${workDir}' && tar -xzf '${archivePath}' -C '${workDir}' && cd '${workDir}'/jetbrains-toolbox* && nohup ./bin/jetbrains-toolbox >/dev/null 2>&1 &"
def extract = sh(extractCmd)
if (extract.code != 0) {
  if (extract.out) System.err.println(extract.out)
  if (extract.err) System.err.println(extract.err)
  System.exit(1)
}

if (!tmpDir.deleteDir()) {
  def cleanup = sh("rm -rf '${tmpDir.absolutePath}'")
  if (cleanup.code != 0) {
    System.err.println("Warning: unable to remove temporary directory ${tmpDir.absolutePath}")
  }
}

System.exit(10)
