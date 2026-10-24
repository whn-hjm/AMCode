#!/data/data/com.devbox/files/usr/bin/bash
# AMCode package-manager repair / setup (idempotent).
#
# Runs on every app launch (TermuxManager.ensurePkgSetup), so an APK installed
# over an existing data directory repairs itself without reinstalling
# code-server. It performs these steps:
#
#   1. trusted.gpg.d: copy the bundled Termux keyring files (plus the TUR key)
#      over the broken absolute symlinks that point at /data/data/com.termux.
#   2. SYMLINKS.txt: create the bootstrap symlinks that ZIP extraction skipped
#      (notably lib/libtermux-exec.so), keeping already working links.
#   3. bin/pkg: fix the hardcoded /data/data/com.devbox/cache/apt path so pkg
#      looks at $PREFIX/var/cache/apt like apt does.
#   4. apt: point Dir::Bin::dpkg at the AMCode dpkg wrapper, so every apt-driven
#      dpkg --unpack rewrites Termux .deb paths to com.devbox.
#   5. TUR source: once the TUR key is available, drop the old trusted=yes
#      workaround and use the signed repository.
#   6. dpkg database: migrate paths recorded by the initial --root bootstrap
#      install from /data/data/com.termux to /data/data/com.devbox, matching
#      where the files were moved and where new packages are installed.
#
# This file is maintained by TermuxManager; do not edit on device.

set -u
export LC_ALL=C

PREFIX="${PREFIX:-/data/data/com.devbox/files/usr}"
PATH="$PREFIX/bin:/system/bin:/system/xbin${PATH:+:$PATH}"
export PREFIX PATH

say() {
    printf '[amcode-pkg] %s\n' "$*"
}

mkdir -p \
    "$PREFIX/etc/apt/trusted.gpg.d" \
    "$PREFIX/etc/apt/sources.list.d" \
    "$PREFIX/etc/apt/apt.conf.d" \
    "$PREFIX/libexec/amcode" \
    "$PREFIX/tmp" \
    "$PREFIX/var/lib/dpkg" \
    "$PREFIX/var/cache/apt/archives" 2>/dev/null || true

# Leftover dpkg-wrapper temp trees (a SIGKILLed .deb rewrite) are never
# reused across runs and can be hundreds of MB; drop them.
find "$PREFIX/tmp" -maxdepth 1 -name 'amcode-deb.*' -exec rm -rf {} + 2>/dev/null || true

# ---------------------------------------------------------------- 1. keyring
key_count=0
for src in "$PREFIX"/share/termux-keyring/*.gpg "$PREFIX"/libexec/amcode/tur.gpg; do
    [ -f "$src" ] || continue
    base="${src##*/}"
    dest="$PREFIX/etc/apt/trusted.gpg.d/$base"
    tmp="$dest.amcode-tmp.$$"
    if cp -f "$src" "$tmp" 2>/dev/null &&
       chmod 644 "$tmp" 2>/dev/null &&
       mv -f "$tmp" "$dest" 2>/dev/null; then
        key_count=$((key_count + 1))
    else
        rm -f "$tmp" 2>/dev/null
        say "WARN could not install key $base"
    fi
done

# Drop broken key symlinks that were not replaced above; apt logs noisy errors
# for unreadable keyring files.
for f in "$PREFIX"/etc/apt/trusted.gpg.d/*.gpg; do
    if [ -L "$f" ] && [ ! -e "$f" ]; then
        rm -f "$f" 2>/dev/null || true
        say "removed broken keyring link ${f##*/}"
    fi
done

if [ -s "$PREFIX/etc/apt/trusted.gpg.d/termux-autobuilds.gpg" ]; then
    say "keyring: $key_count key files installed (main key present)"
else
    say "WARN main key termux-autobuilds.gpg is missing"
fi
if [ -s "$PREFIX/etc/apt/trusted.gpg.d/tur.gpg" ]; then
    say "keyring: TUR key present"
