#!/usr/bin/env bash
# build-install-local.sh
# Builds the Andalus‑Aqli CLI and installs a 'andalus' binary locally.
# No module-selection-*.env dependency.

set -euo pipefail

# ---------- defaults ----------
INSTALL_DIR="${HOME}/.local/bin"
SHARE_DIR="${INSTALL_DIR}/share"
BINARY_NAME="andalus"

# ---------- locate project ----------
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CLI_PROJECT="${REPO_ROOT}/cli/andalus-cli"

# ---------- argument parsing ----------
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      echo "Usage: $(basename "$0") [-i|--install-dir DIR]"
      exit 0
      ;;
    -i|--install-dir)
      INSTALL_DIR="$2"
      SHARE_DIR="${INSTALL_DIR}/share"
      shift 2
      ;;
    *)
      echo "Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

# ---------- sanity check ----------
if [[ ! -d "$CLI_PROJECT" ]]; then
  echo "ERROR: CLI project not found at: $CLI_PROJECT" >&2
  exit 1
fi

# ---------- build ----------
echo "INFO: Building Andalus CLI ..."
cd "$REPO_ROOT"

# Prefer local mvnw wrapper if available, else fall back to system mvn
if [[ -f "./mvnw" ]]; then
  chmod +x ./mvnw
  MVN="./mvnw"
elif command -v mvn &>/dev/null; then
  MVN="mvn"
else
  echo "ERROR: Neither ./mvnw nor mvn found on PATH" >&2
  exit 1
fi

# Step 1: build and install the SDK into local Maven repo
SDK_PROJECT="${REPO_ROOT}/sdk/andalus-aqli-sdk"
echo "INFO: Building SDK dependency ..."
"$MVN" clean install -f "$SDK_PROJECT/pom.xml" -DskipTests --no-transfer-progress

# Step 2: build the CLI (which depends on the SDK now in ~/.m2)
echo "INFO: Building CLI ..."
"$MVN" clean package -f "$CLI_PROJECT/pom.xml" -DskipTests --no-transfer-progress

# Switch back to CLI project to locate the jar
cd "$CLI_PROJECT"

# ---------- locate jar ----------
# The shade plugin replaces the original artifact with the fat jar.
# Exclude 'original-*' which is the pre-shading thin jar.
JAR_FILE=""
JAR_FILE="$(find target -maxdepth 1 -name "*.jar" -not -name "original-*" -not -name "*-sources.jar" -print -quit)"

if [[ -z "$JAR_FILE" ]]; then
  echo "ERROR: No shaded JAR found in target/" >&2
  exit 1
fi
echo "INFO: JAR built at: $JAR_FILE"

# ---------- install ----------
mkdir -p "$INSTALL_DIR" "$SHARE_DIR"
cp "$JAR_FILE" "${SHARE_DIR}/andalus-aqli-cli.jar"

WRAPPER="${INSTALL_DIR}/${BINARY_NAME}"
cat > "$WRAPPER" <<EOF
#!/usr/bin/env bash
exec java \
  --add-modules jdk.incubator.vector \
  -Djava.util.logging.manager=org.jboss.logmanager.LogManager \
  -jar "$SHARE_DIR/andalus-aqli-cli.jar" "\$@"
EOF
chmod +x "$WRAPPER"

echo "INFO: Installed '$BINARY_NAME' wrapper to $WRAPPER"
echo ""
echo "Make sure $INSTALL_DIR is on your PATH:"
echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
echo ""
echo "Then run: andalus --help"
