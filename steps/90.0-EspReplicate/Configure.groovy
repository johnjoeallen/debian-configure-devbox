#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Replicate the EFI System Partition to matching disks and install GRUB to each.
// Config keys: EspReplicate (map)
// Notes: Detects the mounted /boot/efi partition, finds matching ESPs, prompts for approval, then clones and installs GRUB.

import lib.ConfigLoader
import static lib.StepUtils.sh

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

final String stepKey = "EspReplicate"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
Map cfg = ConfigLoader.stepConfig(stepKey)
def enabledValue = cfg.enabled
boolean infoOnly = false
if (enabledValue instanceof CharSequence) {
  def text = enabledValue.toString().trim().toLowerCase()
  if (text == "info") {
    infoOnly = true
  }
}

String bootloaderId = (cfg.bootloaderId ?: "debian").toString().trim()
boolean autoApprove = cfg.containsKey("autoApprove") ? (cfg.autoApprove as boolean) : false

def log = { String msg -> println "[esp-repl] ${msg}" }
def warn = { String msg -> System.err.println("[esp-repl][WARN] ${msg}") }
def fail = { String msg ->
  System.err.println("[esp-repl][ERROR] ${msg}")
  System.exit(1)
}

def needCmd = { String cmd ->
  if (sh("command -v ${cmd} >/dev/null 2>&1").code != 0) {
    fail("Missing required command: ${cmd}")
  }
}

def readFileTrim = { File file ->
  if (!file.exists()) {
    return null
  }
  file.text?.trim()
}

def readFdiskInfo = { String disk ->
  Map<String, Map> parts = [:]
  def output = sh("fdisk -l '${disk}' 2>/dev/null").out ?: ""
  int typeIndex = -1
  output.eachLine { line ->
    if (line.startsWith("Device") && line.contains("Type")) {
      typeIndex = line.indexOf("Type")
      return
    }
    if (!line.startsWith("/dev/")) {
      return
    }
    def cols = line.trim().split(/\s+/)
    if (cols.size() < 2) {
      return
    }
    String dev = cols[0]
    String sizeToken = cols.size() >= 5 ? cols[4] : ""
    String typeText = ""
    if (typeIndex >= 0 && line.length() > typeIndex) {
      typeText = line.substring(typeIndex).trim()
    } else if (cols.size() >= 7) {
      typeText = cols[6..-1].join(" ").trim()
    } else if (cols.size() >= 6) {
      typeText = cols[5..-1].join(" ").trim()
    }
    parts[dev] = [type: typeText, sizeToken: sizeToken]
  }
  parts
}

def isMounted = { String dev ->
  boolean mounted = false
  new File("/proc/self/mounts").eachLine { line ->
    def parts = line.split(" ")
    if (parts.size() >= 2 && parts[0] == dev) {
      mounted = true
    }
  }
  mounted
}

def requireUefi = {
  if (sh("test -d /sys/firmware/efi").code != 0) {
    fail("Not booted in UEFI mode (/sys/firmware/efi missing).")
  }
}

def askApproval = { String message ->
  if (autoApprove) {
    log("Auto-approve enabled; proceeding without prompt.")
    return true
  }
  def console = System.console()
  if (console != null) {
    def answer = console.readLine(message)
    return answer != null && answer.trim().equalsIgnoreCase("yes")
  }
  def reader = new BufferedReader(new InputStreamReader(System.in))
  def answer = reader.readLine()
  return answer != null && answer.trim().equalsIgnoreCase("yes")
}

def resolveDiskName = { String devPath ->
  try {
    String partBase = new File(devPath).name
    File parent = new File("/sys/class/block/${partBase}").canonicalFile?.parentFile
    return parent?.name
  } catch (Exception ignored) {
    return null
  }
}

def resolvePartuuidDevice = { String partuuid ->
  try {
    Path link = Paths.get("/dev/disk/by-partuuid/${partuuid}")
    if (!Files.exists(link)) {
      return null
    }
    return link.toRealPath().toString()
  } catch (Exception ignored) {
    return null
  }
}

