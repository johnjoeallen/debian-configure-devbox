#!/usr/bin/env groovy
// RUN_AS_USER
// --- Documentation ---
// Summary: Configure the Git-aware shell prompt for the invoking user.
// Config keys: promptFile, bashrcPath, enabled
// Notes: Writes the prompt settings to a standalone file and ensures .bashrc sources it.

import lib.ConfigLoader

def stepKey = "GitPrompt"
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

def expandPath = { String path ->
  if (!path) {
    return null
  }
  if (path.startsWith("~")) {
    return path.replaceFirst("^~", home)
  }
  return path
}

def promptPath = expandPath(stepConfig.promptFile ?: "${home}/.git-prompt.sh")
def bashrcPath = expandPath(stepConfig.bashrcPath ?: "${home}/.bashrc")

def promptFile = new File(promptPath)
if (promptFile.parentFile && !promptFile.parentFile.exists()) {
  if (!promptFile.parentFile.mkdirs()) {
    System.err.println("Failed to create directory for ${promptPath}")
    System.exit(1)
  }
}

def promptContent = '''# Enable git prompt
if [ -f /usr/lib/git-core/git-sh-prompt ]; then
    source /usr/lib/git-core/git-sh-prompt
    export GIT_PS1_SHOWDIRTYSTATE=1
    export GIT_PS1_SHOWSTASHSTATE=1
    export GIT_PS1_SHOWUNTRACKEDFILES=1
    export GIT_PS1_SHOWUPSTREAM="auto"
    PS1='\\u@\\h:\\w$(__git_ps1 " (%s)")\\$ '
fi
'''

boolean promptUpdated = false
if (!promptFile.exists() || promptFile.text != promptContent) {
  promptFile.text = promptContent
  promptUpdated = true
}

def bashrcFile = new File(bashrcPath)
def bashrcContent = bashrcFile.exists() ? bashrcFile.text : ""
def sourceBlock = """# Load git prompt configuration\nif [ -f \"${promptFile.absolutePath}\" ]; then\n  . \"${promptFile.absolutePath}\"\nfi\n"""

boolean bashrcUpdated = false
if (!bashrcContent.contains(promptFile.absolutePath)) {
  def updated = new StringBuilder()
  updated << bashrcContent
  if (bashrcContent && !bashrcContent.endsWith("\n")) {
    updated << "\n"
  }
  if (bashrcContent) {
    updated << "\n"
  }
  updated << sourceBlock
  bashrcFile.text = updated.toString()
  bashrcUpdated = true
}

if (promptUpdated || bashrcUpdated) {
  println "Configured git prompt: file=${promptFile.absolutePath}, sourced via ${bashrcFile.absolutePath}"
  System.exit(10)
}

println "Git prompt already configured"
System.exit(0)
