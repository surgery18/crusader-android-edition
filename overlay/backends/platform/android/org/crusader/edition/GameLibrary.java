package org.crusader.edition;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Knows which Crusader games exist and finds the ones the player has installed.
 *
 * Game files live in the app's external files directory, which is reachable
 * over USB from a PC without any extra permission:
 *
 *   Android/data/&lt;package&gt;/files/games/remorse/   (Crusader: No Remorse)
 *   Android/data/&lt;package&gt;/files/games/regret/    (Crusader: No Regret)
 *
 * The folder names are only a convention: every sub-folder of games/ is
 * inspected and identified by its USECODE/EUSECODE.FLX, so a folder called
 * "Crusader No Remorse" copied straight from Steam or GOG works too.
 */
public final class GameLibrary {

	public enum Kind {
		REMORSE("remorse", "Crusader: No Remorse", "1995", "The Silencer turns on the WEC.", 0xFFC62828),
		REGRET("regret", "Crusader: No Regret", "1996", "The war goes to the Moon.", 0xFF1565C0);

		public final String gameId;
		public final String title;
		public final String year;
		public final String tagline;
		public final int accent;

		Kind(String gameId, String title, String year, String tagline, int accent) {
			this.gameId = gameId;
			this.title = title;
			this.year = year;
			this.tagline = tagline;
			this.accent = accent;
		}

		/** ScummVM target name used in scummvm.ini. */
		public String target() {
			return "cae-" + gameId;
		}
	}

	/** One detected installation. */
	public static final class Game {
		public final Kind kind;
		public final File dir;
		public final String version;
		/** FLICS (cutscene) folder present. */
		public final boolean hasMovies;
		/** A GAME.BIN / *.ISO disc image we could pull the movies from. */
		public final File discImage;

		Game(Kind kind, File dir, String version, boolean hasMovies, File discImage) {
			this.kind = kind;
			this.dir = dir;
			this.version = version;
			this.hasMovies = hasMovies;
			this.discImage = discImage;
		}
	}

	// MD5 of the first 5000 bytes of USECODE/EUSECODE.FLX -> (game, version).
	// Mirrors engines/ultima/detection_tables.h so the launcher only offers
	// games the engine will actually accept.
	private static final String[][] KNOWN = {
		{ "0a0f64507adc4f280129c735ee9cad42", "remorse", "v1.21 (GOG / Steam)" },
		{ "73b413b1ef291c4512f16c719ad746f3", "remorse", "v1.10 CD" },
		{ "3fb211f4adfd80595078afc85bdfe7b4", "remorse", "CD" },
		{ "8c74327e30088ce93f08a15a7f85b3ce", "remorse", "v1.01 (unpatched)" },
		{ "efbd33d6a5e8f14e9c57f963c3fbe939", "remorse", "French" },
		{ "36a16d70c97d0379f1133cc743c31313", "remorse", "Spanish" },
		{ "a8b5c421c5d74be8c69fcd4fecadd1dd", "remorse", "Spanish fan translation" },
		{ "1bb360156b7240a1f05eb9bda01c54db", "regret", "v1.06 (GOG / Steam)" },
		{ "1824d9725de45a8b49f058c12c6cf5c3", "regret", "German v1.07NV" },
		{ "f5906654047ed1dab75760da6426ecfa", "regret", "Spanish" },
	};

	private GameLibrary() {}

	public static File gamesRoot(Context ctx) {
		File base = ctx.getExternalFilesDir(null);
		if (base == null) {
			base = ctx.getFilesDir();
		}
		File root = new File(base, "games");
		//noinspection ResultOfMethodCallIgnored
		root.mkdirs();
		return root;
	}

	public static File savesDir(Context ctx) {
		File base = ctx.getExternalFilesDir(null);
		if (base == null) {
			base = ctx.getFilesDir();
		}
		File saves = new File(base, "saves");
		//noinspection ResultOfMethodCallIgnored
		saves.mkdirs();
		return saves;
	}

