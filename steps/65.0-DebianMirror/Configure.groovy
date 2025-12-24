#!/usr/bin/env groovy
// RUN_VIA_SUDO
// Summary: Provision a local Debian apt mirror served via Apache.
// Config keys: DebianMirror (map)
// Notes: Installs apt-mirror + Apache, writes mirror configs, enables vhost, optional initial sync, cron refresh.

import lib.ConfigLoader
import static lib.StepUtils.sh

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths

final String stepKey = "DebianMirror"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}

Map cfg = ConfigLoader.stepConfig(stepKey)
String siteFqdn = (cfg.siteFqdn ?: "mirror.dublinux.lan") as String
String mirrorRoot = (cfg.mirrorRoot ?: "/var/ftp/pub/mirrors") as String
String mainBase = (cfg.mainBase ?: "${mirrorRoot}/debian") as String
String securityBase = (cfg.securityBase ?: "${mirrorRoot}/debian-security") as String
int threads = (cfg.threads ?: 20) as int
List<String> distributions = (cfg.distributions instanceof List ? cfg.distributions : []) ?: ["bookworm", "trixie"]
List<String> components = (cfg.components instanceof List ? cfg.components : []) ?: ["main", "contrib", "non-free", "non-free-firmware"]
boolean includeUpdates = cfg.containsKey("includeUpdates") ? cfg.includeUpdates as boolean : true
boolean includeBackports = cfg.containsKey("includeBackports") ? cfg.includeBackports as boolean : true
boolean includeContents = cfg.containsKey("includeContents") ? cfg.includeContents as boolean : true
List<String> securitySuites = (cfg.securitySuites instanceof List ? cfg.securitySuites : []) ?: distributions.collect { "${it}-security" }
boolean runInitialSync = cfg.containsKey("runInitialSync") ? cfg.runInitialSync as boolean : true
boolean cronEnabled = cfg.containsKey("cronEnabled") ? cfg.cronEnabled as boolean : true
String apacheAdmin = (cfg.apacheAdmin ?: "webmaster@${siteFqdn}") as String
String apacheLogDir = (cfg.apacheLogDir ?: '${APACHE_LOG_DIR}') as String
String mainAlias = (cfg.mainAlias ?: "${mirrorRoot}/debian-root") as String
String securityAliasPath = (cfg.securityAliasPath ?: "${securityBase}/mirror/security.debian.org/debian-security") as String
List<String> requiredPackages = ["apt-mirror", "apache2", "curl"]
String cronPath = "/etc/cron.daily/debian-mirror"
String mainMirrorList = "/etc/apt/mirror.list"
String securityMirrorList = "/etc/apt/mirror-security.list"
String postMirrorHook = "${mainBase}/var/postmirror.sh"
String siteConfPath = "/etc/apache2/sites-available/${siteFqdn}.conf"
String siteId = siteConfPath.endsWith('.conf') ? siteConfPath.substring(siteConfPath.lastIndexOf('/') + 1, siteConfPath.length() - 5) : siteConfPath

boolean changed = false
boolean needReload = false
boolean needSync = false

def fail = { String message, Map result ->
  System.err.println(message)
  if (result?.out) System.err.println(result.out)
  if (result?.err) System.err.println(result.err)
  System.exit(1)
}

def ensurePackages = {
  List<String> missing = requiredPackages.findAll { pkg ->
    sh("dpkg -s ${pkg} >/dev/null 2>&1").code != 0
  }
  if (!missing.isEmpty()) {
    def update = sh("DEBIAN_FRONTEND=noninteractive apt-get update -y")
    if (update.code != 0) {
      fail("apt-get update failed", update)
    }
    def install = sh("DEBIAN_FRONTEND=noninteractive apt-get install -y ${missing.join(' ')}")
    if (install.code != 0) {
      fail("apt-get install failed", install)
    }
    changed = true
  }
}

def ensureDir = { String path ->
  File dir = new File(path)
  if (!dir.exists()) {
    def result = sh("install -d -m 0755 -o root -g root '${path}'")
    if (result.code != 0) {
      fail("Unable to create directory ${path}", result)
    }
    changed = true
  }
}

def ensureParent = { File file ->
  File parent = file.parentFile
  if (parent != null && !parent.exists()) {
    def result = sh("install -d -m 0755 -o root -g root '${parent.absolutePath}'")
    if (result.code != 0) {
      fail("Unable to create parent directory for ${file.absolutePath}", result)
    }
    changed = true
  }
}

def ensureFile = { String path, String content, String mode, String owner = "root", String group = "root", boolean alwaysWrite = false ->
  File file = new File(path)
  ensureParent(file)
  if (alwaysWrite || !file.exists() || file.text != content) {
    file.setText(content)
    changed = true
  }
  def chmod = sh("chmod ${mode} '${path}'")
  if (chmod.code != 0) {
    fail("Unable to chmod ${path}", chmod)
  }
  def chown = sh("chown ${owner}:${group} '${path}'")
  if (chown.code != 0) {
    fail("Unable to chown ${path}", chown)
  }
}

