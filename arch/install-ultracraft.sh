#!/usr/bin/env bash
# Ultracraft on Arch Linux: one script sets the whole thing up.
#
#   arch/install-ultracraft.sh [--ukdir PATH] [--steam PATH] [--dev|--prism|--both]
#                              [--with-essential] [--no-build] [--no-deps] [--yes] [--check]
#
# What it does, in order (nothing outside this repository and ULTRAKILL's own folders is touched):
#   1. finds Steam, ULTRAKILL and ULTRAKILL's Proton prefix;
#   2. checks for JDK 21, the .NET SDK and the small tools it needs, and offers to install them with pacman;
#   3. gives ULTRAKILL BepInEx 5 if it has none, and makes sure Wine loads it (the winhttp override in the prefix);
#   4. builds UltraBridge (the plugin inside ULTRAKILL) and the Fabric mod, and puts the plugin in
#      BepInEx/plugins/UltraBridge (where the mod would put it from Windows: the mod keeps it up to date after this);
#   5. --dev (the default): writes fabric/run/config/ultracraft.properties, so the dev client knows where ULTRAKILL is;
#      --prism: creates or updates a PrismLauncher instance "Ultracraft" (1.21.11 + Fabric) with the mod, Fabric API
#      and Sodium in its mods folder (Essential too with --with-essential).
#
# Play with:  arch/play-ultracraft.sh --dev    or    arch/play-ultracraft.sh --prism
#
# See arch/README.md for what is Arch-specific about this port (Proton's %TEMP%, Steam's folders).

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=arch/lib.sh
source "$ROOT/arch/lib.sh"

UKDIR=''
STEAM_DIR_OPT=''
ENTRY=dev
WITH_ESSENTIAL=0
BUILD=1
DEPS=1
UC_YES=0
CHECK=0

usage() {
	sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'
	exit 0
}

while (($#)); do
	case $1 in
	--ukdir)
		UKDIR=${2:?--ukdir needs a path}
		shift 2
		;;
	--steam)
		STEAM_DIR_OPT=${2:?--steam needs a path}
		shift 2
		;;
	--dev) ENTRY=dev; shift ;;
	--prism) ENTRY=prism; shift ;;
	--both) ENTRY=both; shift ;;
	--with-essential) WITH_ESSENTIAL=1; shift ;;
	--no-build) BUILD=0; shift ;;
	--no-deps) DEPS=0; shift ;;
	--yes | -y) UC_YES=1; shift ;;
	--check) CHECK=1; shift ;;
	-h | --help | help) usage ;;
	*) uc_die "unknown option: $1 (try --help)" ;;
	esac
done

[[ $ENTRY == both ]] && ENTRY='dev prism'

# ---------------------------------------------------------------- 1. Steam, ULTRAKILL, its prefix

uc_head 'Looking for Steam and ULTRAKILL'
[[ -n $STEAM_DIR_OPT ]] && export STEAM_DIR=$STEAM_DIR_OPT

if [[ -n $UKDIR ]]; then
	UK=${UKDIR%/}
	[[ -f $UK/ULTRAKILL.exe ]] || uc_die "$UK has no ULTRAKILL.exe in it: --ukdir wants ULTRAKILL's own folder"
	uc_ok "ULTRAKILL (--ukdir): $UK"
else
	UK=$(uc_find_ultrakill) || uc_die 'ULTRAKILL not found through Steam: install it, run it once, or pass --ukdir PATH'
	uc_ok "ULTRAKILL: $UK"
fi

STEAM=$(uc_find_steam || true)
[[ -n $STEAM ]] && uc_ok "Steam: $STEAM" || uc_warn 'Steam not found: launch options and Proton were not checked'

PFX=$(uc_uk_prefix "$UK" || true)
if [[ -n $PFX ]]; then
	uc_ok "Proton prefix: $PFX"
	TMPD=$(uc_uk_temp_dir "$PFX" || true)
	[[ -n ${TMPD:-} ]] && uc_info "ULTRAKILL's %TEMP% here: $TMPD (the mod finds this by itself)"
else
	uc_warn "no Proton prefix next to $UK: run ULTRAKILL once through Steam (it is the Windows build, played with Proton)"
fi

if uc_has_bepinex "$UK"; then
	uc_ok 'BepInEx 5 is already in the game folder (left alone)'
else
	uc_info 'BepInEx 5 is missing: this script adds it (BepInEx 5.4.23.5 x64, as the mod itself bundles)'
fi

