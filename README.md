# Ultracraft

The real ULTRAKILL, played inside the real Minecraft 1.21.11. ULTRAKILL runs alongside Minecraft and is drawn into
its window; Minecraft's blocks and mobs become ULTRAKILL's world and enemies. Bosses with modifiers, P, the shop,
upgrades, arenas themed after its layers, its music in fights, the Cyber Grind, cheats, and LAN co-op.

You need to own **ULTRAKILL** (Steam) and **Minecraft Java Edition**. On Windows that is all; on Linux ULTRAKILL runs
through Proton and the Arch scripts in [arch/](arch/README.md) set both halves up.

## Install

You don't install anything into ULTRAKILL yourself. When Minecraft starts, Ultracraft finds ULTRAKILL through Steam and
sets it up: it adds BepInEx 5 if ULTRAKILL doesn't have it yet, and the UltraBridge plugin, updated with every new
version of the mod. A toast on the title screen says what it did. Have Steam running.

**Easiest: the modpack.** From this repository's **Releases**:
- **Modrinth App**: download `Ultracraft.mrpack`, then *Add an instance → Import from file*. You get Ultracraft, Fabric
  API, Sodium and Essential.
- **CurseForge App**: download `Ultracraft-CurseForge.zip`, then *Create Custom Profile → Import*. Same mods, plus
  Essential Tweaks, which unbinds Essential's keys so they don't clash with ULTRAKILL's.

Play that instance. ULTRAKILL starts by itself (and closes with Minecraft); open a world and you become V1 (F8 toggles
back to Steve).