def ensureSymlink = { String linkPath, String targetPath ->
  Path link = Paths.get(linkPath)
  Path target = Paths.get(targetPath)
  if (link.parent != null && !Files.exists(link.parent, LinkOption.NOFOLLOW_LINKS)) {
    Files.createDirectories(link.parent)
  }
  if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
    if (!Files.isSymbolicLink(link)) {
      Files.delete(link)
      changed = true
    } else {
      Path current = Files.readSymbolicLink(link)
      if (current.toString() == target.toString()) {
        return
      }
      Files.delete(link)
      changed = true
    }
  }
  Files.createSymbolicLink(link, target)
  changed = true
}

def ensureApacheModule = { String module ->
  if (sh("a2query -m ${module} >/dev/null 2>&1").code != 0) {
    def result = sh("a2enmod ${module}")
    if (result.code != 0) {
      fail("Unable to enable Apache module ${module}", result)
    }
    needReload = true
    changed = true
  }
}

def disableApacheSite = { String site ->
  if (sh("a2query -s ${site} >/dev/null 2>&1").code == 0) {
    def result = sh("a2dissite ${site}")
    if (result.code != 0) {
      fail("Unable to disable Apache site ${site}", result)
    }
    needReload = true
    changed = true
  }
}

def ensureApacheSiteEnabled = { String configName, String siteName ->
  if (sh("a2query -s ${siteName} >/dev/null 2>&1").code != 0) {
    def result = sh("a2ensite ${configName}")
    if (result.code != 0) {
      fail("Unable to enable Apache site ${configName}", result)
    }
    needReload = true
    changed = true
  }
}

def removeFileIfExists = { String path ->
  File file = new File(path)
  if (file.exists()) {
    if (!file.delete()) {
      def result = sh("rm -f '${path}'")
      if (result.code != 0) {
        fail("Unable to remove ${path}", result)
      }
    }
    changed = true
  }
}

ensurePackages()
ensureDir(mirrorRoot)
ensureDir(mainBase)
ensureDir(securityBase)

String componentLine = components.join(' ')
StringBuilder mainListBuilder = new StringBuilder()
mainListBuilder.append("############ Main Debian mirror ############\n")
mainListBuilder.append("set base_path    ${mainBase}\n")
mainListBuilder.append("set nthreads     ${threads}\n")
mainListBuilder.append("set _tilde 0\n\n")
if (includeContents) {
  mainListBuilder.append("set _contents 1\n\n")
}

distributions.each { dist ->
  mainListBuilder.append("# ${dist}\n")
  mainListBuilder.append("deb http://deb.debian.org/debian ${dist} ${componentLine}\n")
  if (includeUpdates) {
    mainListBuilder.append("deb http://deb.debian.org/debian ${dist}-updates ${componentLine}\n")
  }
  if (includeBackports) {
    mainListBuilder.append("deb http://deb.debian.org/debian ${dist}-backports ${componentLine}\n")
  }
  mainListBuilder.append("\n")
}
mainListBuilder.append("clean http://deb.debian.org/debian\n")
mainListBuilder.append("set run_postmirror 1\n")

String mainListContent = mainListBuilder.toString()
ensureFile(mainMirrorList, mainListContent, "0644")

List<String> mainSuites = []
distributions.each { dist ->
  mainSuites << dist
  if (includeUpdates) {
    mainSuites << "${dist}-updates"
  }
  if (includeBackports) {
    mainSuites << "${dist}-backports"
  }
}
String suiteList = mainSuites.unique().join(' ')
String componentList = components.join(' ')
String postMirrorContent = """#!/bin/sh
set -u

BASE_URL="http://deb.debian.org/debian"
MIRROR_BASE="${mainBase}/mirror/deb.debian.org/debian"
SUITES="${suiteList}"
COMPONENTS="${componentList}"

if [ "${includeContents ? "1" : "0"}" -eq 1 ]; then
  for suite in $SUITES; do
    for component in $COMPONENTS; do
      src="$BASE_URL/dists/$suite/$component/Contents-all.gz"
      dest="$MIRROR_BASE/dists/$suite/$component/Contents-all.gz"
      mkdir -p "$(dirname "$dest")"
      if ! curl -fsSL -z "$dest" -o "$dest" "$src"; then
        echo "WARN: unable to fetch $src" >&2
      fi
    done
  done
fi

exit 0
"""
ensureFile(postMirrorHook, postMirrorContent, "0755", "root", "root", true)

StringBuilder secListBuilder = new StringBuilder()
secListBuilder.append("############ Debian Security mirror ############\n")
secListBuilder.append("set base_path    ${securityBase}\n")
secListBuilder.append("set nthreads     ${threads}\n")
secListBuilder.append("set _tilde 0\n\n")
securitySuites.each { suite ->
  secListBuilder.append("deb http://security.debian.org/debian-security ${suite} ${componentLine}\n")
}
secListBuilder.append("clean http://security.debian.org/debian-security\n")
secListBuilder.append("set run_postmirror 0\n")

