#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage: ./configure.sh [--profiles=NAME[,NAME...]]
       ./configure.sh [--profiles NAME[,NAME...]]

Profiles are resolved under \"profiles/\" (overridable with CONFIG_PROFILE_DIR).
USAGE
}

declare -a PROFILE_NAMES=()

parse_profiles() {
  local csv=$1
  local IFS=','
  for raw in $csv; do
    local name=${raw// /}
    if [[ -n "$name" ]]; then
      PROFILE_NAMES+=("$name")
    fi
  done
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profiles)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for --profiles" >&2
        usage
        exit 1
      fi
      shift
      parse_profiles "$1"
      ;;
    --profiles=*)
      parse_profiles "${1#*=}"
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage
      exit 1
      ;;
  esac
  shift
done

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
STEPS_DIR="${STEPS_DIR:-$ROOT_DIR/steps}"
CONFIG_ROOT="${CONFIG_ROOT:-$ROOT_DIR}"
CONFIG_FILE="${CONFIG_FILE:-$CONFIG_ROOT/config.yaml}"
HOST_ID_SHORT="${CONFIG_HOSTNAME:-$(hostname -s 2>/dev/null || hostname || echo unknown)}"
HOST_CONFIG_FILE="${CONFIG_HOST_FILE:-$CONFIG_ROOT/${HOST_ID_SHORT}.yaml}"
PROFILE_DIR="${CONFIG_PROFILE_DIR:-$CONFIG_ROOT/profiles}"

