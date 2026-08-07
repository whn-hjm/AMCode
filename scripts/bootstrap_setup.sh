#!/system/bin/sh
# ─────────────────────────────────────────────────────
# VSBoxed Bootstrap Setup Script
# Runs INSIDE the app's Termux environment after extraction.
# This script is for reference; actual execution is done
# programmatically via TermuxManager.kt
# ─────────────────────────────────────────────────────

set -e

echo "=== VSBoxed Bootstrap Setup ==="

# Detect architecture
ARCH=$(uname -m)
case $ARCH in
    aarch64) BOOTSTRAP_ARCH="aarch64" ;;
    armv7l)  BOOTSTRAP_ARCH="arm" ;;
    i686)    BOOTSTRAP_ARCH="i686" ;;
    x86_64)  BOOTSTRAP_ARCH="x86_64" ;;
    *) echo "Unsupported architecture: $ARCH"; exit 1 ;;
esac

PREFIX="/data/data/com.devbox/files/usr"
BOOTSTRAP_URL="https://github.com/termux/termux-packages/releases/download/bootstrap-2024.07.15-r1%2Bapt-android-7/bootstrap-${BOOTSTRAP_ARCH}.zip"

echo "[1/6] Creating directory structure..."
mkdir -p $PREFIX/bin $PREFIX/bin/applets $PREFIX/etc/apt $PREFIX/lib
mkdir -p $PREFIX/tmp $PREFIX/var $PREFIX/home $PREFIX/share
export HOME="/data/data/com.devbox/files/home"
mkdir -p $HOME

echo "[2/6] Downloading Termux bootstrap..."
cd /data/data/com.devbox/files
curl -L -o bootstrap.zip "$BOOTSTRAP_URL"

echo "[3/6] Extracting bootstrap..."
unzip -o bootstrap.zip
rm bootstrap.zip

echo "[4/6] Patching binary paths..."
# Replace hardcoded 'com.termux' with 'com.devbox' (both 10 chars)
OLD_PATH="/data/data/com.termux/files/usr"
NEW_PATH="/data/data/com.devbox/files/usr"

for DIR in bin bin/applets lib libexec; do
    if [ -d "$PREFIX/$DIR" ]; then
        find "$PREFIX/$DIR" -type f | while read f; do
            if grep -qFl "$OLD_PATH" "$f" 2>/dev/null; then
                sed -i "s|$OLD_PATH|$NEW_PATH|g" "$f" 2>/dev/null || true
            fi
        done
    fi
done

# Also patch text files
find "$PREFIX/etc" "$PREFIX/bin" -type f | while read f; do
    sed -i "s|$OLD_PATH|$NEW_PATH|g" "$f" 2>/dev/null || true
done

echo "[5/6] Configuring apt..."
cat > $PREFIX/etc/apt/sources.list << APTEOF
deb https://packages.termux.dev/apt/termux-main stable main
APTEOF

# Create dpkg status file
mkdir -p $PREFIX/var/lib/dpkg
touch $PREFIX/var/lib/dpkg/status

echo "[6/6] Installing core packages..."
export PATH="$PREFIX/bin:$PREFIX/bin/applets:$PATH"
export LD_LIBRARY_PATH="$PREFIX/lib"

apt update
apt install -y nodejs git python3 make clang binutils openssh

echo "=== Setup Complete ==="
