package org.crusader.edition;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Player settings (SharedPreferences) and the scummvm.ini they are turned into.
 *
 * ScummVMActivity reads its config from getFilesDir()/scummvm.ini. Before a
 * game starts, the launcher rewrites the [scummvm] keys and the game's own
 * section, leaving every other key the engine stored there (last save slot,
 * keymaps, volumes, ...) untouched.
 */
public final class EngineConfig {

	public static final String PREFS = "crusader_android_edition";

	// HUD
	public static final String HUD_ENABLED = "hud_enabled";
	public static final String HUD_OPACITY = "hud_opacity";   // 20..100
	public static final String HUD_SCALE = "hud_scale";       // 70..150
	public static final String HUD_LEFTY = "hud_lefty";
	public static final String HAPTICS = "haptics";
	// Engine / display
	public static final String SMOOTH = "smooth";
	public static final String HIRES = "hires";
	public static final String STRETCH = "stretch";           // fit | stretch | pixel-perfect
	public static final String CAMERA_FOLLOW = "camera_follow";
	public static final String SKIP_INTRO = "skip_intro";
	public static final String BATTERY_SAVER = "battery_saver";

	private EngineConfig() {}

	public static SharedPreferences prefs(Context ctx) {
		return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}

	public static File iniFile(Context ctx) {
		return new File(ctx.getFilesDir(), "scummvm.ini");
	}

	/** Write the config ScummVM needs to boot straight into this game. */
	public static void prepare(Context ctx, GameLibrary.Game game) throws IOException {
		SharedPreferences p = prefs(ctx);
		File f = iniFile(ctx);
		Map<String, Map<String, String>> ini = read(f);

		Map<String, String> g = section(ini, "scummvm");
		g.put("savepath", GameLibrary.savesDir(ctx).getAbsolutePath());
		// Taps on the playfield and in menus act as a direct mouse click.
		g.put("touch_mode_menus", "mouse");
		g.put("touch_mode_2d_games", "mouse");
		g.put("touch_mode_3d_games", "mouse");
		// Our HUD replaces ScummVM's own floating buttons.
		g.put("onscreen_control", "false");
		g.put("confirm_exit", "false");
		// ScummVM's "file access changed" notice is about its own game browser,
		// which this app never shows.
		g.put("android_saf_dialog_shown", "true");
		// No Regret v1.06 is flagged "unstable" upstream; the launcher already
		// told the player which version they have, so skip the nag dialog.
		g.put("enable_unsupported_game_warning", "false");
		// Patched base/main.cpp: when the game ends (or fails), exit back to
		// this launcher instead of ScummVM's own game list.
		g.put("cae_single_game", "true");
		g.put("gui_return_to_launcher_at_exit", "false");
		g.put("autosave_period", "300");
		g.put("aspect_ratio", "true");
		g.put("filtering", String.valueOf(p.getBoolean(SMOOTH, true)));
		g.put("stretch_mode", p.getString(STRETCH, "fit"));
		g.put("fullscreen", "true");
		// The HUD is designed for landscape; don't let the engine rotate.
		g.put("orientation_menus", "landscape");
		g.put("orientation_games", "landscape");

		Map<String, String> s = section(ini, game.kind.target());
		s.put("engineid", "ultima");
		s.put("gameid", game.kind.gameId);
		s.put("description", game.kind.title);
		s.put("path", game.dir.getAbsolutePath());
		s.put("platform", "pc");
		s.put("usehighres", String.valueOf(p.getBoolean(HIRES, false)));
		s.put("camera_on_player", String.valueOf(p.getBoolean(CAMERA_FOLLOW, true)));
		s.put("skip_intro", String.valueOf(p.getBoolean(SKIP_INTRO, false)));
		boolean saver = p.getBoolean(BATTERY_SAVER, false);
		// frameLimit caps the engine at its native speed instead of spinning;
		// frameSkip lets slow phones drop frames rather than slow the game down.
		s.put("frameLimit", "true");
		s.put("frameSkip", String.valueOf(saver));
		s.put("subtitles", "true");
		s.put("original_save_load", s.containsKey("original_save_load") ? s.get("original_save_load") : "false");

		write(f, ini);
	}

	private static Map<String, String> section(Map<String, Map<String, String>> ini, String name) {
		Map<String, String> s = ini.get(name);
		if (s == null) {
			s = new LinkedHashMap<>();
			ini.put(name, s);
		}
		return s;
	}

	static Map<String, Map<String, String>> read(File f) throws IOException {
		Map<String, Map<String, String>> ini = new LinkedHashMap<>();
		if (!f.exists()) {
			return ini;
		}
		Map<String, String> cur = null;
		try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				String t = line.trim();
				if (t.isEmpty() || t.startsWith("#")) {
					continue;
				}
				if (t.startsWith("[") && t.endsWith("]")) {
					cur = section(ini, t.substring(1, t.length() - 1));
				} else if (cur != null) {
					int eq = t.indexOf('=');
					if (eq > 0) {
						cur.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
					}
				}
			}
		}
		return ini;
	}

	static void write(File f, Map<String, Map<String, String>> ini) throws IOException {
		File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
		try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
			// ScummVM expects [scummvm] first.
			if (ini.containsKey("scummvm")) {
				writeSection(w, "scummvm", ini.get("scummvm"));
			}
			for (Map.Entry<String, Map<String, String>> e : ini.entrySet()) {
				if (!e.getKey().equals("scummvm")) {
					writeSection(w, e.getKey(), e.getValue());
				}
			}
		}
		if (!tmp.renameTo(f)) {
			throw new IOException("Could not write " + f);
		}
	}

	private static void writeSection(Writer w, String name, Map<String, String> keys) throws IOException {
		w.write("[" + name + "]\n");
		for (Map.Entry<String, String> k : keys.entrySet()) {
			w.write(k.getKey() + "=" + k.getValue() + "\n");
		}
		w.write("\n");
	}
}