else
    say "WARN TUR key (tur.gpg) is missing"
fi

# --------------------------------------------------------------- 2. symlinks
created=0
kept=0
no_target=0
if [ -f "$PREFIX/SYMLINKS.txt" ]; then
    while IFS= read -r line; do
        case "$line" in
            *"←"*) ;;
            *) continue ;;
        esac
        target="${line%%←*}"
        link="${line#*←}"
        link="${link#./}"
        [ -n "$link" ] && [ -n "$target" ] || continue

        # Keyring symlinks in the bootstrap are absolute com.termux paths.
        case "$target" in
            /data/data/com.termux/*)
                target="/data/data/com.devbox/${target#/data/data/com.termux/}"
                ;;
        esac

        linkpath="$PREFIX/$link"
        linkdir="${linkpath%/*}"

        if [ -L "$linkpath" ]; then
            current="$(readlink "$linkpath" 2>/dev/null || true)"
            case "$current" in
                *com.termux*)
                    # Broken bootstrap link that still points at the old
                    # package name; replace it below.
                    ;;
                *)
                    if [ -e "$linkpath" ]; then
                        kept=$((kept + 1))
                        continue
                    fi
                    ;;
            esac
        elif [ -e "$linkpath" ]; then
            # Never clobber a real file installed by a package. Missing
            # bootstrap links are the ones we need to create.
            kept=$((kept + 1))
            continue
        fi

        case "$target" in
            /*) check="$target" ;;
            *) check="$linkdir/$target" ;;
        esac
        if [ ! -e "$check" ] && [ ! -L "$check" ]; then
            no_target=$((no_target + 1))
            continue
        fi

        mkdir -p "$linkdir" 2>/dev/null || true
        rm -f "$linkpath" 2>/dev/null || true
        if ln -s "$target" "$linkpath" 2>/dev/null; then
            created=$((created + 1))
        else
            no_target=$((no_target + 1))
        fi
    done < "$PREFIX/SYMLINKS.txt"
    say "symlinks: created=$created kept=$kept target-missing=$no_target"
else
    say "WARN SYMLINKS.txt not found; symlink repair skipped"
fi

# ------------------------------------------------------------- 3. pkg script
pkg_patched=0
if [ -f "$PREFIX/bin/pkg" ] &&
   grep -q '/data/data/com\.devbox/cache/apt' "$PREFIX/bin/pkg" 2>/dev/null; then
    if sed -i 's|/data/data/com\.devbox/cache/apt|/data/data/com.devbox/files/usr/var/cache/apt|g' \
        "$PREFIX/bin/pkg" 2>/dev/null; then
        pkg_patched=1
    fi
fi
# A missing .deb cache path makes pkg think its metadata is always stale; the
# patched path matches apt's Dir::Cache ($PREFIX/var/cache/apt).
say "pkg: cache paths $([ "$pkg_patched" -eq 1 ] && echo patched || echo ok)"

# --------------------------------------------------- 4. apt -> dpkg wrapper
wrapper="$PREFIX/libexec/amcode/dpkg"
conf="$PREFIX/etc/apt/apt.conf.d/99-amcode-dpkg"
if [ -x "$wrapper" ]; then
    tmp="$conf.amcode-tmp.$$"
    printf 'Dir::Bin::dpkg "%s";\n' "$wrapper" > "$tmp" 2>/dev/null &&
        chmod 644 "$tmp" 2>/dev/null &&
        mv -f "$tmp" "$conf" 2>/dev/null
    rm -f "$tmp" 2>/dev/null || true
    say "apt: Dir::Bin::dpkg -> $wrapper"
else
    say "WARN dpkg wrapper missing at $wrapper"
fi

# ------------------------------------------------------------- 5. TUR source
tur_list="$PREFIX/etc/apt/sources.list.d/tur.list"
if [ -s "$PREFIX/etc/apt/trusted.gpg.d/tur.gpg" ] && [ -f "$tur_list" ]; then
    if grep -q 'trusted=yes' "$tur_list" 2>/dev/null; then
        if sed -i 's/\[trusted=yes\][[:space:]]*//g' "$tur_list" 2>/dev/null; then
            say "source: TUR repository upgraded from trusted=yes to signed"
        fi
    fi
