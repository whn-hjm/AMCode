#!/data/data/com.devbox/files/usr/bin/bash
# AMCode dpkg wrapper.
#
# apt invokes `dpkg --unpack ...` without --root, so stock dpkg tries to
# unpack Termux packages to their baked-in prefix ./data/data/com.termux/files/usr
# (relative to /, i.e. /data/data/com.termux/...), which this app cannot write.
#
# This wrapper rewrites every .deb handed to dpkg so that:
#   * archive member paths use data/data/com.devbox/files/usr instead,
#   * file contents (ELF strings / maintainer scripts / conffiles / md5sums)
#     have com.termux replaced by com.devbox (same length),
#   * absolute symlink targets pointing at /data/data/com.termux are rewritten.
# The rewritten archive is then installed by the real dpkg with its default
# (patched) admindir $PREFIX/var/lib/dpkg and without --root, so the dpkg
# database records the same paths that actually exist on disk.
#
# It is installed at $PREFIX/libexec/amcode/dpkg and selected through
# apt.conf.d/99-amcode-dpkg (Dir::Bin::dpkg), so it cannot be clobbered by a
# dpkg package upgrade.
#
# This file is maintained by TermuxManager; do not edit on device.

set -u
export LC_ALL=C
PREFIX="${PREFIX:-/data/data/com.devbox/files/usr}"
PATH="$PREFIX/bin:/system/bin:/system/xbin${PATH:+:$PATH}"
export PREFIX PATH
REAL_DPKG="$PREFIX/bin/dpkg"
DPKG_DEB="$PREFIX/bin/dpkg-deb"

log() {
    printf '[amcode-dpkg] %s\n' "$*" >&2
}

if [ ! -x "$REAL_DPKG" ]; then
    log "real dpkg not found at $REAL_DPKG"
    exit 127
fi

install_mode=0
has_root=0
for arg in "$@"; do
    case "$arg" in
        --install|--unpack|-i|--unpack=*|--recursive|-R)
            install_mode=1
            ;;
        --root|--root=*|--instdir|--instdir=*|--admindir|--admindir=*)
            has_root=1
            ;;
    esac
done

# --root is used by the initial code-server bootstrap install in
# TermuxManager; that path already moves $PREFIX/data/... itself, so do not
# interfere with it. Everything that is not an unpack/install is passed through
# untouched (--configure, --remove, --status, --print-architecture, ...).
if [ "$install_mode" -eq 0 ] || [ "$has_root" -eq 1 ]; then
    exec "$REAL_DPKG" "$@"
fi