def parseEfibootmgr = { String output ->
  Map entries = [:]
  List<String> bootOrder = []
  output.eachLine { line ->
    if (line.startsWith("BootOrder:")) {
      def raw = line.split(":", 2)[1]?.trim()
      if (raw) {
        bootOrder = raw.split(",").collect { it.trim() }.findAll { it }
      }
      return
    }
    def matcher = (line =~ /^Boot([0-9A-Fa-f]{4})\*?\s+(.+?)\s+HD\(/)
    if (matcher.find()) {
      String id = matcher.group(1).toUpperCase()
      String label = matcher.group(2).trim()
      def uuidMatch = (line =~ /HD\([^,]+,GPT,([0-9A-Fa-f-]+),/)
      String partuuid = uuidMatch.find() ? uuidMatch.group(1) : null
      entries[id] = [label: label, partuuid: partuuid, line: line]
    }
  }
  [entries: entries, bootOrder: bootOrder]
}

def setManagedBootOrder = { String bootloaderIdValue ->
  def output = sh("efibootmgr -v").out ?: ""
  def parsed = parseEfibootmgr(output)
  Map entries = parsed.entries as Map
  List<String> bootOrder = parsed.bootOrder as List<String>

  def managed = []
  entries.each { id, meta ->
    if (meta.label == bootloaderIdValue && meta.partuuid) {
      String dev = resolvePartuuidDevice(meta.partuuid)
      String diskName = dev ? resolveDiskName(dev) : null
      if (diskName) {
        managed << [id: id, disk: diskName]
      }
    }
  }

  if (managed.isEmpty()) {
    warn("No managed EFI boot entries found for bootloader-id '${bootloaderIdValue}'; skipping BootOrder update.")
    return
  }

  managed.sort { a, b -> a.disk <=> b.disk }
  def managedIds = managed.collect { it.id }

  if (bootOrder.isEmpty()) {
    bootOrder = entries.keySet().toList()
  }
  def remaining = bootOrder.findAll { !managedIds.contains(it) }
  def newOrder = managedIds + remaining

  def orderCsv = newOrder.join(",")
  log("Setting EFI BootOrder (managed entries first): ${orderCsv}")
  def result = sh("efibootmgr -o ${orderCsv}")
  if (result.code != 0) {
    warn("Failed to set EFI BootOrder; continuing.")
  }
}

requireUefi()
[
  "fdisk", "dd", "mount", "umount",
  "grub-install", "update-grub", "efibootmgr"
].each { needCmd(it) }

String efiTypeLabel = "efi system"

def srcDev = null
new File("/proc/self/mounts").eachLine { line ->
  def parts = line.split(" ")
  if (parts.size() >= 2 && parts[1] == "/boot/efi") {
    srcDev = parts[0]
  }
}
if (!srcDev) {
  fail("/boot/efi is not mounted.")
}
try {
  srcDev = new File(srcDev).canonicalPath
} catch (Exception ignored) {
  // Keep the original if canonicalization fails.
}
String srcBase = new File(srcDev).name
File srcPartFile = new File("/sys/class/block/${srcBase}/partition")
String srcPartNum = readFileTrim(srcPartFile)
if (!srcPartNum) {
  fail("Cannot determine partition number for ${srcDev}")
}
def srcSectorSize = readFileTrim(new File("/sys/class/block/${srcBase}/size"))
if (!srcSectorSize) {
  fail("Unable to determine size for ${srcDev}")
}
long srcSizeBytes = srcSectorSize.toLong() * 512L
String srcDiskName = new File("/sys/class/block/${srcBase}").canonicalFile.parentFile?.name
if (!srcDiskName) {
  fail("Unable to determine source disk for ${srcDev}")
}
String srcDisk = "/dev/${srcDiskName}"
String srcPartType = ""
def srcInfo = readFdiskInfo(srcDisk)
if (srcInfo.containsKey(srcDev)) {
  srcPartType = srcInfo[srcDev]?.type ?: ""
}
if (!srcPartType) {
  fail("Unable to determine PARTTYPE for ${srcDev}")
}
if (!srcPartType.toLowerCase().contains(efiTypeLabel)) {
  fail("Source ${srcDev} PARTTYPE is not EFI System. Got: ${srcPartType}")
}

log("Source ESP: ${srcDev}")
log("  Source disk      : ${srcDisk}")
log("  Partition number : ${srcPartNum}")
log("  Size (bytes)     : ${srcSizeBytes}")
log("  PARTTYPE         : ${srcPartType}")
log("  bootloader-id    : ${bootloaderId}")

def disks = []
def sysBlock = new File("/sys/block")
sysBlock.listFiles()?.each { entry ->
  if (entry.isDirectory()) {
    disks << "/dev/${entry.name}"
  }
}
if (disks.isEmpty()) {
  fail("No disks discovered via /sys/block")
}

def partitionSignature = { String disk ->
  def entries = []
  def info = readFdiskInfo(disk)
  info.each { part, meta ->
    String partBase = new File(part).name
    String partNum = readFileTrim(new File("/sys/class/block/${partBase}/partition"))
    String sizeSectors = readFileTrim(new File("/sys/class/block/${partBase}/size")) ?: "0"
    long sizeBytes = sizeSectors.toLong() * 512L
    String partType = meta?.type ?: ""
    entries << "${partNum}:${sizeBytes}:${partType.toLowerCase()}"
  }
  entries.sort()
}

def sourceSignature = partitionSignature(srcDisk)
if (sourceSignature.isEmpty()) {
  fail("Unable to determine partition signature for ${srcDisk}")
}

def targets = new LinkedHashSet<String>()
log("Scanning disks for partition tables identical to ${srcDisk}...")
disks.each { disk ->
  if (sh("test -b '${disk}'").code != 0) {
    return
  }
  def diskResolved = sh("readlink -f '${disk}' || true").out?.trim()
  def srcDiskResolved = sh("readlink -f '${srcDisk}' || true").out?.trim()
  if (diskResolved && srcDiskResolved && diskResolved == srcDiskResolved) {
    return
  }

  def signature = partitionSignature(disk)
  if (signature.isEmpty()) {
    return
  }
  if (signature != sourceSignature) {
    return
  }
  def diskInfo = readFdiskInfo(disk)
  diskInfo.keySet().each { part ->
    String partBase = new File(part).name
    String partNum = readFileTrim(new File("/sys/class/block/${partBase}/partition"))
    if (partNum != srcPartNum) {
      return
    }
    if (isMounted(part)) {
      fail("Refusing: target ${part} is mounted. Unmount it first.")
    }
    String partType = diskInfo[part]?.type ?: ""
    if (!partType.toLowerCase().contains(efiTypeLabel)) {
      return
    }
    log("VERIFIED TARGET: ${part} (parttype=${partType})")
    targets << part
  }
}

if (targets.isEmpty()) {
  log("No verified target ESP partitions found matching source (positional+size+PARTTYPE).")
  System.exit(0)
}

log("Targets to clone:")
targets.each { log("  ${it}") }

  log("Planned actions:")
  log("  - dd clone ${srcDev} -> ${targets.join(', ')}")
  log("  - grub-install --efi-directory=/boot/efi (source)")
  log("  - grub-install --efi-directory=/mnt/efi-<target> (each target)")
  log("  - update-grub once")
  log("  - install EFI/BOOT/BOOTX64.EFI fallback on source + targets")
  log("  - reorder EFI BootOrder for bootloader-id '${bootloaderId}'")

  if (infoOnly) {
    log("Info mode enabled; no changes will be made.")
    System.exit(0)
  }

if (!askApproval("Type YES to proceed with ESP replication and GRUB install: ")) {
  log("Aborted by user.")
  System.exit(0)
}

boolean changed = false
targets.each { target ->
  log("dd if=${srcDev} of=${target}")
  def result = sh("dd if='${srcDev}' of='${target}' bs=4M conv=fsync status=progress")
  if (result.code != 0) {
    fail("dd failed for ${target}")
  }
  changed = true
}

Map<String, String> mounts = [:]
def mountTarget = { String dev ->
  String name = new File(dev).name
  String mountPoint = "/mnt/efi-${name}"
  if (sh("mkdir -p '${mountPoint}'").code != 0) {
    fail("Failed to create mount point ${mountPoint}")
  }
  if (sh("mount '${dev}' '${mountPoint}'").code != 0) {
    fail("Failed to mount ${dev} at ${mountPoint}")
  }
  mounts[dev] = mountPoint
}

def cleanup = {
  mounts.each { dev, mountPoint ->
    sh("umount '${mountPoint}' || true")
    sh("rmdir '${mountPoint}' || true")
  }
}

try {
  log("Installing GRUB to source ESP (safe to re-run)...")
  if (sh("grub-install --target=x86_64-efi --efi-directory=/boot/efi --bootloader-id='${bootloaderId}' --recheck").code != 0) {
    fail("grub-install failed for source ESP")
  }

  targets.each { target ->
    mountTarget(target)
    String mountPoint = mounts[target]
    log("Installing GRUB to target ${target} via ${mountPoint}...")
    if (sh("grub-install --target=x86_64-efi --efi-directory='${mountPoint}' --bootloader-id='${bootloaderId}' --recheck").code != 0) {
      fail("grub-install failed for target ${target}")
    }
  }

  log("Running update-grub...")
  if (sh("update-grub").code != 0) {
    fail("update-grub failed")
  }

  def installFallback = { String efiDir ->
    String src = "${efiDir}/EFI/${bootloaderId}/grubx64.efi"
    String dstDir = "${efiDir}/EFI/BOOT"
    String dst = "${dstDir}/BOOTX64.EFI"
    if (new File(src).exists()) {
      if (sh("mkdir -p '${dstDir}'").code != 0) {
        fail("Failed to create ${dstDir}")
      }
      if (sh("cp '${src}' '${dst}'").code != 0) {
        fail("Failed to copy fallback loader to ${dst}")
      }
    } else {
      warn("Missing ${src}; cannot install fallback for ${efiDir}")
    }
  }

  log("Installing fallback loader (EFI/BOOT/BOOTX64.EFI) on source + targets...")
  installFallback("/boot/efi")
  mounts.values().each { installFallback(it) }

  log("Current EFI boot entries (efibootmgr -v):")
  sh("efibootmgr -v || true")
  setManagedBootOrder(bootloaderId)
} finally {
  cleanup()
}

log("Done. Reboot and test booting from each disk via firmware boot menu.")
System.exit(changed ? 10 : 0)