String securityListContent = secListBuilder.toString()
ensureFile(securityMirrorList, securityListContent, "0644")

String apacheConfig = """<VirtualHost *:80>
    ServerName ${siteFqdn}
    ServerAdmin ${apacheAdmin}

    DocumentRoot ${mirrorRoot}

    Alias /debian ${mainAlias}
    <Directory ${mainAlias}>
        Options -MultiViews +Indexes +FollowSymLinks
        AllowOverride None
        Require all granted
        SetEnvIfNoCase Request_URI "\\.(?:gz|bz2|xz|zst|lzma|deb|udeb|iso)\$" no-gzip=1
    </Directory>

    Alias /debian-security ${securityAliasPath}
    <Directory ${securityAliasPath}>
        Options -MultiViews +Indexes +FollowSymLinks
        AllowOverride None
        Require all granted
        SetEnvIfNoCase Request_URI "\\.(?:gz|bz2|xz|zst|lzma|deb|udeb|iso)\$" no-gzip=1
    </Directory>

    IndexOptions FancyIndexing NameWidth=* SuppressHTMLPreamble

    AddType application/vnd.debian.binary-package .deb .udeb
    AddType application/x-xz .xz
    AddType application/zstd .zst
    AddType application/x-bzip2 .bz2
    AddType application/x-gzip .gz

    AddOutputFilterByType DEFLATE text/html text/plain text/css text/javascript application/javascript application/json
    SetEnvIf no-gzip 1 no-gzip

    <FilesMatch "^(InRelease|Release|Release\\.gpg)\$">
        Header set Cache-Control "no-cache, no-store, must-revalidate"
        Header set Pragma "no-cache"
        Header set Expires "0"
    </FilesMatch>
    <FilesMatch "\\.(?:deb|udeb|dsc|gz|xz|bz2|zst|diff|tar|lzma)\$">
        Header set Cache-Control "public, max-age=604800, immutable"
    </FilesMatch>

    ErrorLog ${apacheLogDir}/${siteFqdn}_error.log
    CustomLog ${apacheLogDir}/${siteFqdn}_access.log combined
</VirtualHost>
"""
ensureFile(siteConfPath, apacheConfig, "0644")

ensureApacheModule("headers")
ensureApacheModule("deflate")
ensureApacheModule("autoindex")

disableApacheSite("000-default")
ensureApacheSiteEnabled("${siteFqdn}.conf", siteId)

try {
  ensureSymlink(mainAlias, "${mainBase}/mirror/deb.debian.org/debian")
} catch (IOException ioe) {
  fail("Unable to update Debian mirror symlink", [err: ioe.message])
}

Path securityPath = Paths.get(securityAliasPath)
if (!Files.isDirectory(securityPath)) {
  println "WARNING: Expected security alias path not present yet: ${securityAliasPath}"
}

if (runInitialSync) {
  Path mainDists = Paths.get("${mainBase}/mirror/deb.debian.org/debian/dists")
  Path securityDists = Paths.get("${securityBase}/mirror/security.debian.org/debian-security/dists")
  if (!Files.isDirectory(mainDists) || !Files.isDirectory(securityDists)) {
    println ">>> Running initial apt-mirror sync"
    def mainSync = sh("apt-mirror ${mainMirrorList}")
    if (mainSync.code != 0 && mainSync.code != 2) {
      fail("apt-mirror (main) failed", mainSync)
    }
    def secSync = sh("apt-mirror ${securityMirrorList}")
    if (secSync.code != 0 && secSync.code != 2) {
      fail("apt-mirror (security) failed", secSync)
    }
    changed = true
  }
}

if (cronEnabled) {
  String cronContent = """#!/bin/sh
/usr/bin/apt-mirror ${mainMirrorList}
/usr/bin/apt-mirror ${securityMirrorList}
exit 0
"""
  ensureFile(cronPath, cronContent, "0755")
} else {
  removeFileIfExists(cronPath)
}

sh("find ${mirrorRoot} -type d -exec chmod o+rx {} +")
sh("find ${mirrorRoot} -type f -exec chmod o+r {} +")

if (needReload) {
  def reload = sh("systemctl reload apache2")
  if (reload.code != 0) {
    fail("Failed to reload Apache", reload)
  }
}

List<String> checkSuites = []
distributions.each { dist ->
  checkSuites << "debian/dists/${dist}/Release"
}
securitySuites.each { suite ->
  checkSuites << "debian-security/dists/${suite}/Release"
}

checkSuites.unique().each { path ->
  sh("curl -sI 'http://${siteFqdn}/${path}' | sed -n '1p'")
}

System.exit(changed ? 10 : 0)
