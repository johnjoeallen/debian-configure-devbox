#!/usr/bin/env groovy
// RUN_VIA_SUDO
// --- Documentation ---
// Summary: Extend systemd-logind idle suspend timeout and action using drop-in config.
// Config keys: idleAction (string), timeout (ISO-8601 duration), timeoutSeconds (number legacy)
// Notes: Writes /etc/systemd/logind.conf.d/50-devbox-idle.conf and signals logind to reload.

import java.nio.file.Paths
import java.time.Duration

import lib.ConfigLoader
import static lib.StepUtils.sh

def stepKey = "ExtendSuspend"
if (!ConfigLoader.stepEnabled(stepKey)) {
  println "${stepKey} disabled via configuration"
  System.exit(0)
}
def stepConfig = ConfigLoader.stepConfig(stepKey)

String idleAction = (stepConfig.idleAction ?: "suspend").toString().trim()
if (!idleAction) {
  idleAction = "suspend"
}

Long timeoutSeconds = null
boolean disableIdle = false
def timeoutSpec = stepConfig.timeout != null ? stepConfig.timeout : stepConfig.timeoutSeconds
if (timeoutSpec instanceof Number) {
  timeoutSeconds = ((Number) timeoutSpec).longValue()
} else if (timeoutSpec instanceof CharSequence) {
  def txt = timeoutSpec.toString().trim()
  if (!txt.isEmpty()) {
    if (txt.equalsIgnoreCase("off") || txt.equalsIgnoreCase("inf")) {
      disableIdle = true
      timeoutSeconds = 0L
    }
    try {
      if (!disableIdle) {
        timeoutSeconds = Duration.parse(txt).seconds
      }
    } catch (Exception ignored) {
      if (!disableIdle) {
        timeoutSeconds = txt.isInteger() ? Long.parseLong(txt) : null
      }
    }
  }
}
if (!disableIdle && (timeoutSeconds == null || timeoutSeconds <= 0)) {
  timeoutSeconds = 3600L
}

String effectiveAction = idleAction
String idleActionSec = timeoutSeconds + "s"
if (disableIdle) {
  effectiveAction = "ignore"
  idleActionSec = "0"
}
String desired = """# Managed by debian-configure-devbox ExtendSuspend step\n[Login]\nIdleAction=${effectiveAction}\nIdleActionSec=${idleActionSec}\n"""

def targetDir = Paths.get("/etc/systemd/logind.conf.d")
def targetFile = targetDir.resolve("50-devbox-idle.conf").toFile()

def dirRes = sh("install -d -m 0755 -o root -g root '${targetDir}'")
if (dirRes.code != 0) {
  System.err.println("Failed to ensure ${targetDir} exists: ${dirRes.err ?: dirRes.out}")
  System.exit(1)
}

boolean changed = false
if (!targetFile.exists() || targetFile.text.replaceAll(/\s+\z/, "") != desired.replaceAll(/\s+\z/, "")) {
  targetFile.withWriter("UTF-8") { it << desired }
  changed = true
}

if (!changed) {
  if (disableIdle) {
    println "Idle suspend already disabled"
  } else {
    println "Idle suspend timeout already set to ${timeoutSeconds}s"
  }
  System.exit(0)
}

def reload = sh("systemctl kill -s HUP systemd-logind.service")
if (reload.code == 0) {
  println "Reloaded systemd-logind configuration."
} else {
  println "Updated configuration; please restart systemd-logind or reboot to apply (rc=${reload.code})."
}

if (disableIdle) {
  println "Disabled system idle suspend (IdleAction=ignore, IdleActionSec=0)."
} else {
  println "Set system idle suspend action to '${effectiveAction}' after ${timeoutSeconds}s."
}
System.exit(10)