**On Arch Linux (ULTRAKILL through Proton):** `arch/install-ultracraft.sh` finds Steam and ULTRAKILL, gives it
BepInEx and the plugin, builds both halves and puts the mod where Minecraft loads it (the Loom dev client, or a
PrismLauncher instance with `--prism`); `arch/play-ultracraft.sh` then starts and stops the two games together. What
the Linux side has to do differently (Proton's `%TEMP%`, Steam's folders, Wine's winhttp override) is in
[arch/README.md](arch/README.md).

**Or by hand:** install **Fabric Loader** for Minecraft **1.21.11** (https://fabricmc.net/use/installer/), and put
**Fabric API** for 1.21.11 (https://modrinth.com/mod/fabric-api) and `ultracraft-x.y.z.jar` (from `Ultracraft.zip` in
the Releases) in your `mods` folder.

ULTRAKILL not found (installed somewhere unusual)? Put its folder in `config/ultracraft.properties` as
`ultrakillDir=D:\\Games\\ULTRAKILL` (double backslashes; on Linux one path,
`ultrakillDir=/home/you/.steam/steam/steamapps/common/ULTRAKILL`). It already has BepInEx 6? Ultracraft needs BepInEx 5. Still
stuck: unzip BepInEx 5 (x64, https://github.com/BepInEx/BepInEx/releases) into the ULTRAKILL folder yourself, and put
`UltraBridge.dll` from `Ultracraft.zip` in `ULTRAKILL/BepInEx/plugins/UltraBridge/`.

Commands: `/uc help`. On someone else's world, the ones that hand things out (P, weapons, upgrades, calling bosses) are
for the host and operators.

Stuck on "ULTRAKILL connected - loading V1..."? Update to the latest release: older ones waited forever on an ULTRAKILL
save that hadn't finished the tutorial. The message now names the ULTRAKILL level it's waiting in.

Minecraft crashes at launch with "Unsupported class file major version 65"? Your Fabric Loader is too old for
Minecraft 1.21.11: run the latest Fabric installer (https://fabricmc.net/use/installer/), install the newest loader
(0.19 or later) for 1.21.11, and start that profile.

## Settings

**Ultracraft...** (title screen, pause menu, Options, or `/uc settings`) has a page for each of these:
- **Enemies & Bosses**: Minecraft's mobs, ULTRAKILL's enemies, bosses (the time between them, their difficulty, how often they
  bring traits), arenas, enemies as Steve.
- **Gameplay**: becoming V1, what breaks blocks, impact frames, starting ULTRAKILL with Minecraft.
- **Shop & Rewards**: style rewards, the OP Shop, the sharp shop screen.
- **Performance**: ULTRAKILL's resolution and frame rate, low-latency frames.
- **Music**: ULTRAKILL's fight music, boss themes, calm music, volumes.
- **Cheats**: ULTRAKILL's Sandbox cheats and Ultracraft's.
- **ULTRAKILL Settings**: sensitivity, field of view, screen shake and the rest, as Ultracraft plays them.
- **ULTRAKILL Controls**: rebind ULTRAKILL's keys, and give each weapon its own key.

## Enemies, arenas and rewards

ULTRAKILL's enemies spawn in the dark, and which ones depends on where you are:
- husks on the plains;
- Greed's soldiers, idols and sentries in deserts and badlands;
- Gluttony's flesh in swamps;
- Violence's machines in jungles and deep caves;
- in the Nether, demons by biome: souls in soul sand valleys, flesh in crimson forests, industry in basalt deltas;
- in the End, angels (Virtues, Powers, Providence).

ULTRAKILL's enemies fight Minecraft's hostile mobs too. A swing, stomp, Virtue pillar or blast that would launch V1
launches a mob just as far, and a mob knocked about by them takes no fall damage.

**Arenas** generate in new parts of the world, one for each of ULTRAKILL's layers:
- Prelude, Limbo, Lust, Gluttony, Greed, Wrath and Violence in the Overworld;
- Heresy in the Nether;
- a Prime Sanctum in the End.

Each has a boss waiting. Step inside the ring as V1 to fight it, with the layer's own song. Beat it and a chest of loot appears on the dais. `/locate structure #ultracraft:arenas` finds the nearest one.

**Style rewards**: kills at style rank S and up pay extra experience and loot. Every fifth kill in a row at that rank pays out for sure, with P on top. The loot gets better with the rank: diamonds and golden apples at SSS, netherite at ULTRAKILL.

## Music

**Ultracraft... → Music → Fight Music** picks what plays in fights:
- off;
- a random ULTRAKILL song each fight;
- any song from its soundtrack: the levels, the Prime Sanctums, the Cyber Grind's tracks.

Bosses and arenas bring their own song where ULTRAKILL has one (V2: Versus, Minos Prime: Order...). Minecraft's music pauses meanwhile.

## Cheats

**Ultracraft... → Cheats** has two groups:
- **ULTRAKILL's own Sandbox cheats**: noclip, flight, invincibility, no weapon cooldown, infinite wall jumps, blind enemies, enemy infighting, Kill All Enemies, the **Spawner Arm** (off by default; turn it on here to get it in weapon slot 6) and more.
- **Ultracraft's**: Infinite P, All Weapons & Arms, One-Hit Kills, Slow Motion, Super Speed, Infinite Stamina, Never Hungry.

They're Ultracraft's settings: ULTRAKILL's own save and settings are never touched. In your own world (singleplayer, or one you host) they're always available; on someone else's server, only for operators.

## If it's laggy

Two games run at once and share your graphics card. In **Ultracraft... → Performance**:
- **Low-End PC Preset**: one click sets everything below to its cheap option (each can still be changed after);
  **Reset to Defaults** undoes it.
- **ULTRAKILL Resolution**: 540p, 480p or 360p is the biggest frame rate win (720p is the default).
- **Effects Quality**: Medium or Low uses ULTRAKILL's simpler explosions, fire and spawn effects. It also turns off
  environment particles and keeps less gore on screen. Low also drops hit sparks and shadows.
- **Blood Stains**: how many stay painted on Minecraft's blocks (Off, 500, 1,500, 4,096 or All).
- **Extra Gore**: off leaves only ULTRAKILL's own blood on deaths.
- **Terrain Range**: 64 or 96 blocks of terrain sent to ULTRAKILL instead of 128 (less work for both games).
- **ULTRAKILL FPS Cap**: Match Minecraft (the default) follows Minecraft's frame limit, the smoothest (as V1, Minecraft
  shows every ULTRAKILL frame once, in step with it); a lower cap leaves Minecraft more room on a weak graphics card.
- **Low-Latency Frames** (on by default): ULTRAKILL hands each frame over as soon as it's drawn: less input lag.
- **Sharp Shop Screen**: off.

Also: Minecraft render distance 8-12 chunks. ULTRAKILL's own graphics options (in ULTRAKILL itself) apply too.

**Sodium and Iris** work with Ultracraft (tested with Sodium 0.8.14 and Iris 1.10.8 on 1.21.11): put them in the mods
folder for a faster Minecraft side. Shader packs are untested.

## Multiplayer

Everyone plays with their own ULTRAKILL (it starts by itself when Minecraft does), and everyone needs the same mods.

- **Easiest: [Essential](https://essential.gg)** (Fabric, 1.21.11) in the mods folder: host your world from Essential's
  menu and invite your friends; they join from their invites. Works with Ultracraft out of the box.
- Or **Open to LAN** from the pause menu (same network, or with a tunnel such as playit.gg).

You share the world and the fight: each player's ULTRAKILL runs the enemies around them, and the others see and hit
them too. Other players show up as V1, holding the gun they have out, and you see and hear what they do: their beams,
projectiles, explosions and shots. Each player has their own P, gear and upgrades; `/uc p give <player> <amount>`
hands some of yours to someone else.

Over ULTRAKILL's view, each other player has their name, health and distance over their head, seen through walls, and
at the screen's edge when they're off it (**Teammate Markers**, Gameplay settings).

Bosses fight every V1 nearby: one goes for whoever hurts it most and is close, holds on to them for a few seconds, and
now and then turns on someone else. Everyone but the player it came for sees its health in a boss bar.

Die in a boss fight (or a Cyber Grind run) while a teammate fights on and you're only **down**: the camera follows a
teammate (the mouse turns it round them, left and right click switch teammate), and a marker of green light stands
where you fell. A teammate who stands on it for 4 seconds (2 with two of them) brings you back up right there. Otherwise
you're back beside them once the boss is beaten or the Grind's wave is cleared. Only when everyone is down is it lost.

**Duels**: `/uc duel <player>` challenges another V1; they accept with a click in chat (or `/uc duel accept`). After a
countdown your shots, punches and blasts hurt each other (half as hard as they'd hurt a mob). The first V1 down loses,
nobody actually dies, and both get back up at full health. `/uc duel forfeit` gives up.

## Controls

**Ultracraft... → ULTRAKILL Controls...** rebinds ULTRAKILL's keys (movement, dash, slide, fire, punch, arm, whiplash,
weapons...). Click an action, press the key or mouse button; Esc cancels. Minecraft's own action on that key steps aside
while you're V1 with guns out. F8 switches V1/Steve and V switches guns/Minecraft hands.

Each weapon (Piercer, Marksman, Core Eject, Firestarter...) can also have its own key, which switches straight to it.
They're unbound until you set them; Esc on one unbinds it. The Alternate versions share their colour's key.

With Minecraft hands out, V1 holds blocks, tools and food in ULTRAKILL's own arm (the Feedbacker's model, taken from
your ULTRAKILL the first time it runs). **F5** works as V1 too: the camera moves behind V1 (or in front) and you see
V1's body.

Back as Steve (F8), ULTRAKILL's enemies stay: you still see them, they still come for you (their hits cost hearts),
and you can fight them as Steve. Turn **ULTRAKILL While Steve** off to have them wait, frozen, until you're V1 again.

Your own ULTRAKILL save is never written: progress lives in the Minecraft world.

## Recipes

**Shop**: 5 iron ingots, 2 gold ingots, a glass pane and a block of redstone.

![Shop recipe](docs/recipes/shop.png)

Place it and walk up to it as V1: ULTRAKILL's shop terminal, where you spend P on weapons, variants and upgrades. The
revolver, shotgun and nailgun pages have an **ALTERNATE** button: it buys the alternate version, and after that switches
the weapon between standard and alternate.

**The Cyber Grind** starts from the shop's Cyber Grind page. With **Cyber Grind Arenas** on (Enemies & Bosses), you
and the other V1s by the shop go into the Grind's own arenas: 50 of them, each after one of ULTRAKILL's levels or
layers, under one of that layer's ULTRAKILL skies. Each run starts in a random arena, and every 5 waves it moves on to
another (all 50 come up before any comes twice); every 15th wave is a boss. Each visit rolls its own take on the arena:
where the pillars stand, the platforms, the raised squares, the sky. The arenas can't be broken. Each has a temporary
shop near its edge, under a column of lights (the chat says which way): **Leave** on its Cyber Grind page takes
everyone back to where they started. `/uc grind start` starts a run on your own, `/uc grind arena <1-50>` starts in
that arena, `/uc grind stop` ends the run. With the setting off, the waves come round the shop you started
from.

**Co-op**: anyone can join a run already going: **Join** on any shop's Cyber Grind page, or `/uc grind join`. The waves
grow with every V1 fighting them. Go down and a teammate can bring you back (or you're up again when the wave is
cleared); with everyone down the run is over and you all go back where you started, alive. Leave on a shop (or
`/uc grind leave`) takes just you out, and if the player who started it leaves, the next one carries the run on.

## Made with AI

Ultracraft was coded with **Claude** (Anthropic's AI) through Claude Code: the Fabric mod, the ULTRAKILL plugin, the
tests and this guide were written by Claude, directed, played and tested by a person.

## Build from source

- Fabric mod: JDK 21, `cd fabric && ./gradlew build` → `fabric/build/1.21.11/libs/` (`-Pmc=1.20.1` for the 1.20.1 build, in progress).
- ULTRAKILL plugin: .NET SDK, `dotnet build -c Release ultrabridge/UltraBridge.csproj -p:GameDir="<your ULTRAKILL folder>"`
  (it compiles against your own install's DLLs and copies the result into its BepInEx plugins).
- On Arch Linux both of those, and the ULTRAKILL side around them, are what `arch/install-ultracraft.sh` runs
  (`fabric: ./gradlew build -PukDir=<ULTRAKILL>` also puts BepInEx and the plugin inside the jar).

No game files are included in this repository.
