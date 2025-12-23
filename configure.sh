#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage: ./configure.sh [--profiles=NAME[,NAME...]]
       ./configure.sh [--profiles NAME[,NAME...]]
       ./configure.sh [--steps=STEP_KEY[,STEP_KEY...]]
       ./configure.sh [--steps STEP_KEY[,STEP_KEY...]]
       ./configure.sh [--root-only]

Profiles are resolved under \"profiles/\" (overridable with CONFIG_PROFILE_DIR).
Recommended flow:
  1) Run once as root to configure prerequisites.
  2) Run again as your normal user for user-context steps.
USAGE
}

declare -a PROFILE_NAMES=()
declare -a STEP_KEYS=()
ROOT_ONLY=0

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

parse_steps() {
  local csv=$1
  local IFS=','
  for raw in $csv; do
    local name=${raw// /}
    if [[ -n "$name" ]]; then
      STEP_KEYS+=("$name")
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
    --steps)
      if [[ $# -lt 2 ]]; then
        echo "Missing value for --steps" >&2
        usage
        exit 1
      fi
      shift
      parse_steps "$1"
      ;;
    --steps=*)
      parse_steps "${1#*=}"
      ;;
    --root-only)
      ROOT_ONLY=1
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
SNAKEYAML_VERSION="${SNAKEYAML_VERSION:-2.2}"
SNAKEYAML_JAR="${ROOT_DIR}/lib/snakeyaml-${SNAKEYAML_VERSION}.jar"
GROOVY_CLASSPATH="${ROOT_DIR}:${ROOT_DIR}/lib/*"
if [[ -n "${CLASSPATH:-}" ]]; then
  GROOVY_CLASSPATH="${GROOVY_CLASSPATH}:${CLASSPATH}"
fi

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
  ROOT_ONLY=1
  echo "ℹ️  Running as root enables root-only mode; rerun as a normal user to apply user-context steps."
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
if [[ "$ROOT_ONLY" -eq 1 && "$IS_ROOT" -eq 0 ]]; then
  echo "❌ --root-only requires running as root." >&2
  exit 1
fi
SUDO_PREFIX=()
if [[ "$IS_ROOT" -eq 0 ]]; then
  SUDO_PREFIX=(sudo)
fi
SDKMAN_DIR="${SDKMAN_DIR:-$HOME/.sdkman}"
SDKMAN_INIT="${SDKMAN_DIR}/bin/sdkman-init.sh"
BOOTSTRAP_JAVA_VERSION="${BOOTSTRAP_JAVA_VERSION:-21.0.9-tem}"
BOOTSTRAP_GROOVY_VERSION="${BOOTSTRAP_GROOVY_VERSION:-4.0.21}"
MIN_GROOVY_VERSION="${MIN_GROOVY_VERSION:-4.0.21}"
ensure_sdkman() {
  if [[ -f "$SDKMAN_INIT" ]]; then
    return 0
  fi
  echo "📦 SDKMAN not found. Installing..."
  if ! bash -lc "export SDKMAN_DIR='${SDKMAN_DIR}'; export SDKMAN_NON_INTERACTIVE=true; curl -s 'https://get.sdkman.io' | bash"; then
    echo "❌ Failed to install SDKMAN." >&2
    exit 1
  fi
}

ensure_snakeyaml() {
  if [[ -f "$SNAKEYAML_JAR" ]]; then
    return 0
  fi
  echo "📦 Downloading SnakeYAML ${SNAKEYAML_VERSION}..."
  if ! curl -fsSL -o "$SNAKEYAML_JAR" "https://repo1.maven.org/maven2/org/yaml/snakeyaml/${SNAKEYAML_VERSION}/snakeyaml-${SNAKEYAML_VERSION}.jar"; then
    echo "❌ Failed to download SnakeYAML (${SNAKEYAML_VERSION})." >&2
    exit 1
  fi
}

version_ge() {
  local a="$1"
  local b="$2"
  [[ "$(printf '%s\n' "$b" "$a" | sort -V | head -n1)" == "$b" ]]
}

groovy_version_from_bin() {
  local bin="$1"
  local raw
  raw="$("$bin" -version 2>&1 | head -n1 || true)"
  if [[ "$raw" =~ ([0-9]+\.[0-9]+\.[0-9]+) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  fi
}

sdkman_groovy="${SDKMAN_DIR}/candidates/groovy/current/bin/groovy"
GROOVY_BIN=""
if [[ -x "$sdkman_groovy" ]]; then
  GROOVY_BIN="$sdkman_groovy"
else
  GROOVY_BIN="$(command -v groovy || true)"
fi

groovy_version=""
if [[ -n "$GROOVY_BIN" ]]; then
  groovy_version="$(groovy_version_from_bin "$GROOVY_BIN")"
fi

if [[ -z "$GROOVY_BIN" || -z "$groovy_version" || ! $(version_ge "$groovy_version" "$MIN_GROOVY_VERSION") ]]; then
  ensure_sdkman
  echo "📦 Installing Groovy via SDKMAN..."
  if ! bash -lc "source '${SDKMAN_INIT}' && sdk install groovy ${BOOTSTRAP_GROOVY_VERSION} && sdk default groovy ${BOOTSTRAP_GROOVY_VERSION}"; then
    echo "❌ Failed to install Groovy ${BOOTSTRAP_GROOVY_VERSION} via SDKMAN." >&2
    exit 1
  fi
  GROOVY_BIN="${SDKMAN_DIR}/candidates/groovy/current/bin/groovy"
fi
ensure_snakeyaml
GROOVY_JAVA_HOME="${GROOVY_JAVA_HOME:-${JAVA_HOME:-}}"
sdkman_java="${SDKMAN_DIR}/candidates/java/current"
if [[ -x "${sdkman_java}/bin/java" ]]; then
  GROOVY_JAVA_HOME="$sdkman_java"
fi
if [[ -n "$GROOVY_JAVA_HOME" && ! -x "$GROOVY_JAVA_HOME/bin/java" ]]; then
  echo "⚠️  JAVA_HOME points to an invalid JVM (${GROOVY_JAVA_HOME}); ignoring for Groovy." >&2
  GROOVY_JAVA_HOME=""
fi
if [[ -z "$GROOVY_JAVA_HOME" ]]; then
  java_bin="$(command -v java || true)"
  if [[ -n "$java_bin" ]]; then
    java_home_resolved="$(readlink -f "$java_bin" 2>/dev/null || true)"
    if [[ -n "$java_home_resolved" ]]; then
      java_home_resolved="$(dirname "$(dirname "$java_home_resolved")")"
      if [[ -x "$java_home_resolved/bin/java" ]]; then
        GROOVY_JAVA_HOME="$java_home_resolved"
      fi
    fi
  fi
fi
if [[ -z "$GROOVY_JAVA_HOME" ]]; then
  ensure_sdkman
  echo "📦 Installing Java via SDKMAN..."
  if ! bash -lc "source '${SDKMAN_INIT}' && sdk install java ${BOOTSTRAP_JAVA_VERSION}"; then
    echo "❌ Failed to install Java ${BOOTSTRAP_JAVA_VERSION} via SDKMAN." >&2
    exit 1
  fi
  if [[ -x "${SDKMAN_DIR}/candidates/java/current/bin/java" ]]; then
    GROOVY_JAVA_HOME="${SDKMAN_DIR}/candidates/java/current"
  fi
fi
GROOVY_ENV=(env CLASSPATH="$GROOVY_CLASSPATH")
if [[ -n "$GROOVY_JAVA_HOME" ]]; then
  GROOVY_ENV=(env JAVA_HOME="$GROOVY_JAVA_HOME" CLASSPATH="$GROOVY_CLASSPATH")
fi
echo "✅ Groovy present: $(${GROOVY_ENV[@]} "$GROOVY_BIN" -version 2>&1)"
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
if [[ ${#STEP_KEYS[@]} -gt 0 ]]; then
  echo "    Steps: ${STEP_KEYS[*]}"
fi
if [[ "$ROOT_ONLY" -eq 1 ]]; then
  echo "    Mode: root-only (user-context steps disabled)"
fi

if [[ ! -f "$CONFIG_FILE" && ! -f "$HOST_CONFIG_FILE" ]]; then
  template_hint="$CONFIG_ROOT/config-template.yaml"
  echo "⚠️  No configuration files found. Copy \"${template_hint}\" to config.yaml or ${HOST_ID_SHORT}.yaml before running." >&2
  exit 1
fi

user_list_script="$(mktemp)"
cat <<'EOF' >"$user_list_script"
#!/usr/bin/env groovy
import lib.ConfigLoader

def cfg = ConfigLoader.loadAll()
def raw = cfg.userStepUsers
def names = []
if (raw instanceof Collection) {
  raw.each { entry ->
    def text = entry?.toString()?.trim()
    if (text) {
      names << text
    }
  }
} else if (raw != null) {
  raw.toString().split(',')
    .collect { it.trim() }
    .findAll { it }
    .each { names << it }
}
println names.join(',')
EOF
chmod +x "$user_list_script"

user_list_tmpdir="$(mktemp -d 2>/dev/null || true)"
USER_STEP_USERS=""
if [[ -n "$user_list_tmpdir" && -d "$user_list_tmpdir" ]]; then
  if USER_STEP_USERS=$("${GROOVY_ENV[@]}" "$GROOVY_BIN" "-Dgroovy.target.directory=$user_list_tmpdir" "$user_list_script"); then
    :
  else
    USER_STEP_USERS=""
  fi
  rm -rf "$user_list_tmpdir" 2>/dev/null || true
fi
rm -f "$user_list_script"

if [[ -n "$USER_STEP_USERS" ]]; then
  echo "    User-step allowlist: $USER_STEP_USERS"
fi
readarray -d '' STEP_FILES < <(find "$STEPS_DIR" -mindepth 1 -maxdepth 2 -type f -name '*.groovy' -print0 | sort -z)

if [[ ${#STEP_FILES[@]} -eq 0 ]]; then
  echo "⚠️  No step scripts found under $STEPS_DIR" >&2
  exit 1
fi

for i in "${!STEP_FILES[@]}"; do
  STEP_FILES[$i]="${STEP_FILES[$i]%$'\n'}"
done

if [[ ${#STEP_KEYS[@]} -gt 0 ]]; then
  declare -A STEP_KEY_SET=()
  for key in "${STEP_KEYS[@]}"; do
    STEP_KEY_SET["$key"]=1
  done
  filtered_steps=()
  found_keys=()
  for step in "${STEP_FILES[@]}"; do
    step_key="$(sed -nE "s/.*\\bstepKey\\b\\s*=\\s*['\"]([^'\"]+)['\"].*/\\1/p" "$step" | head -n1 || true)"
    if [[ -n "$step_key" && -n "${STEP_KEY_SET[$step_key]:-}" ]]; then
      filtered_steps+=("$step")
      found_keys+=("$step_key")
    fi
  done
  missing_keys=()
  for key in "${STEP_KEYS[@]}"; do
    if [[ ! " ${found_keys[*]} " =~ " ${key} " ]]; then
      missing_keys+=("$key")
    fi
  done
  if [[ ${#missing_keys[@]} -gt 0 ]]; then
    echo "❌ Step key(s) not found: ${missing_keys[*]}" >&2
    exit 1
  fi
  STEP_FILES=("${filtered_steps[@]}")
fi

skipped_root_pre_nsswitch=()
skipped_user_steps=()
skipped_root_only_steps=()
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
  def text = file.getText('UTF-8')
  def matcher = (text =~ /\bstepKey\b\s*=\s*[\"']([^\"']+)[\"']/)
  if (!matcher.find()) {
    System.err.println("⚠️  Unable to determine step key for ${file}")
    failures << file.absolutePath
    return
  }
  def key = matcher.group(1)
  def isPrereq = (text =~ /\bPREREQ_ROOT\b/).find()
  def entry = steps[key]
  if (entry == null) {
    if (isPrereq) {
      System.err.println("❌ Prerequisite step '${key}' is missing from configuration.")
      failures << key
      return
    }
    System.err.println("⚠️  Missing configuration for step '${key}'. Assuming disabled.")
    return
  }
  if (!(entry instanceof Map)) {
    System.err.println("⚠️  Configuration for step '${key}' should be a mapping (${key}), but found ${entry.getClass().simpleName()} -> ${entry}.")
    failures << key
    return
  }
  boolean enabled = true
  if (entry.containsKey('enabled')) {
    enabled = entry.enabled != false
  }
  if (isPrereq && !enabled) {
    System.err.println("❌ Prerequisite step '${key}' is disabled in configuration.")
    failures << key
    return
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

if ! "${GROOVY_ENV[@]}" "$GROOVY_BIN" "-Dgroovy.target.directory=$validator_tmpdir" "$validator_script" "${STEP_FILES[@]}"; then
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
  requires_sudo_context=0
  if grep -qE '^[[:space:]]*//[[:space:]]*RUN_AS_USER' "$step"; then
    requires_user_context=1
  fi
  if grep -qE '^[[:space:]]*//[[:space:]]*RUN_VIA_SUDO' "$step"; then
    requires_sudo_context=1
  fi
  if [[ "$requires_user_context" -eq 1 && "$requires_sudo_context" -eq 1 ]]; then
    echo "→ ERROR (step cannot declare both RUN_AS_USER and RUN_VIA_SUDO)."
    exit 1
  fi
  requires_root_context=$((requires_user_context == 0 ? 1 : 0))
  step_is_nsswitch=0
  if [[ "$rel_path" == *"/Nsswitch/"* ]] || [[ "$rel_path" == 25.0-Nsswitch* ]]; then
    step_is_nsswitch=1
  fi
  if [[ "$IS_ROOT" -eq 0 && "$requires_root_context" -eq 1 && "$nsswitch_ready" -eq 0 && "$requires_sudo_context" -eq 0 ]]; then
    echo "→ Verifying (pre-Nsswitch root step; may apply changes)."
  fi
  if [[ "$requires_user_context" -eq 1 && "$IS_ROOT" -eq 1 ]]; then
    echo "→ Skipping (requires non-root user context)."
    skipped_user_steps+=("$rel_path")
    continue
  fi
  if [[ "$ROOT_ONLY" -eq 1 && ( "$requires_user_context" -eq 1 || "$requires_sudo_context" -eq 1 ) ]]; then
    echo "→ Skipping (root-only mode)."
    skipped_root_only_steps+=("$rel_path")
    continue
  fi
  if [[ "$requires_user_context" -eq 1 && -n "$USER_STEP_USERS" ]]; then
    allowed=0
    IFS=',' read -r -a allowed_users <<<"$USER_STEP_USERS"
    for allowed_user in "${allowed_users[@]}"; do
      if [[ "$allowed_user" == "$CURRENT_USER" ]]; then
        allowed=1
        break
      fi
    done
    if [[ "$allowed" -eq 0 ]]; then
      echo "→ Skipping (user '${CURRENT_USER}' not in allowlist)."
      skipped_user_steps+=("$rel_path")
      continue
    fi
  fi
  step_tmpdir="$(mktemp -d 2>/dev/null || true)"
  if [[ -z "$step_tmpdir" || ! -d "$step_tmpdir" ]]; then
    echo "⚠️  Unable to create temporary directory for ${rel_path}" >&2
    exit 1
  fi
  export GROOVY_TARGET_DIR="$step_tmpdir"
  if [[ "$requires_user_context" -eq 1 ]]; then
    runner=("${GROOVY_ENV[@]}" "$GROOVY_BIN" "-Dgroovy.target.directory=$GROOVY_TARGET_DIR" "$step")
  else
    if [[ "$requires_sudo_context" -eq 1 ]]; then
      if [[ "$IS_ROOT" -eq 1 ]]; then
        runner=("${GROOVY_ENV[@]}" "$GROOVY_BIN" "-Dgroovy.target.directory=$GROOVY_TARGET_DIR" "$step")
      else
        runner=(sudo -E "${GROOVY_ENV[@]}" "$GROOVY_BIN" "-Dgroovy.target.directory=$GROOVY_TARGET_DIR" "$step")
      fi
    else
      if [[ "$IS_ROOT" -eq 1 ]]; then
        runner=("${GROOVY_ENV[@]}" "$GROOVY_BIN" "-Dgroovy.target.directory=$GROOVY_TARGET_DIR" "$step")
      else
        runner=(sudo -E "${GROOVY_ENV[@]}" "$GROOVY_BIN" "-Dgroovy.target.directory=$GROOVY_TARGET_DIR" "$step")
      fi
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
if [[ "${#skipped_root_only_steps[@]}" -gt 0 ]]; then
  printf 'ℹ️  Skipped steps due to --root-only: %s\n' "${skipped_root_only_steps[*]}"
fi
echo
echo "✅ All steps processed."