if [[ -n $PFX ]]; then
	if uc_has_winhttp_override "$PFX"; then
		uc_ok 'Wine loads the game folder’s winhttp (BepInEx): the override is in place'
	else
		uc_warn "Wine's winhttp override is missing: BepInEx would be skipped when ULTRAKILL starts"
	fi
fi

if ((CHECK)); then
	uc_head 'Everything the scripts look at'
	uc_info "repository:    $ROOT"
	uc_info "Steam:         ${STEAM:-not found}"
	uc_info "ULTRAKILL:     $UK"
	uc_info "prefix:        ${PFX:-not found}"
	uc_info "ULTRAKILL temp: ${TMPD:-not found}"
	uc_info "BepInEx:       $(uc_has_bepinex "$UK" && echo present || echo missing)"
	if [[ -n $PFX ]] && uc_has_winhttp_override "$PFX"; then uc_info 'winhttp:       overridden'; else uc_info 'winhttp:       not overridden'; fi
	uc_info "JDK 21:        $(uc_find_jdk21 || echo missing)"
	uc_info "dotnet:        $(command -v dotnet || echo missing)"
	uc_info "Steam command: $(uc_steam_cmd "$UK" && printf '%s' "${UC_STEAM_CMD[*]}" || echo missing)"
	uc_info "running now:   $(uc_uk_running && echo 'ULTRAKILL' || true) $(uc_steam_running && echo 'Steam' || true)"
	exit 0
fi

# ---------------------------------------------------------------- 2. what is missing from the machine

