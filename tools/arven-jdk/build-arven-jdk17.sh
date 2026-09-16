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

echo "[1/7] Cloning Termux packages..."
git clone --depth 1 https://github.com/termux/termux-packages.git "$TERMUX_REPO"

cd "$TERMUX_REPO"

echo "[2/7] Configuring Termux build for Arven's private prefix..."
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

echo "[3/7] Applying Termux CI workaround for AppArmor/fuse-overlayfs SDK bug..."
python3 - <<'PY'
from pathlib import Path

# Work around current termux-packages CI/container bug where the Android SDK
# inside the builder becomes effectively non-writable / malformed while using
# fuse-overlayfs + AppArmor. This mirrors the minimal successful workaround
# documented in upstream issue termux/termux-packages#29118.

toolchain = Path("scripts/build/toolchain/termux_setup_toolchain_29.sh")
text = toolchain.read_text(encoding="utf-8")
old_mount = 'if ! mountpoint -q "${TERMUX_STANDALONE_TOOLCHAIN}"; then'
if old_mount not in text:
    raise SystemExit("Could not find fuse-overlayfs mount block in termux_setup_toolchain_29.sh")
text = text.replace(old_mount, 'if false; then', 1)

needle = '''\t\treturn\n\tfi\n\n\tlocal _NDK_ARCHNAME=$TERMUX_ARCH'''
replacement = '''\t\treturn\n\tfi\n\n\trm -rf "${TERMUX_STANDALONE_TOOLCHAIN}"\n\tcp "$NDK/toolchains/llvm/prebuilt/linux-x86_64" "${TERMUX_STANDALONE_TOOLCHAIN}" -r\n\tcp "$NDK/source.properties" "${TERMUX_STANDALONE_TOOLCHAIN}"\n\n\tlocal _NDK_ARCHNAME=$TERMUX_ARCH'''
if needle not in text:
    raise SystemExit("Could not find toolchain insertion point")
text = text.replace(needle, replacement, 1)
toolchain.write_text(text, encoding="utf-8")

run_docker = Path("scripts/run-docker.sh")
text = run_docker.read_text(encoding="utf-8")
old_sec = 'SEC_OPT=" --security-opt seccomp=$REPOROOT/scripts/profile.json --security-opt apparmor=_custom-termux-package-builder-$CONTAINER_NAME --cap-add CAP_SYS_ADMIN --device /dev/fuse"'
new_sec = 'SEC_OPT=" --security-opt seccomp=$REPOROOT/scripts/profile.json"'
if old_sec not in text:
    raise SystemExit("Could not find AppArmor/fuse SEC_OPT line in scripts/run-docker.sh")
text = text.replace(old_sec, new_sec, 1)

needle = '''if [ -z "$APPARMOR_PARSER" ] || ! $SUDO aa-status --enabled; then'''
pos = text.find(needle)
if pos == -1:
    raise SystemExit("Could not find AppArmor detection block in scripts/run-docker.sh")
# Disable AppArmor profile loading after its detection block, matching the
# upstream workaround. Insert before load_apparmor_profile().
load_fn = '\nload_apparmor_profile() {'
idx = text.find(load_fn, pos)
if idx == -1:
    raise SystemExit("Could not find load_apparmor_profile() in scripts/run-docker.sh")
text = text[:idx] + '\nAPPARMOR_PARSER=""\n' + text[idx:]
run_docker.write_text(text, encoding="utf-8")
PY

echo "[4/7] Fixing upstream bootstrap package name bug (bzip2 -> libbz2)..."
python3 - <<'PY2'
from pathlib import Path
p = Path("scripts/build-bootstraps.sh")
text = p.read_text(encoding="utf-8")
old = 'PACKAGES+=("bzip2")'
new = 'PACKAGES+=("libbz2")'
if old in text:
    text = text.replace(old, new, 1)
elif new not in text:
    raise SystemExit("Could not find expected bzip2/libbz2 bootstrap package entry")
p.write_text(text, encoding="utf-8")
PY2

grep -n 'PACKAGES+=("libbz2")' scripts/build-bootstraps.sh

echo "[5/7] Building Android-10-compatible bootstrap + OpenJDK 17..."
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

echo "[6/7] Validating JDK/runtime contents..."
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

echo "[7/7] Preparing Arven artifact..."
FINAL="$OUTPUT_DIR/arven-jdk17-$ARCH.zip"
cp "$BOOTSTRAP" "$FINAL"

(
    cd "$OUTPUT_DIR"
    sha256sum "$(basename "$FINAL")" > "$(basename "$FINAL").sha256"
)

TERMUX_COMMIT="$(git rev-parse HEAD)"
cat > "$OUTPUT_DIR/build-info.txt" <<EOF2
Arven JDK 17 runtime
package=$ARVEN_PACKAGE
architecture=$ARCH
termux-packages-commit=$TERMUX_COMMIT
built-at=$(date -u +%Y-%m-%dT%H:%M:%SZ)
source=https://github.com/termux/termux-packages
workaround=termux-packages-29118-apparmor-fuse-overlayfs
EOF2

echo
echo "READY:"
echo "  $FINAL"
echo "  $FINAL.sha256"
echo "  $OUTPUT_DIR/build-info.txt"
