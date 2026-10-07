package dev.ultracraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The folder the two halves of Ultracraft share their files in (ULTRAKILL's frames, V1's portrait, its arm, the shop
 * terminal): ULTRAKILL writes them where its own %TEMP% points, Minecraft reads them from there.
 *
 * <p>On Windows both games are Windows programs, so that is simply java.io.tmpdir. Under Proton (Linux) ULTRAKILL is a
 * Windows program with a prefix of its own: its %TEMP% is inside it (c:/users/&lt;user&gt;/AppData/Local/Temp), which on
 * the Linux side is
 * &lt;library&gt;/steamapps/compatdata/1229490/pfx/drive_c/users/&lt;user&gt;/AppData/Local/Temp, while Minecraft's own
 * temp is /tmp. Here the prefix's temp folder is worked out instead, so both ends land in the same place either way.
 * ultracraft.properties' sharedDir says where, when it isn't found by itself (or when the two games are set up by
 * hand).
 */
final class UkPaths {
	private static final Logger LOG = LoggerFactory.getLogger("ultracraft");
	/** ULTRAKILL's app id: its prefix is compatdata/<id> (the same one UkInstaller looks in for the game). */
	private static final String APP_ID = "1229490";

	private UkPaths() {}

	private static Path dir;
	private static boolean looked;

	/** The shared folder; never null (java.io.tmpdir when nothing else can be found). */
	static synchronized Path shared() {
		if (!looked) {
			looked = true;
			try {
				dir = resolve();
			} catch (Exception e) {
				LOG.warn("looking for ULTRAKILL's temp folder: {}", e.toString());
			}
			if (dir == null) {
				dir = Path.of(System.getProperty("java.io.tmpdir"));
				LOG.warn("ULTRAKILL's temp folder not found: sharing files with it in {}", dir);
			} else {
				LOG.info("sharing files with ULTRAKILL in {}", dir);
			}
		}
		return dir;
	}

	static boolean windows() {
		return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
	}

	private static Path resolve() throws IOException {
		if (!UltracraftConfig.sharedDir.isBlank()) {
			Path p = Path.of(UltracraftConfig.sharedDir);
			if (Files.isDirectory(p)) return p;
			LOG.warn("sharedDir {} isn't a folder: looking for ULTRAKILL's temp instead", p);
		}
		if (windows()) return Path.of(System.getProperty("java.io.tmpdir"));
		// Linux (or macOS) with ULTRAKILL through Proton: its %TEMP% lives inside the game's prefix.
		// If the mod couldn't find ULTRAKILL's folder (game() is null), look it up from the running process.
		Path prefix = UkInstaller.protonPrefix(UkInstaller.game());
		if (prefix == null) prefix = protonPrefixFromRunning();
		if (prefix == null) return null;
		Files.createDirectories(prefix);
		Path fromWine = prefixTempIn(prefix), found = firstExisting(prefix);
		if (fromWine != null && !Files.isDirectory(fromWine)) {
			// Wine's own TEMP is the one ULTRAKILL will write to; it may not exist until it starts
			Files.createDirectories(fromWine);
			return fromWine;
		}
		return fromWine != null ? fromWine : found;
	}

	/** The prefix of a running ULTRAKILL, when findUltrakill() missed it (Proton wrapper as the command). */
	private static Path protonPrefixFromRunning() {
		return UkLauncher.running().map(p -> {
			String cmdl = p.info().commandLine().orElse("");
			int idx = cmdl.indexOf("steamapps/common");
			if (idx < 0) return null;
			int start = cmdl.lastIndexOf('/', idx - 1) + 1;
			String common = cmdl.substring(start, cmdl.indexOf("ULTRAKILL.exe", idx));
			Path commonDir = Path.of(common);
			if (!Files.isDirectory(commonDir.resolve("../compatdata/1229490/pfx"))) return null;
			return commonDir.resolve("../compatdata/1229490/pfx").normalize();
		}).orElse(null);
	}

	/** The folder Wine's TEMP points at (<prefix>/drive_c/users/<user>/AppData/Local/Temp, usually). */
	private static Path prefixTempIn(Path prefix) {
		try {
			Path reg = prefix.resolve("user.reg");
			if (!Files.isRegularFile(reg)) return null;
			boolean environment = false;
			for (String line : Files.readAllLines(reg, StandardCharsets.UTF_8)) {
				String t = line.trim();
				if (t.startsWith("[")) {
					environment = t.equalsIgnoreCase("[Environment]");
					continue;
				}
				if (!environment) continue;
				Matcher m = Pattern.compile("^\"(TEMP|TMP)\"=\"(.*)\"$").matcher(t);
				if (m.matches()) {
					Path p = winePath(prefix, m.group(2).replace("\\\\", "\\"));
					if (p != null) return p;
				}
			}
		} catch (Exception e) {
			LOG.warn("reading Wine's TEMP from {}: {}", prefix.resolve("user.reg"), e.toString());
		}
		return null;
	}

	/** Where ULTRAKILL's temp would be if Wine's own record can't be read: the usual place, then anywhere. */
	private static Path firstExisting(Path prefix) {
		List<Path> tries = new ArrayList<>();
		tries.add(prefix.resolve("drive_c/users/steamuser/AppData/Local/Temp"));
		String user = System.getProperty("user.name", "");
		if (!user.isBlank()) tries.add(prefix.resolve("drive_c/users/" + user + "/AppData/Local/Temp"));
		try (Stream<Path> s = Files.list(prefix.resolve("drive_c/users"))) {
			for (Path u : (Iterable<Path>) s::iterator) tries.add(u.resolve("AppData/Local/Temp"));
		} catch (Exception ignored) {
		}
		tries.add(prefix.resolve("drive_c/windows/temp"));
		for (Path p : tries) if (Files.isDirectory(p)) return p;
		return null;
	}

	/** A Windows path as it is on this side of the prefix (c:/users/me → &lt;prefix&gt;/drive_c/users/me, z:/tmp → /tmp). */
	private static Path winePath(Path prefix, String path) {
		String p = path.trim();
		if (p.startsWith("/")) return Files.isDirectory(Path.of(p)) || Files.exists(Path.of(p)) ? Path.of(p) : null;
		Matcher m = Pattern.compile("^([A-Za-z]):[\\\\/](.*)$").matcher(p);
		if (!m.matches()) return null;
		String rest = m.group(2).replace('\\', '/');
		String letter = m.group(1).toLowerCase(Locale.ROOT);
		if (letter.equals("c")) return prefix.resolve("drive_c").resolve(rest);
		// the other drives are whichever they are mapped to (Z: is the whole filesystem under Proton)
		Path drive = prefix.resolve("dosdevices").resolve(letter + ":");
		return Files.exists(drive) ? drive.resolve(rest) : null;
	}
}