rewrite_one_deb() {
    local deb="$1"
    local tmp out
    local link target newtarget

    case "$deb" in
        *.deb) ;;
        *) return 0 ;;
    esac
    [ -f "$deb" ] || return 0

    # Already rewritten (or the rare package without any com.termux string)?
    # dpkg-deb -c lists all archive member names; every Termux package contains
    # the prefix in its member paths. This also makes the wrapper idempotent if
    # apt retries the same archive.
    if ! "$DPKG_DEB" -c "$deb" 2>/dev/null | grep -q 'com\.termux'; then
        return 0
    fi

    tmp="$PREFIX/tmp/amcode-deb.$$.$RANDOM"
    rm -rf "$tmp"
    if ! mkdir -p "$tmp/pkg"; then
        log "cannot create temp dir $tmp"
        return 1
    fi

    if ! "$DPKG_DEB" -R "$deb" "$tmp/pkg" >/dev/null 2>&1; then
        log "failed to extract $(basename "$deb")"
        rm -rf "$tmp"
        return 1
    fi

    # 1. Move the archive's data prefix from com.termux to com.devbox.
    # com.devbox is exactly the same length (10 chars), which is also why the
    # bootstrap binary patching is safe.
    if [ -d "$tmp/pkg/data/data/com.termux" ]; then
        rm -rf "$tmp/pkg/data/data/com.devbox"
        if ! mv "$tmp/pkg/data/data/com.termux" "$tmp/pkg/data/data/com.devbox"; then
            log "failed to rename data prefix in $(basename "$deb")"
            rm -rf "$tmp"
            return 1
        fi
    fi

    # 2. Rewrite absolute symlink targets (e.g. termux-keyring links).
    while IFS= read -r -d '' link; do
        target="$(readlink "$link" 2>/dev/null || true)"
        case "$target" in
            *com.termux*)
                newtarget="${target//com.termux/com.devbox}"
                ln -sfn "$newtarget" "$link" || true
                ;;
        esac
    done < <(find "$tmp/pkg/data" -type l -print0 2>/dev/null)

    # 3. Rewrite text and binary contents (meaningful strings in ELF files,
    # maintainer scripts' shebangs/paths, conffiles, md5sums, ...).
    # GNU sed -i replaces the inode and would drop the executable bit from
    # maintainer scripts and packaged binaries; save and restore the mode.
    local file mode
    while IFS= read -r -d '' file; do
        if grep -q 'com\.termux' "$file" 2>/dev/null; then
            mode="$(stat -c '%a' "$file" 2>/dev/null || echo 644)"
            if ! sed -i 's/com\.termux/com.devbox/g' "$file"; then
                log "failed to patch $(basename "$file") in $(basename "$deb")"
                rm -rf "$tmp"
                return 1
            fi
            chmod "$mode" "$file" 2>/dev/null || true
        fi
    done < <(find "$tmp/pkg/data" "$tmp/pkg/DEBIAN" -type f -print0 2>/dev/null)

    # Termux .debs store maintainer scripts with 0644 (dpkg chmods them at
    # install time), but dpkg-deb --build insists on >=0555. Make them
    # executable in the rebuilt archive.
    for script in preinst postinst prerm postrm config; do
        [ -f "$tmp/pkg/DEBIAN/$script" ] && chmod 755 "$tmp/pkg/DEBIAN/$script" 2>/dev/null
    done

    # 4. Rebuild. -z1 keeps decompression/compression time acceptable on
    # phones while still producing a valid .deb. --root-owner-group keeps the
    # archive metadata owned by root:root like the original Termux package.
    out="$tmp/out.deb"
    if ! "$DPKG_DEB" --root-owner-group -Zxz -z1 -b "$tmp/pkg" "$out" >/dev/null 2>&1; then
        log "failed to rebuild $(basename "$deb")"
        rm -rf "$tmp"
        return 1
    fi

    if ! mv -f "$out" "$deb"; then
        log "failed to replace $deb"
        rm -rf "$tmp"
        return 1
    fi
    rm -rf "$tmp"
    log "rewrote $(basename "$deb")"
    return 0
}

newargs=()
recursive_next=0
for arg in "$@"; do
    if [ "$recursive_next" -eq 1 ]; then
        # apt uses `dpkg --recursive <temp-dir>` for multi-package
        # transactions (e.g. clang plus its dependencies), so rewrite every
        # .deb in that directory before handing it to the real dpkg.
        recursive_next=0
        if [ -d "$arg" ]; then
            while IFS= read -r -d '' deb; do
                if ! rewrite_one_deb "$deb"; then
                    log "aborting install because archive rewrite failed"
                    exit 1
                fi
            done < <(find "$arg" -maxdepth 1 \( -type f -o -type l \) -name '*.deb' -print0 2>/dev/null)
        fi
        newargs+=("$arg")
        continue
    fi
    case "$arg" in
        --recursive|-R)
            recursive_next=1
            newargs+=("$arg")
            ;;
        *.deb)
            if ! rewrite_one_deb "$arg"; then
                log "aborting install because archive rewrite failed"
                exit 1
            fi
            newargs+=("$arg")
            ;;
        *)
            newargs+=("$arg")
            ;;
    esac
done

exec "$REAL_DPKG" "${newargs[@]}"
