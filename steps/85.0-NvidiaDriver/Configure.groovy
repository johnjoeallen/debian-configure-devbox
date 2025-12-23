#!/usr/bin/env groovy
// RUN_VIA_SUDO
// RUN_VIA_SUDO
// --- Documentation ---
// Summary: Install NVIDIA drivers on Debian 13 using a fully Groovy-based runbook.
// Config keys: NvidiaDriverInstall (map)
// Notes: Performs APT checks, installs packages, handles DKMS/Secure Boot, and validates module loading.

import lib.ConfigLoader
import static lib.StepUtils.sh
import lib.StepUtils

final String stepKey = "NvidiaDriverInstall"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}

boolean changed = false
boolean aptUpdated = false
boolean aptSourcesChanged = false

def log = { String msg -> println "== ${msg}" }
def warn = { String msg -> System.err.println("WARN: ${msg}") }
def fail = { String msg ->
  System.err.println("ERROR: ${msg}")
  System.exit(1)
}

def requireRoot = {
  def result = sh("id -u")
  if (result.code != 0 || result.out?.trim() != "0") {
    fail("Run via sudo (root required)")
  }
}

def ensureAptUpdate = {
  if (!aptUpdated) {
    log("Updating apt metadata")
    def result = sh("apt-get update")
    if (result.code != 0) {
      if (result.err) System.err.println(result.err)
      fail("apt-get update failed")
    }
    aptUpdated = true
  }
}

def isInstalled = { String pkg ->
  def result = sh("dpkg-query -W -f='${'$'}{Status}' ${pkg} 2>/dev/null")
  if (result.code != 0) {
    return false
  }
  def status = result.out?.trim()?.toLowerCase()
  return status == "install ok installed"
}

def backupFile = { File file ->
  if (!file.exists()) {
    return
  }
  StepUtils.backup(file.absolutePath)
}

def uniqueComponents = { List<String> required, List<String> existing ->
  def ordered = []
  required.each { comp ->
    if (!ordered.contains(comp)) {
      ordered << comp
    }
  }
  existing.each { comp ->
    if (!ordered.contains(comp)) {
      ordered << comp
    }
  }
  ordered
}

def updateComponentsListFile = { File file, List<String> required, String codename ->
  if (!file.exists()) {
    return
  }
  def lines = file.readLines()
  def changedLocal = false
  def updated = lines.collect { String line ->
    def parts = line.split("#", 2)
    def base = parts[0]
    def comment = (parts.size() > 1) ? "#" + parts[1] : ""
    def matcher = (base =~ /^(\s*deb\s+)(\[[^]]+]\s+)?(\S+)\s+(\S+)\s+(.+)$/)
    if (matcher.matches()) {
      def suite = matcher.group(4)
      if (suite?.startsWith(codename)) {
        def comps = matcher.group(5).trim().split(/\s+/).findAll { it }
        def merged = uniqueComponents(required, comps)
        def newLine = "${matcher.group(1)}${matcher.group(2) ?: ''}${matcher.group(3)} ${suite} ${merged.join(' ')}"
        if (comment) {
          newLine = "${newLine} ${comment}".trim()
        }
        if (newLine != line) {
          changedLocal = true
          return newLine
        }
      }
    }
    return line
  }

  if (changedLocal) {
    backupFile(file)
    file.setText(updated.join("\n") + "\n")
    aptSourcesChanged = true
    changed = true
    log("Updated APT components in ${file.absolutePath}")
  }
}

def updateComponentsDeb822File = { File file, List<String> required, String codename ->
  if (!file.exists()) {
    return
  }
  def content = file.text
  def stanzas = content.split(/\n\s*\n/)
  def changedLocal = false
  def updated = stanzas.collect { String stanza ->
    def lines = stanza.readLines()
    boolean hasSuite = false
    int componentsIdx = -1
    lines.eachWithIndex { String line, int idx ->
      if (line ==~ /^\s*Suites:\s+.*\b${codename}\b.*/) {
        hasSuite = true
      }
      if (line ==~ /^\s*Components:\s+.*/) {
        componentsIdx = idx
      }
    }
    if (!hasSuite) {
      return stanza
    }
    if (componentsIdx >= 0) {
      def comps = lines[componentsIdx].replaceFirst(/^\s*Components:\s*/, "")
      def existing = comps.split(/\s+/).findAll { it }
      def merged = uniqueComponents(required, existing)
      def newLine = "Components: ${merged.join(' ')}"
      if (newLine != lines[componentsIdx]) {
        lines[componentsIdx] = newLine
        changedLocal = true
      }
    } else {
      lines << "Components: ${required.join(' ')}"
      changedLocal = true
    }
    lines.join("\n")
  }

  if (changedLocal) {
    backupFile(file)
    file.setText(updated.join("\n\n").trim() + "\n")
    aptSourcesChanged = true
    changed = true
    log("Updated APT components in ${file.absolutePath}")
  }
}

