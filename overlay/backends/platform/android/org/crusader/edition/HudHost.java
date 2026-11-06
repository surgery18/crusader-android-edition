package org.crusader.edition;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Build;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import org.scummvm.scummvm.ScummVM;
import org.scummvm.scummvm.ScummVMActivity;

/**
 * The only thing ScummVMActivity knows about this project: it calls
 * {@link #attach} from onCreate and {@link #onPause} from onPause.
 * Keeping the hook this small keeps the patch against upstream ScummVM small.
 */
public final class HudHost {

	public static final String EXTRA_HUD = "org.crusader.edition.HUD";
	public static final String EXTRA_ACCENT = "org.crusader.edition.ACCENT";

	private HudHost() {}

	/** @return the HUD, or null when the activity was not started by our launcher. */
	public static CrusaderHud attach(final ScummVMActivity activity, ViewGroup videoLayout, Intent intent) {
		if (intent == null || !intent.getBooleanExtra(EXTRA_HUD, false)) {
			return null;
		}
		tunePhone(activity);

		int accent = intent.getIntExtra(EXTRA_ACCENT, 0xFFC62828);
		CrusaderHud hud = new CrusaderHud(activity, new CrusaderHud.Sink() {
			@Override
			public void action(String id, boolean down) {
				ScummVM.pushEngineAction(id, down);
			}

			@Override
			public void releaseAll() {
				ScummVM.releaseEngineActions();
			}

			@Override
			public void toggleKeyboard() {
				activity.toggleScreenKeyboard();
			}

			@Override
			public void key(int code) {
				long t = android.os.SystemClock.uptimeMillis();
				activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
				activity.dispatchKeyEvent(new KeyEvent(t, t + 40, KeyEvent.ACTION_UP, code, 0));
			}
		}, accent);
		videoLayout.addView(hud, new FrameLayout.LayoutParams(
			ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
		return hud;
	}

	public static void onPause(CrusaderHud hud) {
		if (hud != null) {
			hud.releaseEverything();
		}
	}

	public static void onResume(CrusaderHud hud) {
		if (hud != null) {
			hud.reloadSettings();
		}
	}

	/** Phone specific tweaks: landscape, edge to edge, steady clocks. */
	private static void tunePhone(Activity a) {
		a.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
		Window w = a.getWindow();
		w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		if (Build.VERSION.SDK_INT >= 28) {
			WindowManager.LayoutParams lp = w.getAttributes();
			// Use the area beside the camera hole too: the HUD lives there.
			lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
			w.setAttributes(lp);
		}
		if (Build.VERSION.SDK_INT >= 24) {
			// Ask for stable (not boosted-then-throttled) CPU/GPU clocks where
			// the phone supports it. Old 2D games need steady, not peak, speed.
			try {
				w.setSustainedPerformanceMode(true);
			} catch (RuntimeException ignored) {
				// Not supported on this device.
			}
		}
		if (Build.VERSION.SDK_INT >= 30) {
			// Ask for 60 Hz: the engine runs far below that and a 120 Hz
			// panel only burns battery.
			WindowManager.LayoutParams lp = w.getAttributes();
			lp.preferredRefreshRate = 60f;
			w.setAttributes(lp);
		}
	}
}
