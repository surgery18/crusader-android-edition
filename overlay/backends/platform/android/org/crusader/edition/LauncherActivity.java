package org.crusader.edition;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.scummvm.scummvm.ScummVMActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;

/**
 * The "universal" Crusader launcher: shows a card for every Crusader game the
 * player has installed (one, the other, or both) and boots the chosen one
 * straight into the engine with the touch HUD enabled.
 */
public class LauncherActivity extends Activity {

	private static final int REQ_IMPORT = 4101;

	private static final int BG = 0xFF0B0C10;
	private static final int PANEL = 0xFF16181F;
	private static final int MUTED = 0xFF8A8F9C;
	private static final int RED = 0xFFC62828;

	private LinearLayout content;
	private final Handler ui = new Handler(Looper.getMainLooper());
	private volatile boolean cancelled;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		if (Build.VERSION.SDK_INT >= 28) {
			WindowManager.LayoutParams lp = getWindow().getAttributes();
			lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
			getWindow().setAttributes(lp);
		}
		getWindow().getDecorView().setBackgroundColor(BG);

		ScrollView scroll = new ScrollView(this);
		scroll.setFillViewport(true);
		content = new LinearLayout(this);
		content.setOrientation(LinearLayout.VERTICAL);
		content.setPadding(dp(28), dp(18), dp(28), dp(18));
		scroll.addView(content);
		setContentView(scroll);
	}

	@Override
	protected void onResume() {
		super.onResume();
		hideSystemUi();
		rebuild();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus) {
			hideSystemUi();
		}
	}

	@SuppressWarnings("deprecation")
	private void hideSystemUi() {
		getWindow().getDecorView().setSystemUiVisibility(
			View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
				| View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
				| View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
	}

	// ------------------------------------------------------------------- UI

	private void rebuild() {
		content.removeAllViews();
		content.addView(header());

		List<GameLibrary.Game> games = GameLibrary.scan(this);
		if (games.isEmpty()) {
			content.addView(emptyState());
		} else {
			LinearLayout row = new LinearLayout(this);
			row.setOrientation(LinearLayout.HORIZONTAL);
			for (GameLibrary.Game g : games) {
				row.addView(card(g));
			}
			HorizontalScrollView hs = new HorizontalScrollView(this);
			hs.setHorizontalScrollBarEnabled(false);
			hs.addView(row);
			content.addView(hs);
			if (games.size() == 1) {
				TextView hint = label("Own the other Crusader game too? Add it with IMPORT GAME and it will show up here.", 13, MUTED, false);
				hint.setPadding(0, dp(10), 0, 0);
				content.addView(hint);
			}
		}
		content.addView(footer());
	}

	private View header() {
		LinearLayout h = new LinearLayout(this);
		h.setOrientation(LinearLayout.VERTICAL);
		h.setPadding(0, 0, 0, dp(16));
		TextView title = label("CRUSADER", 40, Color.WHITE, true);
		title.setLetterSpacing(0.18f);
		title.setShadowLayer(dp(10), 0, 0, RED);
		TextView sub = label("ANDROID EDITION", 14, RED, true);
		sub.setLetterSpacing(0.55f);
		h.addView(title);
		h.addView(sub);
		return h;
	}

	private View card(final GameLibrary.Game g) {
		LinearLayout c = new LinearLayout(this);
		c.setOrientation(LinearLayout.VERTICAL);
		c.setPadding(dp(20), dp(16), dp(20), dp(16));
		GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
			new int[] { blend(g.kind.accent, PANEL, 0.80f), PANEL });
		bg.setCornerRadius(dp(14));
		bg.setStroke(dp(2), blend(g.kind.accent, PANEL, 0.35f));
		c.setBackground(bg);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
		lp.setMargins(0, 0, dp(18), 0);
		c.setLayoutParams(lp);

		String[] parts = g.kind.title.split(": ");
		TextView t1 = label(parts[0].toUpperCase(), 13, MUTED, true);
		t1.setLetterSpacing(0.3f);
		TextView t2 = label(parts.length > 1 ? parts[1] : g.kind.title, 28, Color.WHITE, true);
		TextView tag = label(g.kind.tagline, 14, 0xFFC9CCD4, false);
		TextView meta = label(g.kind.year + "  -  " + g.version, 12, MUTED, false);
		meta.setPadding(0, dp(6), 0, 0);
		c.addView(t1);
		c.addView(t2);
		c.addView(tag);
		c.addView(meta);

		TextView status;
		if (g.needsDiscMerge) {
			status = label("First PLAY unpacks " + g.discImage.getName() + " (cutscenes, music, levels)", 12, 0xFFFFB74D, false);
		} else if (!g.hasLevels) {
			status = label("Missing STATIC/FIXED.DAT: copy GAME.BIN into this game's folder", 12, 0xFFEF5350, false);
		} else if (g.hasMovies) {
			status = label("Ready", 12, 0xFF66BB6A, false);
		} else {
			status = label("Ready (no cutscenes found)", 12, 0xFFFFB74D, false);
		}
		status.setPadding(0, dp(2), 0, dp(12));
		c.addView(status);

		Button play = bigButton("PLAY", g.kind.accent);
		play.setEnabled(g.playable());
		play.setAlpha(g.playable() ? 1f : 0.4f);
		play.setOnClickListener(v -> {
			if (g.needsDiscMerge) {
				unpackDisc(g);
			} else {
				launch(g);
			}
		});
		c.addView(play);
		return c;
	}

	private View emptyState() {
		LinearLayout box = new LinearLayout(this);
		box.setOrientation(LinearLayout.VERTICAL);
		box.setPadding(dp(20), dp(18), dp(20), dp(18));
		GradientDrawable bg = new GradientDrawable();
		bg.setColor(PANEL);
		bg.setCornerRadius(dp(14));
		box.setBackground(bg);

		box.addView(label("No Crusader games installed yet", 20, Color.WHITE, true));
		TextView body = label(
			"This app does not include the games. You need your own copy of Crusader: No Remorse "
				+ "and/or Crusader: No Regret (Steam or GOG). Either game alone is fine.\n\n"
				+ "Option 1 - on the phone: copy the game folder from your PC to the phone (for example into Download), "
				+ "then tap IMPORT GAME and pick that folder.\n\n"
				+ "Option 2 - from a PC over USB: copy the game folder into\n" + GameLibrary.gamesRoot(this).getAbsolutePath()
				+ "\n(the folder name does not matter), or run the install script from the project README.",
			14, 0xFFC9CCD4, false);
		body.setPadding(0, dp(8), 0, 0);
		body.setTextIsSelectable(true);
		box.addView(body);
		return box;
	}

	private View footer() {
		LinearLayout f = new LinearLayout(this);
		f.setOrientation(LinearLayout.HORIZONTAL);
		f.setPadding(0, dp(18), 0, 0);
		f.setGravity(Gravity.CENTER_VERTICAL);

		Button imp = smallButton("IMPORT GAME");
		imp.setOnClickListener(v -> pickFolder());
		Button set = smallButton("SETTINGS");
		set.setOnClickListener(v -> showSettings());
		Button help = smallButton("CONTROLS");
		help.setOnClickListener(v -> showControls());
		Button refresh = smallButton("RESCAN");
		refresh.setOnClickListener(v -> rebuild());
		for (Button b : new Button[] { imp, set, help, refresh }) {
			LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
			lp.setMargins(0, 0, dp(10), 0);
			f.addView(b, lp);
		}
		TextView note = label("Fan project, not affiliated with EA or Origin Systems.  Engine: ScummVM (GPLv3).", 11, 0xFF5C6170, false);
		note.setPadding(dp(8), 0, 0, 0);
		f.addView(note);
		return f;
	}

	// --------------------------------------------------------------- actions

	private void launch(GameLibrary.Game g) {
		try {
			EngineConfig.prepare(this, g);
		} catch (IOException e) {
			Toast.makeText(this, "Could not write engine config: " + e.getMessage(), Toast.LENGTH_LONG).show();
			return;
		}
		Intent i = new Intent(this, ScummVMActivity.class);
		i.setData(Uri.parse("scummvm:" + g.kind.target()));
		i.putExtra(HudHost.EXTRA_HUD, true);
		i.putExtra(HudHost.EXTRA_ACCENT, g.kind.accent);
		startActivity(i);
	}

	private void pickFolder() {
		Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
		i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		try {
			startActivityForResult(i, REQ_IMPORT);
		} catch (Exception e) {
			Toast.makeText(this, "No folder picker available on this phone", Toast.LENGTH_LONG).show();
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) {
			return;
		}
		final Uri tree = data.getData();
		final ProgressDialogView pd = new ProgressDialogView("Importing");
		cancelled = false;
		new Thread(() -> {
			String msg;
			try {
				List<GameLibrary.Game> got = new GameImporter(this, tree, new GameImporter.Listener() {
					@Override
					public void onStatus(String text, int percent) {
						pd.update(text, percent);
					}

					@Override
					public boolean isCancelled() {
						return cancelled;
					}
				}).run();
				StringBuilder sb = new StringBuilder("Imported: ");
				for (GameLibrary.Game g : got) {
					sb.append("\n  ").append(g.kind.title).append(" (").append(g.version).append(")");
				}
				msg = sb.toString();
			} catch (IOException e) {
				msg = "Import failed: " + e.getMessage();
			}
			final String m = msg;
			ui.post(() -> {
				pd.dismiss();
				new AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK", null).show();
				rebuild();
			});
		}, "import").start();
	}

	/**
	 * One-time unpack of the Steam/GOG disc image into the game folder (only
	 * files the folder lacks), then start the game.
	 */
	private void unpackDisc(final GameLibrary.Game g) {
		final ProgressDialogView pd = new ProgressDialogView("Unpacking " + g.discImage.getName());
		cancelled = false;
		new Thread(() -> {
			String err = null;
			int n = 0;
			try (FileInputStream in = new FileInputStream(g.discImage)) {
				IsoExtractor iso = new IsoExtractor(in.getChannel());
				n = iso.mergeMissing(g.dir, (file, done, total) -> {
					pd.update(file, (int) (100 * done / Math.max(1, total)));
					return !cancelled;
				});
				//noinspection ResultOfMethodCallIgnored
				new File(g.dir, GameLibrary.MERGED_MARKER).createNewFile();
			} catch (IOException e) {
				err = e.getMessage();
			}
			final String fe = err;
			final int count = n;
			ui.post(() -> {
				pd.dismiss();
				rebuild();
				if (fe != null) {
					new AlertDialog.Builder(this).setMessage("Could not read " + g.discImage.getName() + ": " + fe)
						.setPositiveButton("OK", null).show();
					return;
				}
				final GameLibrary.Game fresh = GameLibrary.identify(g.dir);
				new AlertDialog.Builder(this)
					.setTitle("Unpacked " + count + " files")
					.setMessage(g.discImage.getName() + " is no longer needed. Delete it to free "
						+ (g.discImage.length() >> 20) + " MB?")
					.setPositiveButton("Delete and play", (d, w) -> {
						//noinspection ResultOfMethodCallIgnored
						g.discImage.delete();
						launch(fresh != null ? fresh : g);
					})
					.setNegativeButton("Keep and play", (d, w) -> launch(fresh != null ? fresh : g))
					.show();
			});
		}, "unpack").start();
	}

	private void showSettings() {
		final SharedPreferences p = EngineConfig.prefs(this);
		LinearLayout l = new LinearLayout(this);
		l.setOrientation(LinearLayout.VERTICAL);
		l.setPadding(dp(22), dp(10), dp(22), dp(4));

		l.addView(section("Touch controls"));
		l.addView(toggle("Show touch controls", p, EngineConfig.HUD_ENABLED, true));
		l.addView(toggle("Left-handed (stick on the right)", p, EngineConfig.HUD_LEFTY, false));
		l.addView(toggle("Vibrate on button press", p, EngineConfig.HAPTICS, true));
		l.addView(slider("Opacity", p, EngineConfig.HUD_OPACITY, 20, 100, 55, "%"));
		l.addView(slider("Size", p, EngineConfig.HUD_SCALE, 70, 150, 100, "%"));

		l.addView(section("Display & performance"));
		l.addView(toggle("Smooth scaling (off = sharp pixels)", p, EngineConfig.SMOOTH, true));
		l.addView(toggle("High resolution mode (1024x768, sharper, slower)", p, EngineConfig.HIRES, false));
		l.addView(toggle("Camera follows the Silencer", p, EngineConfig.CAMERA_FOLLOW, true));
		l.addView(toggle("Battery saver (allow frame skipping)", p, EngineConfig.BATTERY_SAVER, false));
		l.addView(toggle("Skip intro when continuing a save", p, EngineConfig.SKIP_INTRO, false));

		ScrollView sv = new ScrollView(this);
		sv.addView(l);
		new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
			.setTitle("Settings")
			.setView(sv)
			.setPositiveButton("Done", null)
			.show();
	}

	private void showControls() {
		String s = "LEFT STICK - move (screen directions). Push to the rim to run.\n"
			+ "FIRE - hold to shoot (draws your weapon).\n"
			+ "ROLL - hold with a direction to roll / jump.\n"
			+ "DUCK - crouch on / off.\n"
			+ "FIND - cycle through usable things nearby.  USE - use the selected thing (doors, switches, terminals).\n"
			+ "WPN / ITEM - cycle weapons and items.  USE ITEM - use the current item.  MED - use a medikit.\n"
			+ "MENU - tap to skip a cutscene or open the game menu; HOLD for the engine menu (save, load, quit to this launcher).\n"
			+ "SAVE / LOAD - save and load screens.  KEYS - on-screen keyboard (for naming saves).\n"
			+ "HUD - hide the controls (tap the faint HUD button to bring them back).\n\n"
			+ "Tap anywhere else on the picture to click (menus, inventory, dialogs).\n\n"
			+ "A Bluetooth game controller or keyboard also works.";
		new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
			.setTitle("Controls")
			.setMessage(s)
			.setPositiveButton("OK", null)
			.show();
	}

	// --------------------------------------------------------------- widgets

	/** Small modal progress dialog that can be updated from any thread. */
	private final class ProgressDialogView {
		final AlertDialog dialog;
		final TextView text;
		final ProgressBar bar;

		ProgressDialogView(String title) {
			LinearLayout l = new LinearLayout(LauncherActivity.this);
			l.setOrientation(LinearLayout.VERTICAL);
			l.setPadding(dp(22), dp(12), dp(22), dp(4));
			text = label("Starting...", 13, Color.WHITE, false);
			text.setSingleLine(true);
			text.setEllipsize(TextUtils.TruncateAt.MIDDLE);
			bar = new ProgressBar(LauncherActivity.this, null, android.R.attr.progressBarStyleHorizontal);
			bar.setMax(100);
			l.addView(text);
			l.addView(bar);
			dialog = new AlertDialog.Builder(LauncherActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
				.setTitle(title)
				.setView(l)
				.setCancelable(false)
				.setNegativeButton("Cancel", (d, w) -> cancelled = true)
				.show();
		}

		void update(final String s, final int pct) {
			ui.post(() -> {
				text.setText(s);
				bar.setProgress(pct);
			});
		}

		void dismiss() {
			if (dialog.isShowing()) {
				dialog.dismiss();
			}
		}
	}

	private TextView section(String s) {
		TextView t = label(s.toUpperCase(), 12, RED, true);
		t.setLetterSpacing(0.2f);
		t.setPadding(0, dp(14), 0, dp(4));
		return t;
	}

	private View toggle(String text, final SharedPreferences p, final String key, boolean def) {
		Switch sw = new Switch(this);
		sw.setText(text);
		sw.setTextColor(Color.WHITE);
		sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		sw.setChecked(p.getBoolean(key, def));
		sw.setPadding(0, dp(6), 0, dp(6));
		sw.setOnCheckedChangeListener((CompoundButton b, boolean on) -> p.edit().putBoolean(key, on).apply());
		return sw;
	}

	private View slider(final String name, final SharedPreferences p, final String key, final int min, int max, int def, final String unit) {
		LinearLayout l = new LinearLayout(this);
		l.setOrientation(LinearLayout.VERTICAL);
		final TextView t = label(name + ": " + p.getInt(key, def) + unit, 14, Color.WHITE, false);
		SeekBar sb = new SeekBar(this);
		sb.setMax(max - min);
		sb.setProgress(p.getInt(key, def) - min);
		sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar s, int v, boolean fromUser) {
				t.setText(name + ": " + (v + min) + unit);
				p.edit().putInt(key, v + min).apply();
			}

			@Override
			public void onStartTrackingTouch(SeekBar s) {}

			@Override
			public void onStopTrackingTouch(SeekBar s) {}
		});
		l.addView(t);
		l.addView(sb);
		l.setPadding(0, dp(6), 0, dp(2));
		return l;
	}

	private Button bigButton(String s, int color) {
		Button b = new Button(this);
		b.setText(s);
		b.setTextColor(Color.WHITE);
		b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
		b.setTypeface(Typeface.DEFAULT_BOLD);
		b.setLetterSpacing(0.3f);
		GradientDrawable d = new GradientDrawable();
		d.setColor(color);
		d.setCornerRadius(dp(10));
		b.setBackground(d);
		b.setPadding(0, dp(12), 0, dp(12));
		return b;
	}

	private Button smallButton(String s) {
		Button b = new Button(this);
		b.setText(s);
		b.setTextColor(Color.WHITE);
		b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
		b.setTypeface(Typeface.DEFAULT_BOLD);
		b.setLetterSpacing(0.12f);
		b.setAllCaps(true);
		GradientDrawable d = new GradientDrawable();
		d.setColor(0xFF22252E);
		d.setStroke(dp(1), 0xFF3A3F4B);
		d.setCornerRadius(dp(8));
		b.setBackground(d);
		b.setPadding(dp(16), dp(8), dp(16), dp(8));
		b.setMinHeight(0);
		b.setMinimumHeight(0);
		return b;
	}

	private TextView label(String s, int sp, int color, boolean bold) {
		TextView t = new TextView(this);
		t.setText(s);
		t.setTextColor(color);
		t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
		if (bold) {
			t.setTypeface(Typeface.DEFAULT_BOLD);
		}
		return t;
	}

	private int dp(float v) {
		return Math.round(v * getResources().getDisplayMetrics().density);
	}

	private static int blend(int a, int b, float t) {
		int r = Math.round(Color.red(a) * (1 - t) + Color.red(b) * t);
		int g = Math.round(Color.green(a) * (1 - t) + Color.green(b) * t);
		int bl = Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t);
		return Color.rgb(r, g, bl);
	}
}