def ensureAptComponents = { String codename ->
  def required = ["main", "contrib", "non-free", "non-free-firmware"]
  File sourcesList = new File("/etc/apt/sources.list")
  updateComponentsListFile(sourcesList, required, codename)
  File sourcesDir = new File("/etc/apt/sources.list.d")
  if (sourcesDir.exists()) {
    sourcesDir.listFiles()?.findAll { it.name.endsWith(".list") }?.each { file ->
      updateComponentsListFile(file, required, codename)
    }
    sourcesDir.listFiles()?.findAll { it.name.endsWith(".sources") }?.each { file ->
      updateComponentsDeb822File(file, required, codename)
    }
  }

  if (aptSourcesChanged) {
    ensureAptUpdate()
  }

  def policy = sh("apt-cache policy").out ?: ""
  if (!(policy =~ /\b${codename}\/contrib\b/)) {
    ensureAptUpdate()
    policy = sh("apt-cache policy").out ?: ""
  }
  if (!(policy =~ /\b${codename}\/non-free\b/)) {
    ensureAptUpdate()
    policy = sh("apt-cache policy").out ?: ""
  }
  if (!(policy =~ /\b${codename}\/non-free-firmware\b/)) {
    ensureAptUpdate()
    policy = sh("apt-cache policy").out ?: ""
  }

  if (!(policy =~ /\b${codename}\/contrib\b/)) {
    fail("contrib component not enabled (expect Components: main contrib non-free non-free-firmware)")
  }
  if (!(policy =~ /\b${codename}\/non-free\b/)) {
    fail("non-free component not enabled (expect Components: main contrib non-free non-free-firmware)")
  }
  if (!(policy =~ /\b${codename}\/non-free-firmware\b/)) {
    fail("non-free-firmware component not enabled (expect Components: main contrib non-free non-free-firmware)")
  }
}

def ensureKernelHeaders = {
  def kernel = sh("uname -r").out?.trim()
  if (!kernel) {
    fail("Unable to determine running kernel")
  }
  String pkg = "linux-headers-${kernel}"
  if (!isInstalled(pkg)) {
    log("Installing kernel headers for ${kernel}")
    ensureAptUpdate()
    def result = sh("apt-get install -y ${pkg}")
    if (result.code != 0) {
      if (result.err) System.err.println(result.err)
      fail("Failed to install kernel headers for ${kernel}")
    }
    changed = true
  }
  if (!isInstalled(pkg)) {
    fail("Kernel headers for ${kernel} are not installed")
  }
}

def ensureNvidiaPackages = { String codename ->
  def packages = ["nvidia-driver-full", "nvidia-kernel-dkms", "firmware-misc-nonfree", "dkms", "mokutil"]

  def candidate = sh("apt-cache policy nvidia-driver-full | awk '/Candidate:/ {print ${'$'}2; exit}'").out?.trim()
  if (!candidate || candidate == "(none)") {
    fail("nvidia-driver-full has no installation candidate (check non-free components)")
  }
  candidate = sh("apt-cache policy nvidia-kernel-dkms | awk '/Candidate:/ {print ${'$'}2; exit}'").out?.trim()
  if (!candidate || candidate == "(none)") {
    fail("nvidia-kernel-dkms has no installation candidate (check non-free components)")
  }

  def missing = packages.findAll { !isInstalled(it) }
  if (!missing.isEmpty()) {
    log("Installing NVIDIA packages: ${missing.join(' ')}")
    ensureAptUpdate()
    def result = sh("apt-get install -y ${missing.join(' ')}")
    if (result.code != 0) {
      if (result.err) System.err.println(result.err)
      fail("Failed to install NVIDIA packages")
    }
    changed = true
  }
}

def ensureFileContent = { String path, String content ->
  File file = new File(path)
  if (!file.exists() || file.text != content) {
    file.setText(content + "\n")
    changed = true
  }
}