	/** Scan games/ and return at most one installation per game. */
	public static List<Game> scan(Context ctx) {
		List<Game> found = new ArrayList<>();
		File root = gamesRoot(ctx);
		File[] dirs = root.listFiles();
		if (dirs == null) {
			return found;
		}
		java.util.Arrays.sort(dirs);
		for (File d : dirs) {
			// Skip non-folders and hidden ones such as an interrupted ".importing".
			if (!d.isDirectory() || d.getName().startsWith(".")) {
				continue;
			}
			Game g = identify(d);
			if (g == null) {
				continue;
			}
			boolean dup = false;
			for (Game other : found) {
				if (other.kind == g.kind) {
					dup = true;
					break;
				}
			}
			if (!dup) {
				found.add(g);
			}
		}
		// Always list No Remorse before No Regret.
		java.util.Collections.sort(found, (a, b) -> a.kind.ordinal() - b.kind.ordinal());
		return found;
	}

	/** Identify a single folder, or null if it does not hold a Crusader game. */
	public static Game identify(File dir) {
		File usecode = findChild(findChild(dir, "USECODE"), "EUSECODE.FLX");
		if (usecode == null || !usecode.isFile()) {
			return null;
		}
		if (findChild(dir, "STATIC") == null) {
			return null;
		}

		Kind kind = null;
		String version = "unrecognised version";
		String md5 = md5Prefix(usecode, 5000);
		for (String[] k : KNOWN) {
			if (k[0].equals(md5)) {
				kind = k[1].equals("remorse") ? Kind.REMORSE : Kind.REGRET;
				version = k[2];
				break;
			}
		}
		if (kind == null) {
			// Unknown build: fall back to the executable name, the engine will
			// warn if it cannot run it.
			if (findChild(dir, "REGRET.EXE") != null) {
				kind = Kind.REGRET;
			} else if (findChild(dir, "CRUSADER.EXE") != null) {
				kind = Kind.REMORSE;
			} else {
				return null;
			}
		}

		File flics = findChild(dir, "FLICS");
		boolean hasMovies = false;
		if (flics != null && flics.isDirectory()) {
			String[] names = flics.list();
			if (names != null) {
				for (String n : names) {
					if (n.toUpperCase(Locale.ROOT).endsWith(".AVI")) {
						hasMovies = true;
						break;
					}
				}
			}
		}

		File disc = null;
		if (!hasMovies) {
			disc = findChild(dir, "GAME.BIN");
			if (disc == null) {
				File[] files = dir.listFiles();
				if (files != null) {
					for (File f : files) {
						String n = f.getName().toUpperCase(Locale.ROOT);
						if (f.isFile() && (n.endsWith(".ISO") || n.endsWith(".BIN")) && f.length() > 50L * 1024 * 1024) {
							disc = f;
							break;
						}
					}
				}
			}
		}
		return new Game(kind, dir, version, hasMovies, disc);
	}

	/** Case-insensitive child lookup (Steam ships upper case, GOG mixed). */
	public static File findChild(File dir, String name) {
		if (dir == null || !dir.isDirectory()) {
			return null;
		}
		File exact = new File(dir, name);
		if (exact.exists()) {
			return exact;
		}
		File[] files = dir.listFiles();
		if (files == null) {
			return null;
		}
		for (File f : files) {
			if (f.getName().equalsIgnoreCase(name)) {
				return f;
			}
		}
		return null;
	}

	static String md5Prefix(File f, int len) {
		try (FileInputStream in = new FileInputStream(f)) {
			MessageDigest md = MessageDigest.getInstance("MD5");
			byte[] buf = new byte[len];
			int total = 0;
			while (total < len) {
				int r = in.read(buf, total, len - total);
				if (r < 0) {
					break;
				}
				total += r;
			}
			md.update(buf, 0, total);
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest()) {
				sb.append(String.format(Locale.ROOT, "%02x", b));
			}
			return sb.toString();
		} catch (IOException | java.security.NoSuchAlgorithmException e) {
			return "";
		}
	}
}
