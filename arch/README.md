# Ultracraft on Arch Linux

The same Ultracraft, played on Arch: Minecraft runs natively, ULTRAKILL runs its Windows build through Steam's
Proton, and the two talk over the same TCP link (`127.0.0.1:27110`) and the same shared files as on Windows.

```
arch/install-ultracraft.sh          # builds both halves, sets ULTRAKILL up, puts the mod where Minecraft loads it
arch/play-ultracraft.sh --dev       # the Loom dev client (like Play-Ultracraft.ps1), or:
arch/play-ultracraft.sh --prism     # the PrismLauncher instance "Ultracraft"
```

Both are normal Bash scripts: `--help` explains their options, `arch/install-ultracraft.sh --check` only looks
around and says what it found, `--dry-run` (play) says what it would start.

## What the installer does

1. **Finds Steam and ULTRAKILL**: every Steam folder (`~/.steam/steam`, `~/.local/share/Steam`, Flatpak's
   `~/.var/app/com.valvesoftware.Steam/data/Steam`, …), then `steamapps/libraryfolders.vdf`, then
   `appmanifest_1229490.acf`. `--ukdir PATH` says it by hand.
2. **Checks the toolchain**: `jdk21-openjdk`, `dotnet-sdk`, `unzip`, `curl` (and `python3` for the Prism instance's
   mods). Missing ones are installed with pacman after asking (`--yes` skips the question, `--no-deps` skips this).
3. **Gives ULTRAKILL BepInEx 5** if it has none (5.4.23.5 x64, the same build the mod carries), and makes sure Wine
   loads it: see [Wine's winhttp](#wines-winhttp-bepinex) below. An ULTRAKILL that already has BepInEx is left alone.
4. **Builds and installs the two halves**:
   - `dotnet build -c Release ultrabridge/UltraBridge.csproj -p:GameDir=<ULTRAKILL>` → the plugin lands in
     `BepInEx/plugins/UltraBridge/` (the same place, and with the same hash marker, the mod uses from Windows, so the
     mod keeps it up to date from now on);
   - `fabric: ./gradlew build -PukDir=<ULTRAKILL>` → `build/1.21.11/libs/ultracraft-<version>+1.21.11.jar`, with
     BepInEx and UltraBridge bundled inside it exactly as on Windows.
5. **Puts Minecraft's half where it plays from**:
   - `--dev` (the default): writes `fabric/run/config/ultracraft.properties` (`ultrakillDir`, `setupUltrakill`) for the
     Loom dev client;
   - `--prism`: creates or updates a PrismLauncher instance **Ultracraft** (Minecraft 1.21.11 + Fabric Loader
     0.19.5, Java 21), with the mod, Fabric API and Sodium in its `mods` folder (`--with-essential` adds Essential,
     for hosting worlds with friends). Prism downloads Minecraft the first time the instance starts.

Nothing of yours is touched outside the repository, ULTRAKILL's own folder and (with `--prism`) that instance.

## What is different from Windows

The mod itself is the same code; three things needed the Linux side written out (all in the mod, no patches to it):

| Windows | Arch (Proton) |
| --- | --- |
| Steam found through `HKCU\Software\Valve\Steam` in the registry | the Steam folders above, and `libraryfolders.vdf` |
| `%TEMP%` is one folder both games see | ULTRAKILL's `%TEMP%` is **inside its prefix**: `<library>/steamapps/compatdata/1229490/pfx/drive_c/users/<user>/AppData/Local/Temp`, while Minecraft's is `/tmp` |
| `steam.exe -applaunch 1229490 …` | `/usr/bin/steam` (or `flatpak run com.valvesoftware.Steam`) `-applaunch 1229490 …` |
| `ULTRAKILL.exe` in the process list | the same `.exe`, as a Proton process |

The shared-folder one is the important one: ULTRAKILL's frames (`ultracraft_frame2.bin`), V1's inventory portrait
(`ultracraft_doll.bin`), its arm (`ultracraft_arm.*`) and the shop terminal (`ultracraft_shop2.*`) all cross through
that folder. `UkPaths` reads Wine's own `%TEMP%` out of the prefix's `user.reg` and falls back to
`drive_c/users/steamuser/AppData/Local/Temp`, `drive_c/windows/temp` and then `/tmp`; `sharedDir` in
`config/ultracraft.properties` overrides it when both games are set up by hand.

### Wine's winhttp (BepInEx)

BepInEx 5 hooks the game through `winhttp.dll` in ULTRAKILL's folder; under Wine/Proton that only happens with the
override in place. The installer adds it to the prefix's registry when it is missing (Steam closed, `user.reg` backed
up first) — which is the same thing as this Steam launch option:

