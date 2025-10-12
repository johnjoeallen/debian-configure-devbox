#!/usr/bin/env groovy
// --- Documentation ---
// Summary: Install JetBrains Toolbox launcher for managing IDEs.
// Config keys: none
// Notes: Downloads the latest tarball, extracts, and runs the installer once.

import static lib.StepUtils.sh

def home = System.getenv("HOME")
def marker = new File("${home}/.local/share/JetBrains/Toolbox")
if (marker.exists()) System.exit(0)
def r = sh('bash -lc \'TOOLBOX_URL="https://data.services.jetbrains.com/products/download?code=TBA&platform=linux"; wget -O /tmp/toolbox.tar.gz "$TOOLBOX_URL"\'')
if (r.code!=0) { System.err.println(r.err); System.exit(1) }
def r2 = sh('bash -lc \'mkdir -p /tmp/jb && tar -xzf /tmp/toolbox.tar.gz -C /tmp/jb && cd /tmp/jb/jetbrains-toolbox* && nohup ./bin/jetbrains-toolbox >/dev/null 2>&1 &\'')
if (r2.code!=0) { System.err.println(r2.err); System.exit(1) }
System.exit(10)
