#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
STEPS_DIR="${STEPS_DIR:-$ROOT_DIR/steps}"
CONFIG_ROOT="${CONFIG_ROOT:-$ROOT_DIR}"
CONFIG_FILE="${CONFIG_FILE:-$CONFIG_ROOT/config.yaml}"
HOST_ID_SHORT="${CONFIG_HOSTNAME:-$(hostname -s 2>/dev/null || hostname || echo unknown)}"
HOST_CONFIG_FILE="${CONFIG_HOST_FILE:-$CONFIG_ROOT/${HOST_ID_SHORT}.yaml}"
export CONFIG_ROOT CONFIG_FILE HOST_CONFIG_FILE HOST_ID_SHORT
PAUSE_ON_CHANGED="${PAUSE_ON_CHANGED:-1}"
PAUSE_ON_ERROR="${PAUSE_ON_ERROR:-1}"
CURRENT_USER="$(id -un)"
if ! id -nG "$CURRENT_USER" | tr ' ' '\n' | grep -Eq '^(sudo|wheel|admin)$'; then
  cat <<EOF
❌ sudo access required for user: $CURRENT_USER

This configure script must run under an account with sudo privileges (the admin group is created later in the run).
To grant access:
  su -
  usermod -aG sudo $CURRENT_USER
  exit
Then log out, log back in, and rerun ./configure.sh
After configure completes log out/in so your new admin group membership (added automatically) takes effect.
EOF
  exit 1
fi
if ! command -v groovy >/dev/null 2>&1; then
  echo "📦 Groovy not found. Installing..."
  sudo apt update -y
  sudo apt install -y groovy
  echo "✅ Installed: $(groovy -version 2>&1)"
else
  echo "✅ Groovy present: $(groovy -version 2>&1)"
fi
GROOVY_BIN="$(command -v groovy)"
echo "==> Running provision steps from: $STEPS_DIR"
echo "    Exit codes: 0=NOOP, 10=CHANGED (pause), 1-9=ERROR (pause)"
if [[ -f "$CONFIG_FILE" ]]; then
  echo "    Base config: $CONFIG_FILE"
else
  echo "    Base config: (missing at $CONFIG_FILE)"
fi
if [[ -f "$HOST_CONFIG_FILE" ]]; then
  echo "    Host config: $HOST_CONFIG_FILE"
else
  echo "    Host config: (missing at $HOST_CONFIG_FILE)"
fi

if [[ ! -f "$CONFIG_FILE" && ! -f "$HOST_CONFIG_FILE" ]]; then
  template_hint="$CONFIG_ROOT/config-template.yaml"
  echo "⚠️  No configuration files found. Copy \"${template_hint}\" to config.yaml or ${HOST_ID_SHORT}.yaml before running." >&2
  exit 1
fi
step_index=0
while IFS= read -r -d '' step; do
  step_index=$((step_index+1))
  rel_path="${step#$STEPS_DIR/}"
  echo
  echo "[$step_index] === $rel_path ==="
  if grep -qE '^[[:space:]]*//[[:space:]]*RUN_AS_ROOT' "$step"; then
    runner=(sudo -E "$GROOVY_BIN" "$step")
  else
    runner=("$GROOVY_BIN" "$step")
  fi
  set +e
  "${runner[@]}"
  rc=$?
  set -e
  case "$rc" in
    0) echo "→ NOOP (already compliant). Continuing...";;
    10) echo "→ CHANGED (work done)."
        if [[ "$PAUSE_ON_CHANGED" == "1" ]]; then
          read -r -p "Press Enter to continue, or 'q' to quit: " ans
          [[ "${ans:-}" == "q" ]] && exit 0
        fi ;;
    *) echo "→ ERROR (rc=$rc)."
        if [[ "$PAUSE_ON_ERROR" == "1" ]]; then
          read -r -p "Press Enter to continue anyway, or 'q' to quit: " ans
          [[ "${ans:-}" == "q" ]] && exit "$rc"
        fi ;;
  esac
done < <(find "$STEPS_DIR" -mindepth 1 -maxdepth 2 -type f -name '*.groovy' -print0 | sort -z)
echo
echo "✅ All steps processed."
