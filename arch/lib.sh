#!/usr/bin/env bash
# Ultracraft on Arch Linux: the looking-up both arch scripts do (where Steam is, where ULTRAKILL is, where its
# Proton prefix and %TEMP% are, which JDK 21 to build and run with, and how to reach ULTRAKILL's half of Ultracraft).
#
# Sourced, never run on its own. Nothing here writes anything: each script says what it changes.

# shellcheck shell=bash

UC_APP_ID=1229490
UC_FLATPAK_ID=com.valvesoftware.Steam
UC_PORT=27110 # UltraBridge's port for instance 1 (27110 + N - 1 for the others)

if [[ -t 2 ]]; then
	UC_B=$'\033[1m' UC_G=$'\033[32m' UC_Y=$'\033[33m' UC_R=$'\033[31m' UC_0=$'\033[0m'
else
	UC_B='' UC_G='' UC_Y='' UC_R='' UC_0=''
fi

uc_head() { printf '\n%s==> %s%s\n' "$UC_B" "$*" "$UC_0"; }
uc_ok() { printf '  %s✓%s %s\n' "$UC_G" "$UC_0" "$*"; }
uc_info() { printf '    %s\n' "$*"; }
uc_step() { printf '  %s·%s %s\n' "$UC_B" "$UC_0" "$*"; }
uc_warn() { printf '  %s!%s %s\n' "$UC_Y" "$UC_0" "$*" >&2; }
uc_die() {
	printf '\n%serror:%s %s\n' "$UC_R" "$UC_0" "$*" >&2
	exit 1
}

uc_repo_root() { cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd; }

# ---------------------------------------------------------------- Steam, ULTRAKILL, its prefix

# Every place Steam can be, most likely first (STEAM_DIR overrides everything).
uc_steam_dirs() {
	[[ -n ${STEAM_DIR:-} ]] && printf '%s\n' "$STEAM_DIR"
	[[ -n ${STEAM_COMPAT_CLIENT_INSTALL_PATH:-} ]] && printf '%s\n' "$STEAM_COMPAT_CLIENT_INSTALL_PATH"
	printf '%s\n' \
		"$HOME/.steam/steam" \
		"$HOME/.steam/root" \
		"$HOME/.local/share/Steam" \
		"$HOME/.steam/debian-installation" \
		"$HOME/.var/app/$UC_FLATPAK_ID/data/Steam"
}

# The first Steam folder that really is one.
uc_find_steam() {
	local d
	while read -r d; do
		[[ -d $d/steamapps ]] && {
			printf '%s\n' "$d"
			return 0
		}
	done < <(uc_steam_dirs)
	return 1
}

# Every library folder of a Steam install (Steam's own first): steamapps/libraryfolders.vdf lists the others.
uc_steam_libraries() {
	local steam=$1 d
	printf '%s\n' "$steam"
	[[ -f $steam/steamapps/libraryfolders.vdf ]] || return 0
	while read -r d; do
		[[ -n $d && $d != "$steam" ]] && printf '%s\n' "${d//\\\\/\/}"
	done < <(sed -n 's/^[[:space:]]*"path"[[:space:]]*"\([^"]*\)".*/\1/p' "$steam/steamapps/libraryfolders.vdf")
}

# "installdir" from an appmanifest (ULTRAKILL's own folder name inside steamapps/common).
uc_installdir() {
	[[ -f $1 ]] || return 1
	sed -n 's/^[[:space:]]*"installdir"[[:space:]]*"\([^"]*\)".*/\1/p' "$1" | head -1
}

# ULTRAKILL's folder: every library of every Steam, then the usual places. The Windows build (what Proton runs).
uc_find_ultrakill() {
	local steam lib apps name candidate
	while read -r steam; do
		while read -r lib; do
			apps=$lib/steamapps
			name=$(uc_installdir "$apps/appmanifest_$UC_APP_ID.acf" || printf 'ULTRAKILL')
			for candidate in "${name:-ULTRAKILL}" ULTRAKILL; do
				[[ -f $apps/common/$candidate/ULTRAKILL.exe ]] && {
					printf '%s\n' "$apps/common/$candidate"
					return 0
				}
			done
		done < <(uc_steam_libraries "$steam")
	done < <(uc_steam_dirs)
	return 1
}

# The Proton prefix ULTRAKILL runs in (<library>/steamapps/compatdata/1229490/pfx): its C: and its %TEMP% are there.
uc_uk_prefix() {
	local uk=$1 lib
	lib=$(dirname "$(dirname "$uk")") # …/steamapps/common/ULTRAKILL -> …/steamapps
	[[ -d $lib/compatdata/$UC_APP_ID/pfx ]] && printf '%s\n' "$lib/compatdata/$UC_APP_ID/pfx"
}