```
WINEDLLOVERRIDES="winhttp=n,b" %command%
```

BepInEx is then loaded whether ULTRAKILL is started by Steam's Play button or by the mod.

## Playing

- `arch/play-ultracraft.sh --dev`: the script starts ULTRAKILL (hidden, waiting for Minecraft), waits for its plugin,
  then starts the dev client. Closing Minecraft closes ULTRAKILL. `--world NAME` picks the save: the default
  `Ultracraft` has to exist in `fabric/run/saves` (create it once in-game; the script says so when it is missing and
  Minecraft opens its title screen instead — ULTRAKILL connects either way).
- `arch/play-ultracraft.sh --prism`: the mod starts and closes ULTRAKILL itself (Gameplay → *Start ULTRAKILL*, on by
  default), as it does on Windows.
- `--instance 2` runs a second pair of games at once (its own port and shared files) for testing co-op alone: pass it
  to the play script (or start ULTRAKILL with `-ucinstance 2` and Minecraft with `-Dultracraft.instance=2`).
- `--uk` / `--no-uk` force the script to start ULTRAKILL itself, or leave it alone.

Sodium and Iris work as they do on Windows; the Performance page (ULTRAKILL resolution, Effects Quality, …) is the
place to go if two games share the GPU badly. Under Proton the plugin parks ULTRAKILL's window off-screen, and a
compositor that throttles off-screen windows can show up as a lower ULTRAKILL frame rate — and the frame readback that
brings each frame to Minecraft is slower through DXVK than on Windows, which shows as `[frames] ULTRAKILL missed N of
the last 60 frames` in Minecraft's log. If that makes the world stutter, on a shop's or the settings' **Performance**
page: **ULTRAKILL Resolution** 540p, **Effects Quality** Medium/Low, and **Lock Step** (or **Low-Latency Frames**) off
— Minecraft then runs at its own pace instead of waiting for ULTRAKILL's frames.

## Troubleshooting

- **Minecraft says "ULTRAKILL not found"**: the mod couldn't work out the game's folder. Set `ultrakillDir` in
  `config/ultracraft.properties` (one path, e.g. `/home/you/.steam/steam/steamapps/common/ULTRAKILL`).
- **Running Prism as a Flatpak**: the Flatpak sandbox limits filesystem access. If ULTRAKILL is installed via
  Steam (especially Steam Flatpak), the mod may not be able to find it or its Proton prefix.
  - Grant filesystem access: `flatpak override --filesystem=~/.local/share/Steam org.prismlauncher.PrismLauncher`
  - Or install Prism natively (not as Flatpak) for full filesystem access
  - Or set both `ultrakillDir` and `sharedDir` in `config/ultracraft.properties` explicitly
- **Minecraft connects but the screen stays black, or "loading V1" forever**: ULTRAKILL isn't writing frames where
  Minecraft reads them. Check that ULTRAKILL's `%TEMP%` (the mod logs its answer at startup:
  `sharing files with ULTRAKILL in …`) matches the folder under the prefix; set `sharedDir` if you moved the prefix.
- **BepInEx isn't loading (no `BepInEx/LogOutput.log` updated, no UltraBridge in it)**: the winhttp override is
  missing. Close Steam, re-run the installer, or add `WINEDLLOVERRIDES="winhttp=n,b" %command%` to ULTRAKILL's Steam
  launch options.
- **"Unsupported class file major version 65"**: an old Fabric Loader; use 0.19 or later for 1.21.11.
- **Nothing on the Prism side**: the instance needs the game's assets and libraries downloaded on its first start
  (Prism's own progress window), and its Java is set to `/usr/lib/jvm/java-21-openjdk` — change it in the instance's
  settings if your JDK 21 is elsewhere.

## Removing it

- The mod from Minecraft: delete the jar from the instance's `mods` folder (or `fabric/build` for the dev client).
- The plugin from ULTRAKILL: delete `BepInEx/plugins/UltraBridge` — nothing else in the game folder is changed.
- BepInEx itself: only remove it if the installer added it (a `user.reg.ultracraft-backup` next to the prefix means
  the winhttp override was ours; the game folder's BepInEx is BepInEx's own).
