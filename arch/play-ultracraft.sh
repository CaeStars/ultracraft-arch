#!/usr/bin/env bash
# Play Ultracraft on Arch Linux: ULTRAKILL (its Windows build, through Steam and Proton) and Minecraft together.
# Closing Minecraft closes the ULTRAKILL it started: what Play-Ultracraft.ps1 does on Windows.
#
#   arch/play-ultracraft.sh [--dev | --prism] [--world NAME] [--instance N] [--ukdir PATH]
#                           [--uk | --no-uk] [--no-wait] [--dry-run]
#
#   --dev        play the mod through the Loom dev client (fabric/run; what arch/install-ultracraft.sh builds).
#                ULTRAKILL is started (and closed) by this script, as on Windows.
#   --prism      play the PrismLauncher instance "Ultracraft". ULTRAKILL is started and closed by the mod itself
#                (Gameplay → Start ULTRAKILL), the way a normal Windows install does it.
#   --world NAME which world the dev client opens (default Ultracraft: it has to exist in the save list)
#   --instance N a second (third…) pair of games on this machine, for co-op with yourself: its own port and
#                shared files (ULTRAKILL gets -ucinstance N, Minecraft -Dultracraft.instance=N)
#   --ukdir PATH ULTRAKILL's folder, when it isn't found through Steam
#   --uk / --no-uk   force starting ULTRAKILL itself, or leave it alone
#   --no-wait    don't wait for ULTRAKILL to be ready before starting Minecraft
#   --dry-run    say what would be run, run nothing

set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
# shellcheck source=arch/lib.sh
source "$ROOT/arch/lib.sh"

ENTRY=dev
WORLD=Ultracraft
INSTANCE=1
UKDIR=''
UKMODE=auto # auto | yes | no
WAIT=1
DRY=0

usage() {
	sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
	exit 0
}

while (($#)); do
	case $1 in
	--dev) ENTRY=dev; shift ;;
	--prism) ENTRY=prism; shift ;;
	--world)
		WORLD=${2:?--world needs a name}
		shift 2
		;;
	--instance)
		INSTANCE=${2:?--instance needs a number}
		shift 2
		;;
	--ukdir)
		UKDIR=${2:?--ukdir needs a path}
		shift 2
		;;
	--uk) UKMODE=yes; shift ;;
	--no-uk) UKMODE=no; shift ;;
	--no-wait) WAIT=0; shift ;;
	--dry-run) DRY=1; shift ;;
	-h | --help | help) usage ;;
	*) uc_die "unknown option: $1 (try --help)" ;;
	esac
done

[[ $INSTANCE =~ ^[0-9]+$ && $INSTANCE -ge 1 ]] || uc_die "--instance wants a number from 1 up"
PORT=$((UC_PORT + INSTANCE - 1))
run() {
	if ((DRY)); then
		printf '    would run: %s\n' "$*"
	else
		"$@"
	fi
}

# ---------------------------------------------------------------- ULTRAKILL

UK=''
if [[ -n $UKDIR ]]; then
	UK=${UKDIR%/}
elif ! UK=$(uc_find_ultrakill); then
	UK=''
fi

if [[ $UKMODE == auto ]]; then
	# the dev client is driven from here, as Play-Ultracraft.ps1 does; with Prism the mod starts ULTRAKILL itself
	if [[ $ENTRY == dev ]]; then UKMODE=yes; else UKMODE=no; fi
fi

