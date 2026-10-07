package dev.ultracraft;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The ULTRAKILL side, set up by the mod: players install the one jar and Ultracraft does the rest when Minecraft starts.
 * It finds ULTRAKILL (its folder from the Steam libraries, or ultrakillDir in the config), gives it BepInEx 5 if it has
 * none (an existing BepInEx and whatever else it loads is left alone), and puts this jar's UltraBridge plugin in
 * BepInEx/plugins/UltraBridge, replacing an older one, so the two halves are always the same version. A copy of the
 * plugin dropped somewhere else under plugins (it would load twice) is set aside as UltraBridge.dll.disabled. A plugin
 * that's there and isn't one Ultracraft put there (someone's own build) is left as it is. Nothing is downloaded: both
 * come inside the jar (see ultrakill/BEPINEX-NOTICE.txt). -Dultracraft.noInstall skips all of it.
 */
final class UkInstaller {
	private static final Logger LOG = LoggerFactory.getLogger("ultracraft");
	private static final String APP_ID = "1229490";
	/** What the last plugin Ultracraft installed hashed to: a different one there now isn't ours to replace. */
	private static final String MARKER = "installed-by-ultracraft.txt";

	/** What happened, for the toast on the title screen (null: nothing worth saying). */
	record Result(String title, String detail, boolean problem) {}

	private UkInstaller() {}

	/** ULTRAKILL's folder, or null (looked for once). */
	static Path game() {
		if (!looked) {
			looked = true;
			try {
				game = findUltrakill();
			} catch (Exception e) {
				LOG.warn("looking for ULTRAKILL: {}", e.toString());
			}
			if (game != null) {
				LOG.info("ULTRAKILL at {}", game);
			} else {
				LOG.warn("ULTRAKILL not found (Steam libraries, a running ULTRAKILL, the config's ultrakillDir): its plugin isn't set up");
				if (!UltracraftConfig.ultrakillDir.isBlank()) {
					LOG.warn("configured ultrakillDir: {}", UltracraftConfig.ultrakillDir);
					LOG.warn("configured path exists: {}", Files.exists(Path.of(UltracraftConfig.ultrakillDir)));
					LOG.warn("configured path has ULTRAKILL.exe: {}", Files.exists(Path.of(UltracraftConfig.ultrakillDir).resolve("ULTRAKILL.exe")));
				}
			}
		}
		return game;
	}

	private static Path game;
	private static boolean looked;

	/** Whether setting up would change anything in ULTRAKILL's folder (asked before the first time it does). */
	static boolean needed() {
		Path g = game();
		if (g == null || System.getProperty("ultracraft.noInstall") != null) return false;
		try {
			if (bundled("ultrakill/bepinex") != null && !(Files.isRegularFile(g.resolve("BepInEx/core/BepInEx.dll")) && Files.isRegularFile(g.resolve("winhttp.dll")))
				&& !Files.isRegularFile(g.resolve("BepInEx/core/BepInEx.Core.dll"))) return true;
			Path src = bundled("ultrakill/plugin/UltraBridge.dll");
			if (src == null) return false;
			Path target = g.resolve("BepInEx/plugins/UltraBridge/UltraBridge.dll");
			if (!Files.isRegularFile(target)) return true;
			return !sha256(Files.readAllBytes(target)).equals(sha256(Files.readAllBytes(src)));
		} catch (Exception e) {
			return false;
		}
	}

	/** Sets ULTRAKILL up if it needs it. Runs before ULTRAKILL is started. */
	static Result install() {
		if (System.getProperty("ultracraft.noInstall") != null) return null;
		Path game = game();
		if (game == null) return new Result("ULTRAKILL not found", "Install it through Steam, or set ultrakillDir in config/ultracraft.properties", true);
		try {
			if (Files.isRegularFile(game.resolve("BepInEx/core/BepInEx.Core.dll")) && !Files.isRegularFile(game.resolve("BepInEx/core/BepInEx.dll"))) {
				LOG.warn("ULTRAKILL has BepInEx 6; UltraBridge needs BepInEx 5");
				return new Result("ULTRAKILL has BepInEx 6", "Ultracraft needs BepInEx 5: see the Ultracraft README", true);
			}
			int bepinex = installBepInEx(game);
			String plugin = installPlugin(game);
			if (bepinex > 0) return new Result("ULTRAKILL set up for Ultracraft", "Installed BepInEx and the UltraBridge plugin", false);
			if (plugin != null) return new Result("UltraBridge " + plugin, "ULTRAKILL's half of Ultracraft is up to date", false);
			return null;
		} catch (Locked e) {
			LOG.warn("couldn't replace UltraBridge.dll (ULTRAKILL is running): {}", e.getMessage());
			return new Result("Couldn't update UltraBridge", "Close ULTRAKILL, then restart Minecraft", true);
		} catch (Exception e) {
			LOG.warn("setting up ULTRAKILL: {}", e.toString());
			return new Result("Couldn't set up ULTRAKILL", "See the Ultracraft README to install its plugin by hand", true);
		}
	}

	// ------------------------------------------------------------------ finding ULTRAKILL

	static Path findUltrakill() {
		List<Path> tries = new ArrayList<>();
		if (!UltracraftConfig.ultrakillDir.isBlank()) {
			Path configured = Path.of(UltracraftConfig.ultrakillDir);
			tries.add(configured);
			tries.add(configured.toAbsolutePath().normalize());
		}
		// one that's running already says where it is (its command line may be the Proton wrapper, so scan it)
		ProcessHandle.allProcesses().filter(UkLauncher::isUltrakillProcess).forEach(p -> {
			String c = p.info().command().orElse(null);
			if (c != null) tries.add(Path.of(c).getParent());
			// the .exe may be a later arg under Proton; grab it from the command line
			String cmdl = p.info().commandLine().orElse(null);
			if (cmdl != null) {
				int idx = cmdl.lastIndexOf("ULTRAKILL.exe");
				if (idx >= 0) {
					String segment = cmdl.substring(0, idx);
					int slash = segment.lastIndexOf('/');
					if (slash >= 0) tries.add(Path.of(segment.substring(slash + 1)));
				}
			}
		});
		Path steam = steamDir();
		if (steam != null) {
			for (Path lib : libraries(steam)) {
				Path apps = lib.resolve("steamapps");
				String dir = installDir(apps.resolve("appmanifest_" + APP_ID + ".acf")).orElse("ULTRAKILL");
				tries.add(apps.resolve("common").resolve(dir));
			}
		}
		// ULTRAKILL is the Windows build either way (on Linux it runs through Proton, and its folder keeps its .exe)
		if (UkPaths.windows()) {
			tries.add(Path.of("C:\\Program Files (x86)\\Steam\\steamapps\\common\\ULTRAKILL"));
		} else {
			for (Path dir : linuxSteamDirs()) tries.add(dir.resolve("steamapps/common/ULTRAKILL"));
		}
		for (Path p : tries) if (p != null && Files.isRegularFile(p.resolve("ULTRAKILL.exe"))) return p;
		return null;
	}

	/**
	 * The prefix ULTRAKILL runs in when it is the Windows build under Linux (…/steamapps/compatdata/1229490/pfx),
	 * which is where its %TEMP% is (UkPaths). Null on Windows, or when the game is run some other way.
	 */
	static Path protonPrefix(Path game) {
		if (game == null || UkPaths.windows()) return null;
		Path common = game.getParent(), apps = common != null ? common.getParent() : null;
		if (apps == null) return null;
		Path pfx = apps.resolve("compatdata").resolve(APP_ID).resolve("pfx");
		return Files.isDirectory(pfx) ? pfx : null;
	}

	/** Where Steam usually is on Linux: Steam's own environment, the native install, then Flatpak. */
	private static List<Path> linuxSteamDirs() {
		List<Path> dirs = new ArrayList<>();
		add(dirs, System.getenv("STEAM_COMPAT_CLIENT_INSTALL_PATH"));
		Path home = Path.of(System.getProperty("user.home", "."));
		add(dirs, home.resolve(".steam/steam"));
		add(dirs, home.resolve(".steam/root"));
		add(dirs, home.resolve(".local/share/Steam"));
		add(dirs, home.resolve(".steam/debian-installation"));
		add(dirs, home.resolve(".var/app/com.valvesoftware.Steam/data/Steam"));
		return dirs;
	}

	private static void add(List<Path> dirs, String path) {
		if (path != null && !path.isBlank()) add(dirs, Path.of(path));
	}

	private static void add(List<Path> dirs, Path dir) {
		if (dir != null && !dirs.contains(dir)) dirs.add(dir);
	}

	/** Steam's folder: the registry's record on Windows, the usual places on Linux. */
	private static Path steamDir() {
		if (!UkPaths.windows()) {
			for (Path p : linuxSteamDirs()) if (Files.isRegularFile(p.resolve("steamapps/libraryfolders.vdf"))) return p;
			for (Path p : linuxSteamDirs()) if (Files.isDirectory(p.resolve("steamapps"))) return p;
			return null;
		}
		try {
			Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath").redirectErrorStream(true).start();
			try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
				String line;
				while ((line = r.readLine()) != null) {
					int i = line.indexOf("REG_SZ");
					if (line.contains("SteamPath") && i >= 0) {
						Path path = Path.of(line.substring(i + 6).trim().replace('/', '\\'));
						if (Files.isDirectory(path)) return path;
					}
				}
			}
		} catch (Exception ignored) {
		}
		Path fallback = Path.of("C:\\Program Files (x86)\\Steam");
		return Files.isDirectory(fallback) ? fallback : null;
	}

	private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"([^\"]+)\"");
	private static final Pattern VDF_INSTALLDIR = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"");

	/** Every Steam library folder (steamapps/libraryfolders.vdf), Steam's own first. */
	private static List<Path> libraries(Path steam) {
		List<Path> libs = new ArrayList<>();
		libs.add(steam);
		try {
			String vdf = Files.readString(steam.resolve("steamapps/libraryfolders.vdf"), StandardCharsets.UTF_8);
			Matcher m = VDF_PATH.matcher(vdf);
			while (m.find()) {
				Path lib = Path.of(m.group(1).replace("\\\\", "\\"));
				if (!libs.contains(lib)) libs.add(lib);
			}
		} catch (Exception ignored) {
		}
		return libs;
	}

	private static Optional<String> installDir(Path manifest) {
		try {
			Matcher m = VDF_INSTALLDIR.matcher(Files.readString(manifest, StandardCharsets.UTF_8));
			return m.find() ? Optional.of(m.group(1)) : Optional.empty();
		} catch (Exception e) {
			return Optional.empty();
		}
	}

	// ------------------------------------------------------------------ installing

	private static Path bundled(String path) {
		return FabricLoader.getInstance().getModContainer("ultracraft").flatMap(c -> c.findPath(path)).orElse(null);
	}

	/** BepInEx's files the game folder doesn't have yet (none overwritten); how many were copied. */
	private static int installBepInEx(Path game) throws IOException {
		Path root = bundled("ultrakill/bepinex");
		if (root == null || !Files.isDirectory(root)) {
			if (!Files.isRegularFile(game.resolve("BepInEx/core/BepInEx.dll"))) LOG.warn("this jar has no BepInEx to install, and ULTRAKILL has none");
			return 0;
		}
		// already there: nothing to do (another BepInEx 5, perhaps with other mods, stays as it is)
		if (Files.isRegularFile(game.resolve("BepInEx/core/BepInEx.dll")) && Files.isRegularFile(game.resolve("winhttp.dll"))) return 0;
		int copied = 0;
		List<Path> files;
		try (Stream<Path> s = Files.walk(root)) {
			files = s.filter(Files::isRegularFile).toList();
		}
		for (Path f : files) {
			Path to = game.resolve(root.relativize(f).toString());
			if (Files.exists(to)) continue;
			Files.createDirectories(to.getParent());
			Files.copy(f, to);
			copied++;
		}
		Files.createDirectories(game.resolve("BepInEx/plugins"));
		LOG.info("installed BepInEx in {} ({} files)", game, copied);
		return copied;
	}

	private static final class Locked extends IOException {
		Locked(String m) {
			super(m);
		}
	}

	/** The plugin in place and current: "installed", "updated", or null if it already was. */
	private static String installPlugin(Path game) throws IOException {
		Path src = bundled("ultrakill/plugin/UltraBridge.dll");
		if (src == null || !Files.isRegularFile(src)) {
			LOG.warn("this jar has no UltraBridge plugin to install");
			return null;
		}
		byte[] ours = Files.readAllBytes(src);
		String oursHash = sha256(ours);
		Path plugins = game.resolve("BepInEx/plugins");
		Path dir = plugins.resolve("UltraBridge");
		Path target = dir.resolve("UltraBridge.dll");
		Path marker = dir.resolve(MARKER);
		setAsideStrays(plugins, target);
		String result;
		if (!Files.isRegularFile(target)) {
			result = "installed";
		} else {
			String there = sha256(Files.readAllBytes(target));
			if (there.equals(oursHash)) {
				if (!Files.isRegularFile(marker)) Files.writeString(marker, oursHash);
				return null;
			}
			String put = Files.isRegularFile(marker) ? Files.readString(marker).trim() : null;
			if (put != null && !put.equals(there)) {
				// not the one Ultracraft put there: someone's own build, theirs to keep
				LOG.info("UltraBridge.dll in {} isn't one Ultracraft installed: left as it is", dir);
				return null;
			}
			result = "updated";
		}
		Files.createDirectories(dir);
		Path tmp = dir.resolve("UltraBridge.dll.new");
		Files.write(tmp, ours);
		try {
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			Files.deleteIfExists(tmp);
			throw new Locked(e.toString());
		}
		Files.writeString(marker, oursHash);
		LOG.info("UltraBridge plugin {} in {}", result, dir);
		return result;
	}

	/** Another UltraBridge.dll under plugins (dropped in by hand, in the wrong place) would load next to ours. */
	private static void setAsideStrays(Path plugins, Path target) throws IOException {
		if (!Files.isDirectory(plugins)) return;
		List<Path> strays;
		try (Stream<Path> s = Files.walk(plugins, 3)) {
			strays = s.filter(p -> p.getFileName().toString().equalsIgnoreCase("UltraBridge.dll") && !p.equals(target)).toList();
		}
		for (Path p : strays) {
			Path aside = p.resolveSibling("UltraBridge.dll.disabled");
			Files.move(p, aside, StandardCopyOption.REPLACE_EXISTING);
			LOG.info("set aside a second UltraBridge.dll: {}", aside);
		}
	}

	private static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