if ((DEPS)); then
	uc_head 'The tools this needs'
	missing=()
	PACKAGES=()
	uc_find_jdk21 >/dev/null || {
		missing+=(jdk21-openjdk)
		PACKAGES+=(jdk21-openjdk)
	}
	command -v dotnet >/dev/null 2>&1 || {
		missing+=(dotnet-sdk)
		PACKAGES+=(dotnet-sdk)
	}
	command -v unzip >/dev/null 2>&1 || {
		missing+=(unzip)
		PACKAGES+=(unzip)
	}
	command -v curl >/dev/null 2>&1 || {
		missing+=(curl)
		PACKAGES+=(curl)
	}
	if [[ " $ENTRY " == *' prism '* ]]; then
		command -v python3 >/dev/null 2>&1 || uc_warn 'no python3: the Prism instance’s mods will be fetched with curl instead'
	fi
	if ((${#PACKAGES[@]})); then
		if uc_is_arch && ((DEPS)); then
			uc_install_packages "${PACKAGES[@]}" || uc_die "missing: ${missing[*]} — install them and run this again"
		else
			uc_die "missing: ${missing[*]} — install them and run this again"
		fi
	else
		uc_ok 'JDK 21, the .NET SDK, unzip and curl are all here'
	fi
fi

JDK21=$(uc_find_jdk21) || uc_die 'no JDK 21 found: install jdk21-openjdk (the mod builds and plays with Java 21)'
command -v dotnet >/dev/null 2>&1 || uc_die 'no dotnet found: install dotnet-sdk (UltraBridge is a .NET Framework plugin)'
uc_ok "JDK 21: $JDK21"

# ---------------------------------------------------------------- 3. BepInEx and Wine's winhttp

if [[ -n $PFX ]] && ! uc_has_winhttp_override "$PFX"; then
	uc_head 'Telling Wine to load BepInEx'
	if uc_steam_running || uc_uk_running; then
		uc_warn 'Steam or ULTRAKILL is running: Wine rewrites its registry on exit, so this has to wait'
		uc_info "Close both and run this again, or set it by hand: Steam → ULTRAKILL → Properties → Launch Options → WINEDLLOVERRIDES=\"winhttp=n,b\" %command%"
	else
		cp "$PFX/user.reg" "$PFX/user.reg.ultracraft-backup"
		if grep -q '^\[Software\\\\Wine\\\\DllOverrides\]' "$PFX/user.reg"; then
			awk '/^\[Software\\\\Wine\\\\DllOverrides\]/{print; print "#time=0"; print "\"winhttp\"=\"native,builtin\""; next} {print}' \
				"$PFX/user.reg" >"$PFX/user.reg.ultracraft-new"
		else
			cat "$PFX/user.reg" >"$PFX/user.reg.ultracraft-new"
			printf '\n[Software\\\\Wine\\\\DllOverrides] 0\n#time=0\n"winhttp"="native,builtin"\n' >>"$PFX/user.reg.ultracraft-new"
		fi
		mv "$PFX/user.reg.ultracraft-new" "$PFX/user.reg"
		uc_ok "winhttp override added (backup: $PFX/user.reg.ultracraft-backup)"
	fi
fi

BEPINEX_CACHE="$ROOT/ultrabridge/bepinex"
if ! uc_has_bepinex "$UK" && [[ ! -f $BEPINEX_CACHE/BepInEx/core/BepInEx.dll ]]; then
	uc_head 'Fetching BepInEx 5 (the mod loader UltraBridge runs in)'
	url='https://github.com/BepInEx/BepInEx/releases/download/v5.4.23.5/BepInEx_win_x64_5.4.23.5.zip'
	zip=$(mktemp -d)/bepinex.zip
	curl -fL --retry 2 -o "$zip" "$url" || uc_die "couldn't download BepInEx from $url"
	mkdir -p "$BEPINEX_CACHE"
	unzip -q -o "$zip" -d "$BEPINEX_CACHE" || uc_die "couldn't unpack $zip"
	rm -f "$zip"
	[[ -f $BEPINEX_CACHE/BepInEx/core/BepInEx.dll ]] || uc_die 'the BepInEx download had no BepInEx/core/BepInEx.dll in it'
	uc_ok "BepInEx 5.4.23.5 in $BEPINEX_CACHE"
fi

# ---------------------------------------------------------------- 4. building both halves

PLUGIN="$ROOT/ultrabridge/bin/Release/net472/UltraBridge.dll"

if ((BUILD)); then
	uc_head 'Building UltraBridge (ULTRAKILL’s half)'
	tmp=$(mktemp)
	if ! dotnet build -c Release "$ROOT/ultrabridge/UltraBridge.csproj" -p:GameDir="$UK" >"$tmp" 2>&1; then
		tail -30 "$tmp"
		rm -f "$tmp"
		uc_die 'the plugin build failed (see above)'
	fi
	rm -f "$tmp"
	uc_ok "plugin built and put in $UK/BepInEx/plugins/UltraBridge"

	uc_head 'Building the Fabric mod (Minecraft’s half)'
	uc_info 'first build downloads Gradle, Minecraft 1.21.11 and Fabric: it takes a while'
	tmp=$(mktemp)
	if ! (cd "$ROOT/fabric" && JAVA_HOME="$JDK21" PATH="$JDK21/bin:$PATH" bash ./gradlew --console=plain build -PukDir="$UK" >"$tmp" 2>&1); then
		tail -30 "$tmp"
		rm -f "$tmp"
		uc_die 'the mod build failed (see above)'
	fi
	rm -f "$tmp"
	uc_ok 'mod built'
else
	[[ -f $PLUGIN ]] || uc_die "--no-build, but $PLUGIN isn't there yet: build once without --no-build"
	uc_step 'skipping both builds (--no-build)'
fi

# The plugin in place, with the marker the mod writes (the build already copied it there; this covers --no-build, and
# leaves a plugin that isn't one Ultracraft put there alone, as the mod itself does)
PLUGIN_DIR="$UK/BepInEx/plugins/UltraBridge"
mkdir -p "$PLUGIN_DIR"
want=$(sha256sum "$PLUGIN" | cut -d' ' -f1)
have=$(sha256sum "$PLUGIN_DIR/UltraBridge.dll" 2>/dev/null | cut -d' ' -f1 || true)
if [[ -n $have && -f $PLUGIN_DIR/installed-by-ultracraft.txt && $(cat "$PLUGIN_DIR/installed-by-ultracraft.txt") != "$have" ]]; then
	uc_warn "$PLUGIN_DIR/UltraBridge.dll isn't one Ultracraft installed: left as it is (delete it to install this one)"
elif [[ $have == "$want" ]]; then
	uc_ok 'the plugin in ULTRAKILL is the one this build makes'
else
	cp -f "$PLUGIN" "$PLUGIN_DIR/UltraBridge.dll"
	uc_ok "plugin installed in $PLUGIN_DIR"
fi
printf '%s' "$want" >"$PLUGIN_DIR/installed-by-ultracraft.txt"

# The mod's jar: fabric/build/<minecraft>/libs/ultracraft-<version>+<minecraft>.jar
MCV=$(sed -n 's/^minecraft_version=//p' "$ROOT/fabric/versions/1.21.11.properties")
JAR=$(ls -t "$ROOT/fabric/build/$MCV/libs/ultracraft-"*.jar 2>/dev/null | head -1 || true)
[[ -n $JAR ]] || uc_die "no built jar under $ROOT/fabric/build/$MCV/libs: run without --no-build"
uc_ok "mod jar: ${JAR#"$ROOT/"}"

# BepInEx into the game folder, only what isn't there (the same files the jar carries, and what the mod would do)
if ! uc_has_bepinex "$UK"; then
	uc_head 'Giving ULTRAKILL BepInEx'
	(cd "$BEPINEX_CACHE" && find . -type f -print0 | while IFS= read -r -d '' f; do
		mkdir -p "$UK/$(dirname "$f")"
		[[ -e $UK/$f ]] || cp "$f" "$UK/$f"
	done)
	mkdir -p "$UK/BepInEx/plugins"
	uc_ok 'BepInEx 5 in the game folder (nothing that was already there was overwritten)'
fi

# ---------------------------------------------------------------- 5. where Minecraft loads it from

if [[ " $ENTRY " == *' dev '* ]]; then
	uc_head 'The dev client (Loom, like Play-Ultracraft.ps1 does)'
	cfg="$ROOT/fabric/run/config/ultracraft.properties"
	mkdir -p "$(dirname "$cfg")"
	if [[ -f $cfg ]]; then
		uc_ok 'fabric/run/config/ultracraft.properties is already there (left as it is)'
		uc_info "ULTRAKILL's folder is found by itself on Linux; the file's ultrakillDir overrides it when needed"
	else
		cat >"$cfg" <<EOF
# Written by arch/install-ultracraft.sh. The mod's own settings are added to this file as it runs.
ultrakillDir=$UK
setupUltrakill=true
EOF
		uc_ok 'fabric/run/config/ultracraft.properties written'
	fi
fi

if [[ " $ENTRY " == *' prism '* ]]; then
	uc_head 'The PrismLauncher instance'
	PRISM=''
	for candidate in "${PRISM_DIR:-}" "$HOME/.local/share/PrismLauncher" "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"; do
		[[ -n $candidate && -f $candidate/accounts.json ]] && {
			PRISM=$candidate
			break
		}
	done
	if [[ -z $PRISM ]]; then
		for candidate in "$HOME/.local/share/PrismLauncher" "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"; do
			[[ -d $candidate/instances ]] && {
				PRISM=$candidate
				break
			}
	done
	fi
	[[ -n $PRISM ]] || uc_die 'no PrismLauncher found: install it (or use --dev), or point PRISM_DIR at its data folder'

	# Check if Prism is running as Flatpak
	if [[ $PRISM == "$HOME/.var/app/org.prismlauncher.PrismLauncher/"* ]]; then
		uc_warn 'Prism Launcher is installed as a Flatpak'
		uc_info 'The Prism Flatpak has limited filesystem access by default'
		uc_info 'If ULTRAKILL is installed via Steam Flatpak or in a non-standard location,'
		uc_info 'the mod may not be able to find it. Consider:'
		uc_info '  1. flatpak override --filesystem=~/.local/share/Steam org.prismlauncher.PrismLauncher'
		uc_info '  2. Or install Prism natively (not as Flatpak) for full filesystem access'
	fi

	# Write the config so the mod knows where ULTRAKILL is (the mod writes the rest on first start)
	if [[ -n $UK ]]; then
		CFG="$PRISM/instances/Ultracraft/minecraft/config/ultracraft.properties"
		mkdir -p "$(dirname "$CFG")"
		# Normalize the path: resolve symlinks, remove trailing slash
		UK_NORM=''
		if command -v realpath >/dev/null 2>&1; then
			UK_NORM=$(realpath "$UK" 2>/dev/null || echo "")
		fi
		if [[ -z $UK_NORM ]] && cd "$UK" 2>/dev/null; then
			UK_NORM=$(pwd -P)
			cd - >/dev/null
		fi
		[[ -z $UK_NORM ]] && UK_NORM=$UK
		if [[ -f $CFG ]]; then
			if grep -q '^ultrakillDir=' "$CFG" 2>/dev/null; then
				uc_step "ultrakillDir already in $CFG (left as it is)"
			else
				printf '\nultrakillDir=%s\n' "$UK_NORM" >>"$CFG"
				uc_ok "ultrakillDir=$UK_NORM (appended to $CFG)"
			fi
		else
			printf '# Ultracraft config (minimal: the mod writes the rest)\nultrakillDir=%s\n' "$UK_NORM" >"$CFG"
			uc_ok "ultrakillDir=$UK_NORM (written to $CFG)"
		fi
	fi

	INST="$PRISM/instances/Ultracraft"
	MODS="$INST/minecraft/mods"
	mkdir -p "$MODS"
	if [[ -f $INST/instance.cfg ]]; then
		uc_ok "instance \"Ultracraft\" is already there: $INST (mods refreshed)"
	else
		cat >"$INST/instance.cfg" <<EOF
[General]
ConfigVersion=1.3
InstanceType=OneSix
name=Ultracraft
iconKey=grass
ManagedPack=false
AutomaticJava=false
OverrideJavaLocation=true
JavaPath=$JDK21/bin/java
MinMemAlloc=1024
MaxMemAlloc=4096
MinecraftWinWidth=1280
MinecraftWinHeight=720
EOF
		uc_ok "instance \"Ultracraft\" created: $INST"
	fi
	cat >"$INST/mmc-pack.json" <<EOF
{
    "formatVersion": 1,
    "components": [
        {"uid": "net.minecraft", "version": "$MCV", "important": true},
        {"uid": "net.fabricmc.intermediary", "version": "$MCV", "dependencyOnly": true},
        {"uid": "net.fabricmc.fabric-loader", "version": "$(sed -n 's/^loader_version=//p' "$ROOT/fabric/gradle.properties")"}
    ]
}
EOF
	rm -f "$MODS"/ultracraft-*.jar
	cp "$JAR" "$MODS/"
	uc_ok "Ultracraft in the instance's mods ($(basename "$JAR"))"

	# Fabric API and Sodium (and Essential, on request): the versions packs/pack.json pins, from Modrinth
	PACK="$ROOT/packs/pack.json"
	if command -v python3 >/dev/null 2>&1; then
		python3 - "$PACK" "$MODS" "$WITH_ESSENTIAL" <<'PY'
import json, os, sys, urllib.request
pack = json.load(open(sys.argv[1], encoding="utf-8"))
dest, essential = sys.argv[2], sys.argv[3] == "1"
want = ["Fabric API", "Sodium"] + (["Essential"] if essential else [])
for mod in pack["mods"]:
    if mod["name"] not in want or "modrinthVersion" not in mod:
        continue
    req = urllib.request.Request(f"https://api.modrinth.com/v2/version/{mod['modrinthVersion']}", headers={"User-Agent": "ultracraft-arch"})
    with urllib.request.urlopen(req, timeout=30) as r:
        v = json.load(r)
    f = next((f for f in v["files"] if f.get("primary")), v["files"][0])
    out = os.path.join(dest, f["filename"])
    if os.path.exists(out):
        print(f"  = {f['filename']} (already there)")
        continue
    with urllib.request.urlopen(f["url"], timeout=60) as r, open(out, "wb") as w:
        w.write(r.read())
    print(f"  + {f['filename']}")
PY
	else
		for name in 'Fabric API' 'Sodium'; do
			vid=$(sed -n "s/.*\"name\": \"$name\".*\"modrinthVersion\": \"\([^\"]*\)\".*/\1/p" "$PACK" | head -1)
			[[ -n $vid ]] || continue
			api="https://api.modrinth.com/v2/version/$vid"
			version=$(curl -fsL -H 'User-Agent: ultracraft-arch' "$api" || true)
			url=$(printf '%s' "$version" | grep -o '"url":"[^"]*"' | head -1 | cut -d'"' -f4)
			file=$(printf '%s' "$version" | grep -o '"filename":"[^"]*"' | head -1 | cut -d'"' -f4)
			if [[ -z $url || -z $file ]]; then
				uc_warn "couldn't look up $name on Modrinth: add it to $MODS by hand"
			elif [[ -e $MODS/$file ]]; then
				uc_info "$file is already there"
			else
				curl -fsL -o "$MODS/$file" "$url" && uc_ok "$file"
			fi
		done
	fi
	[[ $WITH_ESSENTIAL == 1 ]] && uc_info 'Essential is in: host worlds from its menu, as on Windows'
fi

# ---------------------------------------------------------------- done

uc_head 'Done'
uc_info "ULTRAKILL:  $UK"
uc_info "plugin:     $UK/BepInEx/plugins/UltraBridge/UltraBridge.dll"
uc_info "mod jar:    ${JAR#"$ROOT/"}"
[[ " $ENTRY " == *' dev '* ]] && uc_info "dev client: arch/play-ultracraft.sh --dev"
[[ " $ENTRY " == *' prism '* ]] && uc_info "Prism:      arch/play-ultracraft.sh --prism   (instance \"Ultracraft\")"
printf '\n%sPlay:%s arch/play-ultracraft.sh %s\n\n' "$UC_B" "$UC_0" "$([[ $ENTRY == prism ]] && echo --prism || echo --dev)"