STARTED_UK=0
if [[ $UKMODE == yes ]]; then
	uc_head 'ULTRAKILL'
	if uc_uk_running; then
		uc_ok 'already running (using that one)'
	elif [[ -z $UK ]]; then
		uc_warn "ULTRAKILL's folder not found: starting it is up to you (Steam, or --ukdir PATH)"
	else
		uc_steam_cmd "$UK" || uc_die 'Steam not found: start ULTRAKILL yourself (or pass --no-uk)'
		args=(-applaunch "$UC_APP_ID" -ultracraft -screen-fullscreen 0 -screen-width 1280 -screen-height 720)
		if ((INSTANCE > 1)); then args+=(-ucinstance "$INSTANCE"); fi
		uc_step "starting it through Steam (instance $INSTANCE)"
		if ((DRY)); then
			printf '    would run: %s %s\n' "${UC_STEAM_CMD[*]}" "${args[*]}"
		else
			"${UC_STEAM_CMD[@]}" "${args[@]}" >/dev/null 2>&1 &
			disown || true
		fi
		STARTED_UK=1
	fi
	if [[ $WAIT == 1 && $STARTED_UK == 1 ]] && ! ((DRY)); then
		printf '    waiting for ULTRAKILL to be ready (its plugin at 127.0.0.1:%d)' "$PORT"
		ready=0
		for _ in $(seq 1 45); do
			if uc_port_open "$PORT"; then
				ready=1
				break
			fi
			printf '.'
			sleep 2
		done
		printf '\n'
		if ((ready)); then uc_ok 'ULTRAKILL is up'; else uc_warn "ULTRAKILL isn't answering yet: Minecraft waits for it and says where it is"; fi
	fi
fi

# ---------------------------------------------------------------- Minecraft

exit_code=0
case $ENTRY in
dev)
	uc_head 'Minecraft (the dev client)'
	JDK21=$(uc_find_jdk21) || uc_die 'no JDK 21 found: install jdk21-openjdk, or play through Prism (--prism)'
	# Loom's launch files: launch.cfg beside the project's .gradle, and the arg files under the build directory (which
	# is build/<minecraft> for this project, build/ for older layouts)
	ARGS=$(ls "$ROOT"/fabric/.gradle/loom-cache/launch.cfg "$ROOT"/fabric/build/*/loom-cache/launch.cfg "$ROOT"/fabric/build/loom-cache/launch.cfg 2>/dev/null | head -1 || true)
	CP=$(ls "$ROOT"/fabric/build/*/loom-cache/argFiles/runClient "$ROOT"/fabric/build/loom-cache/argFiles/runClient 2>/dev/null | head -1 || true)
	if [[ ! -d $ROOT/fabric/run/saves/$WORLD ]]; then
		uc_warn "no world \"$WORLD\" in fabric/run/saves yet: Minecraft opens its title screen instead"
		uc_info "create one with that name (Singleplayer → Create New World), or pass --world NAME"
	fi
	if [[ -n $ARGS && -n $CP ]]; then
		cfg_args=(
			"-Dfabric.dli.config=$ARGS"
			-Dfabric.dli.env=client
			-Dultracraft.noLaunch="$([[ $STARTED_UK == 1 ]] && echo true || echo false)"
		)
		if ((INSTANCE > 1)); then cfg_args+=("-Dultracraft.instance=$INSTANCE"); fi
		uc_step "starting Minecraft from Loom's launch files (world \"$WORLD\")"
		if ((DRY)); then
			printf '    would run: (cd %s && %s %s @%s net.fabricmc.devlaunchinjector.Main --username V1 --quickPlaySingleplayer %s)\n' \
				"$ROOT/fabric/run" "$JDK21/bin/java" "${cfg_args[*]}" "$CP" "$WORLD"
		else
			mkdir -p "$ROOT/fabric/run"
			(cd "$ROOT/fabric/run" && "$JDK21/bin/java" "${cfg_args[@]}" "@$CP" net.fabricmc.devlaunchinjector.Main --username V1 --quickPlaySingleplayer "$WORLD") || exit_code=$?
		fi
	else
		uc_step "no Loom launch files yet: starting Minecraft through Gradle (this run writes them)"
		if ((DRY)); then
			printf '    would run: (cd %s && JAVA_HOME=%s ./gradlew runClient)\n' "$ROOT/fabric" "$JDK21"
		else
			(cd "$ROOT/fabric" && JAVA_HOME="$JDK21" PATH="$JDK21/bin:$PATH" bash ./gradlew --console=plain runClient) || exit_code=$?
		fi
	fi
	;;