# A Windows path as it is on this side of the prefix (C:\users\me → <prefix>/drive_c/users/me, Z:\tmp → /tmp).
uc_wine_path() {
	local pfx=$1 win=$2 drive rest
	win=${win//\\\\/\\}
	if [[ $win =~ ^([A-Za-z]):[\\/](.*)$ ]]; then
		drive=${BASH_REMATCH[1],,}
		rest=${BASH_REMATCH[2]//\\//}
		if [[ $drive == c ]]; then
			printf '%s\n' "$pfx/drive_c/$rest"
		elif [[ -e $pfx/dosdevices/$drive: ]]; then
			printf '%s\n' "$pfx/dosdevices/$drive:/$rest"
		else
			return 1
		fi
		return 0
	fi
	return 1
}

# Where ULTRAKILL's %TEMP% is on this side: Wine's own record, then the usual place. What the mod works out by
# itself (UkPaths); this is the same answer, for the scripts to look at while setting things up.
uc_uk_temp_dir() {
	local pfx=$1 win user d
	win=$(awk '/^\[Environment\]/{e=1;next} e&&/^\[/{exit} e&&/^"(TEMP|TMP)"/{sub(/^"[^"]*"="/,"");sub(/"$/,"");print;exit}' "$pfx/user.reg" 2>/dev/null)
	if [[ -n $win ]]; then
		d=$(uc_wine_path "$pfx" "$win") && {
			printf '%s\n' "$d"
			return 0
		}
	fi
	for user in "$pfx"/drive_c/users/*; do
		[[ -d $user/AppData/Local/Temp ]] && {
			printf '%s\n' "$user/AppData/Local/Temp"
			return 0
		}
	done
	[[ -d $pfx/drive_c/windows/temp ]] && {
		printf '%s\n' "$pfx/drive_c/windows/temp"
		return 0
	}
	return 1
}

# Does ULTRAKILL's folder have BepInEx 5 in it (the same two files the mod looks for)?
uc_has_bepinex() {
	local uk=$1
	[[ -f $uk/BepInEx/core/BepInEx.dll && -f $uk/winhttp.dll ]]
}

# Is Wine's winhttp override in place (native first, or BepInEx's winhttp.dll in the game folder is passed over)?
uc_has_winhttp_override() {
	local pfx=$1
	[[ -f $pfx/user.reg ]] && awk '/^\[Software\\\\Wine\\\\DllOverrides\]/{f=1;next} f&&/^\[/{exit} f&&/^"winhttp"/{found=1} END{exit !found}' "$pfx/user.reg"
}

# ---------------------------------------------------------------- the running games

uc_uk_running() { pgrep -f -i 'ultrakill\.exe' >/dev/null 2>&1; }

uc_steam_running() { pgrep -x steam >/dev/null 2>&1 || pgrep -f 'steamwebhelper' >/dev/null 2>&1; }

# Is UltraBridge there (it listens on 127.0.0.1:27110, + instance - 1)?
uc_port_open() { timeout 1 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1" 2>/dev/null; }

# Ask ULTRAKILL's half to quit the game: the plugin quits it at once (Minecraft's own way of closing it).
uc_uk_quit() {
	timeout 5 bash -c "exec 3<>/dev/tcp/127.0.0.1/$1 && printf 'QUIT\n' >&3" 2>/dev/null
}

# Ask ULTRAKILL to start through Steam, the way Minecraft's own launcher does (Steam applies the game's launch
# options, and Proton is used on Linux). Sets UC_STEAM_CMD.
uc_steam_cmd() {
	local uk=${1:-} c
	if [[ -n $uk && $uk == "$HOME/.var/app/$UC_FLATPAK_ID/"* ]] && command -v flatpak >/dev/null 2>&1; then
		UC_STEAM_CMD=(flatpak run "$UC_FLATPAK_ID")
		return 0
	fi
	for c in /usr/bin/steam /usr/games/steam "$HOME/.local/share/Steam/steam.sh" "$HOME/.steam/steam/steam.sh"; do
		[[ -f $c ]] && {
			UC_STEAM_CMD=("$c")
			return 0
		}
	done
	if c=$(command -v steam); then
		UC_STEAM_CMD=("$c")
		return 0
	fi
	if command -v flatpak >/dev/null 2>&1 && [[ -d $HOME/.var/app/$UC_FLATPAK_ID ]]; then
		UC_STEAM_CMD=(flatpak run "$UC_FLATPAK_ID")
		return 0
	fi
	return 1
}

# ---------------------------------------------------------------- the toolchain

# The JDK 21 to build with (Arch's jdk21-openjdk, or any JAVA_HOME that is one).
uc_find_jdk21() {
	local c
	for c in "${JAVA_HOME:-}" /usr/lib/jvm/java-21-openjdk /usr/lib/jvm/java-21 /usr/lib/jvm/temurin-21 /opt/java/openjdk /usr/lib/jvm/default; do
		[[ -n $c && -x $c/bin/java ]] || continue
		"$c/bin/java" -version 2>&1 | head -1 | grep -q '"21' && {
			printf '%s\n' "$c"
			return 0
		}
	done
	if c=$(command -v java) && "$c" -version 2>&1 | head -1 | grep -q '"21'; then
		cd "$(dirname "$(readlink -f "$c")")/.." && pwd
		return 0
	fi
	return 1
}

# Is this an Arch (or Arch-based) system?
uc_is_arch() {
	[[ -f /etc/os-release ]] && grep -qE '^(ID|ID_LIKE)=.*(arch|cachyos|endeavouros|manjaro|garuda|artix)' /etc/os-release
}

# Is the current process running inside a Flatpak sandbox?
uc_is_flatpak() {
	[[ -n ${FLATPAK_SANDBOX_NAME:-} ]] || [[ -d /run/.flatpak-info ]] || {
		# Check if we're running as a Flatpak child (parent has FLATPAK_SANDBOX_NAME)
		[[ -f /proc/1/environ ]] && grep -q 'FLATPAK_SANDBOX_NAME' /proc/1/environ 2>/dev/null
	}
}

# [pacman -S ...] for what is missing, if the player says yes (or --yes was given).
uc_install_packages() {
	local pkgs=("$@") missing=()
	local p
	for p in "${pkgs[@]}"; do
		pacman -Qq "$p" >/dev/null 2>&1 || missing+=("$p")
	done
	[[ ${#missing[@]} -eq 0 ]] && return 0
	if [[ ${UC_YES:-0} != 1 ]]; then
		printf 'Install %s with pacman? [Y/n] ' "${missing[*]}" >&2
		read -r answer
		[[ $answer =~ ^[Nn] ]] && return 1
	fi
	uc_step "sudo pacman -S --needed ${missing[*]}"
	sudo pacman -S --needed "${missing[@]}"
}
