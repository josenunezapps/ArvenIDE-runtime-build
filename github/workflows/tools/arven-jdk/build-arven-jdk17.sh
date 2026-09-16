#!/usr/bin/env bash
set -euo pipefail

ARVEN_PACKAGE="com.arven.ide"
ARCH="${1:-aarch64}"
WORK_ROOT="${2:-$PWD/.arven-jdk-build}"
TERMUX_REPO="$WORK_ROOT/termux-packages"
OUTPUT_DIR="$WORK_ROOT/output"

echo "[Arven JDK] Package: $ARVEN_PACKAGE"
echo "[Arven JDK] Architecture: $ARCH"
echo "[Arven JDK] Work dir: $WORK_ROOT"

rm -rf "$WORK_ROOT"
mkdir -p "$WORK_ROOT" "$OUTPUT_DIR"

echo "[1/5] Cloning Termux packages..."
git clone --depth 1 https://github.com/termux/termux-packages.git "$TERMUX_REPO"

cd "$TERMUX_REPO"

echo "[2/5] Configuring Termux build for Arven's private prefix..."
python3 - <<'PY'
from pathlib import Path
p = Path("scripts/properties.sh")
text = p.read_text(encoding="utf-8")
old = 'TERMUX_APP__PACKAGE_NAME="com.termux"'
new = 'TERMUX_APP__PACKAGE_NAME="com.arven.ide"'
if old not in text:
    raise SystemExit("Could not find TERMUX_APP__PACKAGE_NAME in scripts/properties.sh")
p.write_text(text.replace(old, new, 1), encoding="utf-8")
PY

grep -n 'TERMUX_APP__PACKAGE_NAME=' scripts/properties.sh | head -n 1

echo "[3/5] Building Android-10-compatible bootstrap + OpenJDK 17..."
# The bootstrap builder builds every dependency from source because a custom
# package name/prefix cannot safely mix packages built for com.termux.
./scripts/run-docker.sh \
    ./scripts/build-bootstraps.sh \
    --android10 \
    --architectures "$ARCH" \
    --add openjdk-17

BOOTSTRAP="$TERMUX_REPO/bootstrap-$ARCH.zip"
if [ ! -f "$BOOTSTRAP" ]; then
    echo "ERROR: bootstrap was not created: $BOOTSTRAP" >&2
    exit 1
fi

echo "[4/5] Validating JDK/runtime contents..."
python3 - "$BOOTSTRAP" <<'PY'
import sys, zipfile

archive = sys.argv[1]
required = [
    "lib/jvm/java-17-openjdk/bin/java",
    "lib/jvm/java-17-openjdk/bin/javac",
    "lib/jvm/java-17-openjdk/lib/modules",
    "lib/jvm/java-17-openjdk/lib/libjava.so",
    "lib/jvm/java-17-openjdk/lib/server/libjvm.so",
    "lib/libtermux-exec.so",
    "SYMLINKS.txt",
]

with zipfile.ZipFile(archive) as z:
    names = set(z.namelist())
    missing = [p for p in required if p not in names]
    if missing:
        print("Missing required runtime files:", file=sys.stderr)
        for item in missing:
            print(" -", item, file=sys.stderr)
        raise SystemExit(2)

print("Runtime archive contains all required JDK 17 files.")
PY

echo "[5/5] Preparing Arven artifact..."
FINAL="$OUTPUT_DIR/arven-jdk17-$ARCH.zip"
cp "$BOOTSTRAP" "$FINAL"

(
    cd "$OUTPUT_DIR"
    sha256sum "$(basename "$FINAL")" > "$(basename "$FINAL").sha256"
)

TERMUX_COMMIT="$(git rev-parse HEAD)"
cat > "$OUTPUT_DIR/build-info.txt" <<EOF
Arven JDK 17 runtime
package=$ARVEN_PACKAGE
architecture=$ARCH
termux-packages-commit=$TERMUX_COMMIT
built-at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
source=https://github.com/termux/termux-packages
EOF

echo
echo "READY:"
echo "  $FINAL"
echo "  $FINAL.sha256"
echo "  $OUTPUT_DIR/build-info.txt"
