#!/usr/bin/env groovy
// RUN_AS_ROOT
// --- Documentation ---
// Summary: Install baseline CLI packages required for provisioning.
// Config keys: packages (list of package names)
// Notes: Uses apt to install any missing packages only.

import lib.ConfigLoader
import static lib.StepUtils.sh

def stepKey = "Essentials"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

def defaultPackages = ["curl", "wget", "zip", "unzip", "rsync", "ca-certificates", "gnupg", "apt-transport-https", "neovim"]

def packages = new LinkedHashSet<String>()
def aliasRequests = []
def aliasPattern = ~/^(.+?)\s+alias\s+(\S+)\s*->\s*(\S+)$/
def aliasPatternShort = ~/^(.+?)\s+alias\s+(\S+)$/

def addAliasRequest = { String pkgName, String sourceCmd, String targetCmd ->
  packages << pkgName
  aliasRequests << [pkg: pkgName, source: sourceCmd, target: targetCmd]
}

def collectPackages = { value ->
  def handleEntry
  handleEntry = { entry ->
    if (entry instanceof Collection) {
      entry.each { handleEntry(it) }
      return
    }
    if (entry == null) {
      return
    }
    def text = entry.toString().trim()
    if (!text) {
      return
    }
    def matcher = (text =~ aliasPattern)
    if (matcher.matches()) {
      addAliasRequest(matcher.group(1).trim(), matcher.group(2).trim(), matcher.group(3).trim())
      return
    }
    def shortMatcher = (text =~ aliasPatternShort)
    if (shortMatcher.matches()) {
      addAliasRequest(shortMatcher.group(1).trim(), null, shortMatcher.group(2).trim())
      return
    }
    packages << text
  }

  handleEntry(value)
}

collectPackages(stepConfig.packages)
if (packages.isEmpty()) {
  collectPackages(defaultPackages)
}

if (packages.isEmpty()) {
  println "No packages defined for ${stepKey}; skipping"
  System.exit(0)
}

def dpkgStatusFormat = "\${Status}"
def isInstalled = { pkg ->
  def status = sh("dpkg-query -W -f='${dpkgStatusFormat}' ${pkg} 2>/dev/null")
  if (status.code != 0) {
    return false
  }
  def normalized = status.out?.trim()?.toLowerCase()
  return normalized == 'install ok installed'
}

def missing = packages.findAll { pkg -> !isInstalled(pkg) }
boolean packagesInstalled = false

if (missing) {
  def installCmd = "apt update && apt install -y ${missing.join(' ')}"
  def result = sh(installCmd)
  if (result.code != 0) {
    if (result.out) {
      System.err.println(result.out)
    }
    if (result.err) {
      System.err.println(result.err)
    }
    System.exit(1)
  }
  packagesInstalled = true
}

aliasRequests.each { alias ->
  def sourceCmd = alias.source
  def targetCmd = alias.target

  if (!sourceCmd) {
    def candidateList = sh("dpkg -L ${alias.pkg} 2>/dev/null | grep -E '/s?bin/[^/]+\$'")
    if (candidateList.code == 0 && candidateList.out) {
      def basenames = candidateList.out
        .split("\\n")
        .collect { it.trim() }
        .findAll { it }
        .collect { new File(it).name }
      if (basenames) {
        basenames.sort { a, b -> a.length() <=> b.length() ?: a <=> b }
        sourceCmd = basenames.first()
      }
    }
    if (!sourceCmd) {
      def cmdName = alias.pkg
      def cmdPath = sh("command -v ${cmdName}")
      if (cmdPath.code == 0 && cmdPath.out) {
        sourceCmd = cmdName
      }
    }
    if (!sourceCmd) {
      System.err.println("Could not determine command to alias for package '${alias.pkg}'")
      System.exit(1)
    }
  }
  def sourcePathResult = sh("command -v ${sourceCmd}")
  if (sourcePathResult.code != 0 || !sourcePathResult.out) {
    System.err.println("Command '${sourceCmd}' not found in PATH after installing ${alias.pkg}; cannot configure alternatives for '${targetCmd}'")
    System.exit(1)
  }
  def sourcePath = sourcePathResult.out
  def resolvedSource = sh("readlink -f ${sourcePath}")
  if (resolvedSource.code == 0 && resolvedSource.out) {
    sourcePath = resolvedSource.out
  }

  def existingTarget = sh("command -v ${targetCmd}")
  if (existingTarget.code == 0 && existingTarget.out) {
    def targetPath = existingTarget.out
    def resolvedTarget = sh("readlink -f ${targetPath}")
    if (resolvedTarget.code == 0 && resolvedTarget.out) {
      targetPath = resolvedTarget.out
    }
    if (targetPath != sourcePath) {
      def owner = sh("dpkg-query -S ${targetPath}")
      if (owner.code == 0 && owner.out) {
        def packagesToRemove = owner.out
          .split("\\n")
          .collect { it.split(':')[0].trim() }
          .findAll { it }
          .unique()
        if (packagesToRemove) {
          def removal = sh("apt remove -y ${packagesToRemove.join(' ')}")
          if (removal.code != 0) {
            if (removal.out) {
              System.err.println(removal.out)
            }
            if (removal.err) {
              System.err.println(removal.err)
            }
            System.exit(1)
          }
        }
      }
    }
  }

  def altInstall = sh("update-alternatives --install /usr/bin/${targetCmd} ${targetCmd} ${sourcePath} 60")
  if (altInstall.code != 0) {
    if (altInstall.out) {
      System.err.println(altInstall.out)
    }
    if (altInstall.err) {
      System.err.println(altInstall.err)
    }
    System.exit(1)
  }
  def altSet = sh("update-alternatives --set ${targetCmd} ${sourcePath}")
  if (altSet.code != 0) {
    if (altSet.out) {
      System.err.println(altSet.out)
    }
    if (altSet.err) {
      System.err.println(altSet.err)
    }
    System.exit(1)
  }
}

System.exit(packagesInstalled ? 10 : 0)
