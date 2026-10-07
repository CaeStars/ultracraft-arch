package dev.ultracraft;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft started from the normal launcher brings ULTRAKILL along: as soon as Minecraft is up, ULTRAKILL starts
 * through Steam with -ultracraft (its window hidden, the Sandbox loading in the background, waiting for a world), and
 * closing Minecraft closes the ULTRAKILL it started. An ULTRAKILL already running is used as it is. The "Start
 * ULTRAKILL" setting (or -Dultracraft.noLaunch, which the test launcher passes) turns this off. First, ULTRAKILL gets
 * its half of Ultracraft (UkInstaller: BepInEx and the UltraBridge plugin), with a toast saying what was done; the
 * first time that would change ULTRAKILL's folder, only once the player says yes on the title screen.
 */
final class UkLauncher {
	private static final Logger LOG = LoggerFactory.getLogger("ultracraft");
	private static final String APP_ID = "1229490";
	private static final String FLATPAK_ID = "com.valvesoftware.Steam";
	private static boolean started;
	private static boolean askPending;

	private static void setUp(net.minecraft.client.Minecraft mc) {
		UkInstaller.Result r = UkInstaller.install();
		if (r != null) SystemToast.add(mc.getToastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal(r.title()), Component.literal(r.detail()));
	}

	private UkLauncher() {}

	static void register() {
		ClientLifecycleEvents.CLIENT_STARTED.register(mc -> {
			// the first time ULTRAKILL's folder would change, the player is asked (on the title screen); after a yes,
			// the plugin is kept up to date without asking
			if (UkInstaller.needed() && !UltracraftConfig.setupUltrakill) {
				askPending = true;
				return;
			}
			setUp(mc);
			launch();
		});
		ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
			if (!askPending || !(screen instanceof TitleScreen)) return;
			askPending = false;
			mc.execute(() -> mc.setScreen(new ConfirmScreen(yes -> {
				if (yes) {
					UltracraftConfig.setupUltrakill = true;
					UltracraftConfig.save();
					setUp(mc);
					launch();
				}
				mc.setScreen(new TitleScreen());
			}, Component.literal("Set up ULTRAKILL for Ultracraft?"),
				Component.literal("Ultracraft plays the real ULTRAKILL alongside Minecraft. For that, ULTRAKILL needs BepInEx 5 (a mod loader) and "
					+ "Ultracraft's UltraBridge plugin, both included in this mod. Ultracraft will add them to\n" + UkInstaller.game()
					+ "\n\nNothing else there is changed, and ULTRAKILL's own save and settings are never touched. Its plugin is kept up to date with "
					+ "each new version of Ultracraft. You can remove it any time: delete BepInEx/plugins/UltraBridge."),
				Component.literal("Install"), Component.literal("Not now"))));
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> close());
	}

	/** ULTRAKILL's process, whichever way the running game names it (on Linux it is its .exe under Proton too). */
	static Optional<ProcessHandle> running() {
		return ProcessHandle.allProcesses().filter(UkLauncher::isUltrakillProcess).findFirst();
	}

	/** True when the process looks like ULTRAKILL: its command or command line mentions ultrakill.exe. */
	static boolean isUltrakillProcess(ProcessHandle p) {
		String c = p.info().command().orElse("").toLowerCase(Locale.ROOT).replace('\\', '/');
		if (c.equals("ultrakill.exe") || c.endsWith("/ultrakill.exe")) return true;
		String cmdl = p.info().commandLine().orElse("").toLowerCase(Locale.ROOT).replace('\\', '/');
		return cmdl.contains("ultrakill.exe");
	}

	private static void launch() {
		if (!UltracraftConfig.launchUltrakill || System.getProperty("ultracraft.noLaunch") != null) return;
		if (running().isPresent() || UkLink.connected) {
			LOG.info("ULTRAKILL is already running");
			return;
		}
		List<String> steam = steamCommand();
		if (steam == null) {
			LOG.warn("Steam not found: start ULTRAKILL yourself (with the UltraBridge plugin)");
			return;
		}
		try {
			List<String> cmd = new ArrayList<>(steam);
			cmd.addAll(List.of("-applaunch", APP_ID, "-ultracraft", "-screen-fullscreen", "0", "-screen-width", "1280", "-screen-height", "720"));
			new ProcessBuilder(cmd).start();
			started = true;
			LOG.info("starting ULTRAKILL through {}", String.join(" ", cmd));
		} catch (Exception e) {
			LOG.warn("couldn't start ULTRAKILL: {}", e.toString());
		}
	}

	private static void close() {
		if (!started) return;
		// ULTRAKILL quits itself when asked; if it doesn't answer, it's closed
		if (UkLink.connected) UkLink.send("QUIT");
		running().ifPresent(p -> {
			try {
				p.onExit().get(6, TimeUnit.SECONDS);
			} catch (Exception e) {
				LOG.info("closing ULTRAKILL");
				p.destroy();
			}
		});
	}

	/**
	 * How to ask Steam to start ULTRAKILL: its own steam.exe on Windows; on Linux the Steam that has the game
	 * (the Flatpak one when the game lives in its folders, otherwise steam itself).
	 */
	private static List<String> steamCommand() {
		if (UkPaths.windows()) {
			String exe = steamExe();
			return exe != null ? List.of(exe) : null;
		}
		Path home = Path.of(System.getProperty("user.home", "."));
		Path flatpak = home.resolve(".var/app/" + FLATPAK_ID);
		boolean flatpakSteam = Files.isDirectory(flatpak) && which("flatpak") != null;
		Path game = UkInstaller.game();
		if (flatpakSteam && game != null && game.startsWith(flatpak)) return List.of("flatpak", "run", FLATPAK_ID);
		for (Path p : List.of(Path.of("/usr/bin/steam"), Path.of("/usr/games/steam"), home.resolve(".local/share/Steam/steam.sh"), home.resolve(".steam/steam/steam.sh"))) {
			if (Files.isRegularFile(p)) return List.of(p.toString());
		}
		String steam = which("steam");
		if (steam != null) return List.of(steam);
		if (flatpakSteam) return List.of("flatpak", "run", FLATPAK_ID);
		return null;
	}

	/** A program found in PATH, or null. */
	private static String which(String name) {
		for (String dir : System.getenv().getOrDefault("PATH", "").split(":")) {
			if (dir.isBlank()) continue;
			Path p = Path.of(dir, name);
			if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
		}
		return null;
	}

	/** Steam's own record of where it is (HKCU\Software\Valve\Steam SteamExe), or the usual place. */
	private static String steamExe() {
		try {
			Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamExe").redirectErrorStream(true).start();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				String line;
				while ((line = r.readLine()) != null) {
					int i = line.indexOf("REG_SZ");
					if (line.contains("SteamExe") && i >= 0) {
						String path = line.substring(i + 6).trim().replace('/', '\\');
						if (new File(path).isFile()) return path;
					}
				}
			}
		} catch (Exception ignored) {
		}
		String fallback = "C:\\Program Files (x86)\\Steam\\steam.exe";
		return new File(fallback).isFile() ? fallback : null;
	}
}