if [[ ${#PROFILE_NAMES[@]} -gt 0 ]]; then
  for profile in "${PROFILE_NAMES[@]}"; do
    profile_file="${PROFILE_DIR}/${profile}.yaml"
    if [[ ! -f "$profile_file" ]]; then
      echo "❌ Profile not found: $profile_file" >&2
      exit 1
    fi
  done
fi

if [[ ${#PROFILE_NAMES[@]} -gt 0 ]]; then
  CONFIG_PROFILES=$(IFS=','; printf '%s' "${PROFILE_NAMES[*]}")
else
  CONFIG_PROFILES=""
fi

export CONFIG_ROOT CONFIG_FILE HOST_CONFIG_FILE HOST_ID_SHORT CONFIG_PROFILE_DIR="${PROFILE_DIR}" CONFIG_PROFILES
PAUSE_ON_CHANGED="${PAUSE_ON_CHANGED:-1}"
PAUSE_ON_ERROR="${PAUSE_ON_ERROR:-1}"
CURRENT_USER="$(id -un)"
IS_ROOT=0
if [[ "$CURRENT_USER" == "root" ]]; then
  IS_ROOT=1
else
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
fi
SUDO_PREFIX=()
if [[ "$IS_ROOT" -eq 0 ]]; then
  SUDO_PREFIX=(sudo)
fi
if ! command -v groovy >/dev/null 2>&1; then
  echo "📦 Groovy not found. Installing..."
  "${SUDO_PREFIX[@]}" apt update -y
  "${SUDO_PREFIX[@]}" apt install -y groovy
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
if [[ ${#PROFILE_NAMES[@]} -gt 0 ]]; then
  echo "    Profiles: ${PROFILE_NAMES[*]} (dir: $PROFILE_DIR)"
else
  echo "    Profiles: (none)"
fi

if [[ ! -f "$CONFIG_FILE" && ! -f "$HOST_CONFIG_FILE" ]]; then
  template_hint="$CONFIG_ROOT/config-template.yaml"
  echo "⚠️  No configuration files found. Copy \"${template_hint}\" to config.yaml or ${HOST_ID_SHORT}.yaml before running." >&2
  exit 1
fi
readarray -d '' STEP_FILES < <(find "$STEPS_DIR" -mindepth 1 -maxdepth 2 -type f -name '*.groovy' -print0 | sort -z)

if [[ ${#STEP_FILES[@]} -eq 0 ]]; then
  echo "⚠️  No step scripts found under $STEPS_DIR" >&2
  exit 1
fi

for i in "${!STEP_FILES[@]}"; do
  STEP_FILES[$i]="${STEP_FILES[$i]%$'\n'}"
done

skipped_root_pre_nsswitch=()
skipped_user_steps=()
nsswitch_ready=0

validator_script="$(mktemp)"
cat <<'EOF' >"$validator_script"
#!/usr/bin/env groovy
import lib.ConfigLoader

def stepFiles = this.args.collect { new File(it) }
Map merged = ConfigLoader.loadAll()
Map steps = (merged.steps instanceof Map) ? (Map) merged.steps : [:]

Set<String> failures = new LinkedHashSet<>()
stepFiles.each { file ->
  if (!file.exists()) {
    System.err.println("⚠️  Step file missing: ${file}")
    failures << file.absolutePath
    return
  }
  def matcher = (file.getText('UTF-8') =~ /\bstepKey\b\s*=\s*[\"']([^\"']+)[\"']/)
  if (!matcher.find()) {
    System.err.println("⚠️  Unable to determine step key for ${file}")
    failures << file.absolutePath
    return
  }
  def key = matcher.group(1)
  def entry = steps[key]
  if (!(entry instanceof Map)) {
    if (entry == null) {
      System.err.println("⚠️  Missing configuration for step '${key}' (expected ${key}).")
    } else {
      System.err.println("⚠️  Configuration for step '${key}' should be a mapping (${key}), but found ${entry.getClass().simpleName()} -> ${entry}.")
    }
    failures << key
    return
  }
  boolean enabled = true
  if (entry.containsKey('enabled')) {
    enabled = entry.enabled != false
  }
  if (!enabled) {
    return
  }
  // Config entry present and enabled; nothing more to validate here.
}

if (!failures.isEmpty()) {
  System.exit(4)
}
EOF
chmod +x "$validator_script"

validator_tmpdir="$(mktemp -d 2>/dev/null || true)"
if [[ -z "$validator_tmpdir" || ! -d "$validator_tmpdir" ]]; then
  echo "⚠️  Unable to create temporary directory for validator" >&2
  rm -f "$validator_script"
  exit 1
fi

if ! "$GROOVY_BIN" "-Dgroovy.target.directory=$validator_tmpdir" "$validator_script" "${STEP_FILES[@]}"; then
  rm -rf "$validator_tmpdir"
  rm -f "$validator_script"
  echo "❌ Step configuration validation failed. Resolve the warnings above before rerunning." >&2
  exit 1
fi

rm -rf "$validator_tmpdir"
rm -f "$validator_script"

step_index=0
for step in "${STEP_FILES[@]}"; do
  [[ -z "$step" ]] && continue
  step_index=$((step_index+1))
  rel_path="${step#$STEPS_DIR/}"
  echo
  echo "[$step_index] === $rel_path ==="
  requires_user_context=0
  if grep -qE '^[[:space:]]*//[[:space:]]*RUN_AS_USER' "$step"; then
    requires_user_context=1
  fi
  requires_root_context=$((requires_user_context == 0 ? 1 : 0))
  step_is_nsswitch=0
  if [[ "$rel_path" == *"/Nsswitch/"* ]] || [[ "$rel_path" == 25.0-Nsswitch* ]]; then
    step_is_nsswitch=1
  fi
  if [[ "$IS_ROOT" -eq 0 && "$requires_root_context" -eq 1 && "$nsswitch_ready" -eq 0 ]]; then
    echo "→ Skipping (requires root before Nsswitch completes)."
    skipped_root_pre_nsswitch+=("$rel_path")
    if [[ "$step_is_nsswitch" -eq 1 ]]; then
      nsswitch_ready=1
    fi
    continue
  fi
  if [[ "$requires_user_context" -eq 1 && "$IS_ROOT" -eq 1 ]]; then
    echo "→ Skipping (requires non-root user context)."
    skipped_user_steps+=("$rel_path")
    continue
  fi
  step_tmpdir="$(mktemp -d 2>/dev/null || true)"
  if [[ -z "$step_tmpdir" || ! -d "$step_tmpdir" ]]; then
    echo "⚠️  Unable to create temporary directory for ${rel_path}" >&2
    exit 1
  fi
  export GROOVY_TARGET_DIR="$step_tmpdir"
  if [[ "$requires_user_context" -eq 1 ]]; then
    runner=("$GROOVY_BIN" "-Dgroovy.target.directory=$GROOVY_TARGET_DIR" "$step")
  else
    if [[ "$IS_ROOT" -eq 1 ]]; then
      runner=("$GROOVY_BIN" "-Dgroovy.target.directory=$GROOVY_TARGET_DIR" "$step")
    else
      runner=(sudo -E "$GROOVY_BIN" "-Dgroovy.target.directory=$GROOVY_TARGET_DIR" "$step")
    fi
  fi
  set +e
  "${runner[@]}"
  rc=$?
  set -e
  if ! rm -rf "$step_tmpdir" 2>/dev/null; then
    if [[ "$IS_ROOT" -eq 0 ]]; then
      sudo rm -rf "$step_tmpdir" 2>/dev/null || true
    fi
  fi
  unset GROOVY_TARGET_DIR
  if [[ "$step_is_nsswitch" -eq 1 && "$rc" -lt 11 ]]; then
    nsswitch_ready=1
  fi
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
done
if [[ "${#skipped_root_pre_nsswitch[@]}" -gt 0 ]]; then
  printf 'ℹ️  Skipped pre-Nsswitch root steps (run as root to apply): %s\n' "${skipped_root_pre_nsswitch[*]}"
fi
if [[ "${#skipped_user_steps[@]}" -gt 0 ]]; then
  printf 'ℹ️  Skipped user-context steps (rerun as non-root with sudo): %s\n' "${skipped_user_steps[*]}"
fi
echo
echo "✅ All steps processed."