def rebuildInitramfsIfNeeded = {
  if (changed) {
    log("Rebuilding initramfs")
    def result = sh("update-initramfs -u")
    if (result.code != 0) {
      if (result.err) System.err.println(result.err)
      fail("update-initramfs failed")
    }
  }
}

def checkSecureBoot = {
  def mok = sh("command -v mokutil")
  if (mok.code != 0) {
    warn("mokutil is not available; cannot determine Secure Boot state")
    return
  }
  def state = sh("mokutil --sb-state 2>/dev/null").out?.toLowerCase()
  if (state?.contains("enabled")) {
    log("Secure Boot is enabled")
    log("Reconfiguring DKMS for MOK enrollment")
    def result = sh("dpkg-reconfigure nvidia-kernel-dkms")
    if (result.code != 0) {
      fail("Failed to reconfigure nvidia-kernel-dkms for Secure Boot")
    }
  } else {
    log("Secure Boot is disabled")
  }
}

def verifyDkms = {
  def kernel = sh("uname -r").out?.trim()
  def status = sh("dkms status | grep -E 'nvidia' || true").out?.trim()
  if (!status || !status.contains("installed")) {
    if (!kernel) {
      fail("Unable to determine running kernel for DKMS build")
    }
    log("Attempting DKMS autoinstall for ${kernel}")
    def build = sh("dkms autoinstall -k ${kernel}")
    if (build.code != 0) {
      if (build.err) System.err.println(build.err)
      fail("DKMS autoinstall failed; check /var/lib/dkms and build logs")
    }
    status = sh("dkms status | grep -E 'nvidia' || true").out?.trim()
  }
  if (!status) {
    fail("DKMS reports no NVIDIA modules; check build logs")
  }
  if (!status.contains("installed")) {
    fail("DKMS does not report installed modules for ${kernel}: ${status}")
  }
}

def verifyModuleLoad = {
  def nouveau = sh("lsmod | grep -q '^nouveau'").code == 0
  if (nouveau) {
    warn("Nouveau is currently loaded; it should be gone after reboot")
  }
  def hasNvidia = sh("lsmod | grep -q '^nvidia'").code == 0
  if (!hasNvidia) {
    log("Attempting to load the NVIDIA kernel module")
    def result = sh("modprobe nvidia 2>&1")
    if (result.code != 0) {
      def output = (result.out ?: "") + (result.err ?: "")
      if (output.toLowerCase().contains("required key not available") ||
          output.toLowerCase().contains("operation not permitted")) {
        fail("Secure Boot is blocking NVIDIA modules; enroll a MOK and reboot")
      }
      if (output.toLowerCase().contains("module nvidia not found")) {
        fail("NVIDIA module not found for this kernel; check DKMS and headers")
      }
      fail("Failed to load NVIDIA module: ${output}".trim())
    }
  }
}

requireRoot()

log("Checking Debian version")
def osRelease = new File("/etc/os-release")
if (!osRelease.exists()) {
  fail("Unable to read /etc/os-release")
}
def osInfo = [:]
osRelease.eachLine { line ->
  def parts = line.split("=", 2)
  if (parts.size() == 2) {
    osInfo[parts[0]] = parts[1].replaceAll(/^"|"$/, "")
  }
}
if (osInfo.VERSION_ID != "13") {
  fail("This step is for Debian 13 only")
}
def codename = osInfo.VERSION_CODENAME ?: "trixie"
log("Using suite: ${codename}")

log("Checking APT components")
ensureAptComponents(codename)

log("Checking kernel headers")
ensureKernelHeaders()

log("Installing NVIDIA packages")
ensureNvidiaPackages(codename)

log("Configuring Nouveau blacklist")
ensureFileContent("/etc/modprobe.d/blacklist-nouveau.conf", "blacklist nouveau\noptions nouveau modeset=0")

log("Enabling NVIDIA DRM modeset")
ensureFileContent("/etc/modprobe.d/nvidia-drm.conf", "options nvidia-drm modeset=1")

rebuildInitramfsIfNeeded()
checkSecureBoot()
verifyDkms()
verifyModuleLoad()

log("Post-install checklist after reboot:")
log("  lsmod | grep nvidia")
log("  nvidia-smi")
log("  dkms status")
log("Only replicate /boot after NVIDIA loads successfully")
log("Reboot required")

if (changed) {
  System.exit(10)
}
System.exit(0)
