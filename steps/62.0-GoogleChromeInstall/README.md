# 62.0 – Google Chrome Install

Installs the latest stable Google Chrome browser using Google's apt repository.

- **Config key**: `GoogleChromeInstall`
  - `enabled` (bool, optional) – set false to skip.

Requires root privileges to add the repository, install packages, and drop the Google signing key. Uses shared step utilities for command execution.