fi

# The initial bootstrap uses [trusted=yes] as a fallback because the keyring is
# not usable yet. Once the dpkg database exists (i.e. the initial download
# finished) and the main key is installed, prefer real signature verification
# for HTTPS mirrors. HTTP mirrors keep trusted=yes as a last-resort fallback.
main_list="$PREFIX/etc/apt/sources.list"
if [ -s "$PREFIX/etc/apt/trusted.gpg.d/termux-autobuilds.gpg" ] &&
   [ -f "$main_list" ] &&
   grep -q '^Package:' "$PREFIX/var/lib/dpkg/status" 2>/dev/null &&
   grep -q 'trusted=yes' "$main_list" 2>/dev/null; then
    if sed -i '/https:\/\//s/\[trusted=yes\][[:space:]]*//g' "$main_list" 2>/dev/null; then
        say "source: main HTTPS repository upgraded from trusted=yes to signed"
    fi
fi

# ---------------------------------------------------------- 6. dpkg database
# The initial bootstrap install used "dpkg --root=$PREFIX" and then moved
# $PREFIX/data/data/com.termux/files/usr/* up one level. The dpkg database
# still lists those old com.termux paths, so upgrades/removals would look at
# nonexistent files. Rewrite the recorded paths once (idempotent).
db="$PREFIX/var/lib/dpkg"
migrated=0
if [ -d "$db" ]; then
    if [ -f "$db/status" ] &&
       grep -q '/data/data/com\.termux' "$db/status" 2>/dev/null &&
       [ ! -f "$db/status.amcode-bak" ]; then
        cp -p "$db/status" "$db/status.amcode-bak" 2>/dev/null || true
    fi

    for f in "$db/status" "$db/status-old"; do
        [ -f "$f" ] || continue
        if grep -q '/data/data/com\.termux' "$f" 2>/dev/null; then
            if sed -i 's|/data/data/com\.termux|/data/data/com.devbox|g' "$f" 2>/dev/null; then
                migrated=$((migrated + 1))
            fi
        fi
    done

    # .list, conffiles, md5sums and maintainer scripts from the bootstrap
    # install all carry the old prefix. Alternatives too.
    for f in "$db"/info/* "$db"/alternatives/*; do
        [ -f "$f" ] || continue
        if grep -q '/data/data/com\.termux' "$f" 2>/dev/null; then
            if sed -i 's|/data/data/com\.termux|/data/data/com.devbox|g' "$f" 2>/dev/null; then
                migrated=$((migrated + 1))
            fi
        fi
    done
fi
say "dpkg db: migrated_files=$migrated"

# ------------------------------------------------------------- 7. validation
problems=0
for f in \
    "$PREFIX/bin/bash" \
    "$PREFIX/bin/dpkg" \
    "$PREFIX/bin/dpkg-deb" \
    "$PREFIX/libexec/amcode/dpkg" \
    "$PREFIX/lib/libtermux-exec.so" \
    "$PREFIX/etc/apt/trusted.gpg.d/termux-autobuilds.gpg" \
    "$PREFIX/etc/apt/trusted.gpg.d/tur.gpg"; do
    if [ ! -e "$f" ]; then
        say "WARN missing $f"
        problems=$((problems + 1))
    fi
done
if [ -f "$conf" ] && grep -q 'Dir::Bin::dpkg' "$conf" 2>/dev/null; then
    :
else
    say "WARN apt is not configured to use the dpkg wrapper"
    problems=$((problems + 1))
fi

if [ "$problems" -eq 0 ]; then
    say "ready"
else
    say "completed with $problems warning(s)"
fi
exit 0
