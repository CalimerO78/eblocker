#!/bin/sh
# Refresh official Debian metadata without changing /etc/apt or installed packages.
set -eu
export LC_ALL=C
cache=/var/cache/eblocker/systeminfo-debian
umask 022
mkdir -p "$cache/lists/partial"
cat > "$cache/sources.list" <<'EOF'
deb [signed-by=/usr/share/keyrings/debian-archive-keyring.gpg] https://deb.debian.org/debian bookworm main contrib non-free non-free-firmware
deb [signed-by=/usr/share/keyrings/debian-archive-keyring.gpg] https://deb.debian.org/debian bookworm-updates main contrib non-free non-free-firmware
deb [signed-by=/usr/share/keyrings/debian-archive-keyring.gpg] https://security.debian.org/debian-security bookworm-security main contrib non-free non-free-firmware
EOF
apt_opts="-o Dir::Etc::sourcelist=$cache/sources.list -o Dir::Etc::sourceparts=- -o Dir::State::lists=$cache/lists"
# shellcheck disable=SC2086
apt-get $apt_opts -o APT::Get::List-Cleanup=0 update
candidate_file=$(mktemp "$cache/candidates.XXXXXX")
trap 'rm -f "$candidate_file"' EXIT HUP INT TERM
for package in qemu-guest-agent open-vm-tools hyperv-daemons spice-vdagent virtualbox-guest-utils \
               firmware-linux-free firmware-linux-nonfree intel-microcode amd64-microcode; do
    # shellcheck disable=SC2086
    candidate=$(apt-cache $apt_opts policy "$package" | awk '/^[[:space:]]*Candidate:/ {print $2; exit}')
    [ "$candidate" = '(none)' ] && candidate=''
    printf '%s\t%s\n' "$package" "$candidate" >> "$candidate_file"
done
chmod 0644 "$candidate_file"
mv "$candidate_file" "$cache/candidates.tsv"
date -u '+%Y-%m-%dT%H:%M:%SZ' > "$cache/refreshed-at.txt"