prism)
	uc_head 'Minecraft (PrismLauncher, instance "Ultracraft")'
	PRISM=''
	for candidate in "${PRISM_DIR:-}" "$HOME/.local/share/PrismLauncher" "$HOME/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher"; do
		[[ -n $candidate && -f $candidate/accounts.json ]] && {
			PRISM=$candidate
			break
		}
	done
	[[ -n $PRISM ]] || uc_die 'no PrismLauncher found: pass --dev, or point PRISM_DIR at its data folder'
	[[ -f $PRISM/instances/Ultracraft/instance.cfg ]] || uc_die "no \"Ultracraft\" instance in $PRISM: run arch/install-ultracraft.sh --prism"
	
	# Check if we're running inside a Flatpak sandbox
	if uc_is_flatpak; then
		uc_warn 'Running inside a Flatpak sandbox'
		uc_info 'The Prism Flatpak may not have access to your Steam library'
		uc_info 'If ULTRAKILL is not detected, try:'
		uc_info '  1. flatpak override --filesystem=~/.local/share/Steam org.prismlauncher.PrismLauncher'
		uc_info '  2. Or install Prism natively (not as Flatpak) for full filesystem access'
		uc_info '  3. Or set sharedDir in config to ULTRAKILL'
	fi
	
	if [[ $UKMODE == no ]]; then
		uc_info 'ULTRAKILL is started (and closed) by the mod: Gameplay → Start ULTRAKILL, or pass --uk'
	fi
	# Make sure the mod knows where ULTRAKILL is, even when it can't find it through Steam or the running process.
	# UK may already be set from --ukdir; if not, try to find it.
	if [[ -z $UK ]]; then
		UK=$(uc_find_ultrakill) || true
	fi
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
				# Update if different
				OLD=$(grep '^ultrakillDir=' "$CFG" | head -1 | cut -d= -f2-)
				if [[ "$OLD" != "$UK_NORM" ]]; then
					sed -i "s|^ultrakillDir=.*|ultrakillDir=$UK_NORM|" "$CFG"
					uc_ok "ultrakillDir updated to $UK_NORM (was $OLD)"
				else
					uc_step "ultrakillDir already in $CFG (left as it is)"
				fi
			else
				printf '\nultrakillDir=%s\n' "$UK_NORM" >>"$CFG"
				uc_ok "ultrakillDir=$UK_NORM (appended to $CFG)"
			fi
		else
			printf '# Ultracraft config (minimal: the mod writes the rest)\nultrakillDir=%s\n' "$UK_NORM" >"$CFG"
			uc_ok "ultrakillDir=$UK_NORM (written to $CFG)"
		fi
		uc_info "Mod will look for ULTRAKILL at: $UK_NORM"
	else
		uc_warn "ULTRAKILL not found: set ultrakillDir in the config by hand, or pass --ukdir PATH"
		uc_info "Config file: $PRISM/instances/Ultracraft/minecraft/config/ultracraft.properties"
	fi
	if [[ $PRISM == "$HOME/.var/app/org.prismlauncher.PrismLauncher/"* ]]; then
		run flatpak run org.prismlauncher.PrismLauncher --launch Ultracraft
	else
		run "$(command -v prismlauncher || echo prismlauncher)" --launch Ultracraft
	fi
	;;
*) uc_die "unknown entry point: $ENTRY" ;;
esac

# ---------------------------------------------------------------- tidying up

if ((STARTED_UK)) && [[ $ENTRY == dev ]] && ! ((DRY)); then
	uc_head 'Closing ULTRAKILL (this script started it)'
	uc_uk_quit "$PORT" && uc_ok 'asked it to quit' || uc_warn "couldn't ask it to quit (already gone?)"
	for _ in $(seq 1 10); do
		uc_uk_running || break
		sleep 1
	done
	if uc_uk_running; then
		uc_step 'still running: closing the process'
		pkill -f -i 'ULTRAKILL\.exe' || true
	fi
fi

exit "$exit_code"
