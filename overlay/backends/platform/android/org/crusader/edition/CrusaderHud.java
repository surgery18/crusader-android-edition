package org.crusader.edition;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * On-screen controls for Crusader, drawn on top of the game surface.
 *
 * Layout (landscape): the game is 4:3, so a phone shows black bars at the
 * sides. The movement stick lives in the left bar and the combat buttons in
 * the right bar, so they cover as little of the picture as possible.
 *
 *   left:  analog stick (8 directions, push to the rim to run)
 *          MENU / SAVE / LOAD / KEYBOARD along the top
 *   right: FIRE (hold), ROLL, CROUCH, USE, SEARCH
 *          weapon / item / use item / medikit column, HUD hide toggle
 *
 * Touches that miss every control fall through to the game as mouse clicks
 * (menus, inventory, dialogs), because this view returns false for them.
 *
 * Every control sends a keymapper action *name* through {@link Sink}; the
 * native side resolves it against the engine's keymap.
 */
public class CrusaderHud extends View {

	public interface Sink {
		void action(String id, boolean down);
		void releaseAll();
		void toggleKeyboard();
		/** Press and release a real key, exactly like a hardware keyboard. */
		void key(int androidKeyCode);
	}

	private static final int KIND_HOLD = 0;   // down while finger is down
	private static final int KIND_TAP = 1;    // press + release on touch down
	private static final int KIND_KEYBOARD = 2;
	private static final int KIND_HIDE = 3;
	// A real Esc key: movies, menus and dialogs listen for the key itself
	// rather than for the MENU action (in game it still maps to MENU).
	private static final int KIND_ESC = 4;

	private final class Button {
		final String label;
		final String action;
		final int kind;
		final float radiusDp;
		float cx, cy, r;
		int pointer = -1;

		Button(String label, String action, int kind, float radiusDp) {
			this.label = label;
			this.action = action;
			this.kind = kind;
			this.radiusDp = radiusDp;
		}

		boolean hit(float x, float y) {
			float dx = x - cx, dy = y - cy;
			float slop = r * 1.2f;
			return dx * dx + dy * dy <= slop * slop;
		}
	}

	private final Sink sink;
	private final float dp;
	private final List<Button> buttons = new ArrayList<>();
	private final Button fire, roll, crouch, use, search;
	private final Button weapon, item, useItem, medikit;
	private final Button menu, save, load, keyboard, hide;

	// Stick state
	private float stickHomeX, stickHomeY, stickR;
	private float stickX, stickY;      // current base (floats to where the thumb lands)
	private float knobX, knobY;
	private int stickPointer = -1;
	private final RectF stickZone = new RectF();
	private boolean up, down, left, right, run;

	private Runnable escLongPress;
	private boolean escLongFired;

	private boolean hidden;
	private boolean haptics;
	private boolean lefty;
	private float scale;
	private int alpha;
	private final int accent;

	private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
	private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

	public CrusaderHud(Context ctx, Sink sink, int accentColor) {
		super(ctx);
		this.sink = sink;
		this.accent = accentColor;
		dp = ctx.getResources().getDisplayMetrics().density;

		fire = add("FIRE", "ATTACK", KIND_HOLD, 46);
		roll = add("ROLL", "JUMP", KIND_HOLD, 31);
		crouch = add("DUCK", "TOGGLE_CROUCH", KIND_TAP, 27);
		use = add("USE", "USE_SELECTION", KIND_TAP, 29);
		search = add("FIND", "SELECT_ITEM", KIND_TAP, 27);

		weapon = add("WPN", "NEXT_WEAPON", KIND_TAP, 22);
		item = add("ITEM", "NEXT_INVENTORY", KIND_TAP, 22);
		useItem = add("USE\nITEM", "USE_INVENTORY", KIND_TAP, 22);
		medikit = add("MED", "USE_MEDIKIT", KIND_TAP, 22);

		menu = add("MENU", null, KIND_ESC, 20);
		save = add("SAVE", "SAVE", KIND_TAP, 20);
		load = add("LOAD", "LOAD", KIND_TAP, 20);
		keyboard = add("KEYS", null, KIND_KEYBOARD, 20);
		hide = add("HUD", null, KIND_HIDE, 18);

		text.setColor(Color.WHITE);
		text.setTextAlign(Paint.Align.CENTER);
		text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
		rim.setStyle(Paint.Style.STROKE);

		reloadSettings();
	}

	private Button add(String label, String action, int kind, float radiusDp) {
		Button b = new Button(label, action, kind, radiusDp);
		buttons.add(b);
		return b;
	}

	public void reloadSettings() {
		SharedPreferences p = EngineConfig.prefs(getContext());
		haptics = p.getBoolean(EngineConfig.HAPTICS, true);
		lefty = p.getBoolean(EngineConfig.HUD_LEFTY, false);
		scale = p.getInt(EngineConfig.HUD_SCALE, 100) / 100f;
		alpha = Math.round(255 * p.getInt(EngineConfig.HUD_OPACITY, 55) / 100f);
		hidden = !p.getBoolean(EngineConfig.HUD_ENABLED, true);
		requestLayout();
		invalidate();
	}

	@Override
	protected void onSizeChanged(int w, int h, int oldw, int oldh) {
		super.onSizeChanged(w, h, oldw, oldh);
		layoutControls(w, h);
	}

	private void layoutControls(int w, int h) {
		for (Button b : buttons) {
			b.r = b.radiusDp * dp * scale;
		}
		float m = 18 * dp;
		// Width of the black bar beside a 4:3 picture (0 on 4:3 tablets).
		float bar = Math.max(0, (w - h * 4f / 3f) / 2f);

		// --- Movement side -------------------------------------------------
		stickR = 62 * dp * scale;
		float sx = Math.max(m + stickR * 1.15f, bar / 2f);
		float sy = h - m - stickR * 1.25f;
		// --- Action side ----------------------------------------------------
		float fr = fire.r;
		float fx = w - Math.max(m + fr * 1.1f, bar / 2f - fr * 0.6f);
		float fy = h - m - fr * 1.2f;

		if (lefty) {
			sx = w - sx;
			fx = w - fx;
		}
		float dir = lefty ? -1 : 1; // ring opens towards the screen centre

		stickHomeX = sx;
		stickHomeY = sy;
		stickX = sx;
		stickY = sy;
		knobX = sx;
		knobY = sy;
		// Thumb can land anywhere in the lower part of the movement side.
		float zoneW = Math.max(bar, stickR * 2.6f);
		if (!lefty) {
			stickZone.set(0, h * 0.30f, zoneW, h);
		} else {
			stickZone.set(w - zoneW, h * 0.30f, w, h);
		}

		fire.cx = fx;
		fire.cy = fy;
		// Inner ring (left, up-left, straight up) and FIND further out
		// between DUCK and USE. Nothing goes right of FIRE: that is the edge.
		place(roll, fx, fy, fr * 2.15f, 180, dir);
		place(crouch, fx, fy, fr * 2.15f, 225, dir);
		place(use, fx, fy, fr * 2.15f, 270, dir);
		place(search, fx, fy, fr * 3.5f, 247.5f, dir);

		// Inventory as a 2x2 grid in the top corner of the action side.
		float ir = weapon.r;
		float gx1 = lefty ? m + ir : w - m - ir;           // outer column
		float gx2 = gx1 - ir * 2.5f * dir;                  // inner column
		float gy1 = m + hide.r * 2 + ir * 1.6f;
		float gy2 = gy1 + ir * 2.4f;
		weapon.cx = gx2;  weapon.cy = gy1;
		item.cx = gx1;    item.cy = gy1;
		useItem.cx = gx2; useItem.cy = gy2;
		medikit.cx = gx1; medikit.cy = gy2;
		// System row along the top of the movement side.
		float topY = m + menu.r;
		float sysX = lefty ? w - m - menu.r : m + menu.r;
		float sysStep = menu.r * 2.5f * dir;
		menu.cx = sysX;                 menu.cy = topY;
		save.cx = sysX + sysStep;       save.cy = topY;
		load.cx = sysX + sysStep * 2;   load.cy = topY;
		keyboard.cx = sysX;             keyboard.cy = topY + menu.r * 2.5f;

		// Short (phone) screens: no room above FIND / USE, so the grid moves to
		// the movement side, between KEYS and the stick, inside the black bar.
		float clearance = Math.min(search.cy - search.r, use.cy - use.r) - (gy2 + ir) - 6 * dp;
		if (clearance < 0) {
			float lx1 = sysX;
			float lx2 = sysX + ir * 2.5f * dir;
			float ly1 = keyboard.cy + keyboard.r + ir * 1.5f;
			float ly2 = ly1 + ir * 2.4f;
			// ...but never over the resting stick.
			float over = (ly2 + ir) - (stickHomeY - stickR - 6 * dp);
			if (over > 0) {
				ly1 -= over;
				ly2 -= over;
			}
			weapon.cx = lx1;  weapon.cy = ly1;
			item.cx = lx2;    item.cy = ly1;
			useItem.cx = lx1; useItem.cy = ly2;
			medikit.cx = lx2; medikit.cy = ly2;
		}
		// HUD toggle in the top corner of the action side.
		hide.cx = lefty ? m + hide.r : w - m - hide.r;
		hide.cy = m + hide.r;
	}

	private static void place(Button b, float cx, float cy, float dist, float angleDeg, float dir) {
		double a = Math.toRadians(angleDeg);
		b.cx = cx + (float) Math.cos(a) * dist * dir;
		b.cy = cy + (float) Math.sin(a) * dist;
	}

	// ------------------------------------------------------------------ touch

	@SuppressLint("ClickableViewAccessibility")
	@Override
	public boolean onTouchEvent(MotionEvent e) {
		int action = e.getActionMasked();
		int idx = e.getActionIndex();
		int id = e.getPointerId(idx);

		switch (action) {
		case MotionEvent.ACTION_DOWN:
		case MotionEvent.ACTION_POINTER_DOWN: {
			boolean took = pointerDown(id, e.getX(idx), e.getY(idx));
			// For the first finger, returning false lets the touch reach the
			// game as a mouse click.
			if (action == MotionEvent.ACTION_DOWN) {
				return took;
			}
			return true;
		}
		case MotionEvent.ACTION_MOVE:
			for (int i = 0; i < e.getPointerCount(); i++) {
				if (e.getPointerId(i) == stickPointer) {
					moveStick(e.getX(i), e.getY(i));
				}
			}
			return true;
		case MotionEvent.ACTION_UP:
		case MotionEvent.ACTION_POINTER_UP:
			pointerUp(id);
			return true;
		case MotionEvent.ACTION_CANCEL:
			releaseEverything();
			return true;
		default:
			return true;
		}
	}

	private boolean pointerDown(int id, float x, float y) {
		if (hidden) {
			if (hide.hit(x, y)) {
				setHidden(false);
				buzz();
				return true;
			}
			return false;
		}
		for (Button b : buttons) {
			if (b.pointer == -1 && b.hit(x, y)) {
				b.pointer = id;
				buzz();
				switch (b.kind) {
				case KIND_HOLD:
					sink.action(b.action, true);
					break;
				case KIND_TAP:
					sink.action(b.action, true);
					sink.action(b.action, false);
					break;
				case KIND_KEYBOARD:
					sink.toggleKeyboard();
					break;
				case KIND_ESC:
					// Tap = Esc (skip movie, game menu). Hold = ScummVM's own
					// menu (save/load/options/quit), which gesture-navigation
					// phones cannot open with a long Back press.
					escLongFired = false;
					final Button held = b;
					escLongPress = () -> {
						if (held.pointer != -1) {
							escLongFired = true;
							buzz();
							sink.key(android.view.KeyEvent.KEYCODE_MENU);
						}
					};
					postDelayed(escLongPress, 550);
					break;
				case KIND_HIDE:
					setHidden(true);
					break;
				}
				invalidate();
				return true;
			}
		}
		if (stickPointer == -1 && stickZone.contains(x, y)) {
			stickPointer = id;
			// Floating stick: re-centre under the thumb, but keep the whole
			// base on screen.
			stickX = clamp(x, stickR, getWidth() - stickR);
			stickY = clamp(y, stickR, getHeight() - stickR);
			moveStick(x, y);
			return true;
		}
		return false;
	}

	private void pointerUp(int id) {
		if (id == stickPointer) {
			stickPointer = -1;
			stickX = stickHomeX;
			stickY = stickHomeY;
			knobX = stickX;
			knobY = stickY;
			setDirs(false, false, false, false, false);
			invalidate();
			return;
		}
		for (Button b : buttons) {
			if (b.pointer == id) {
				b.pointer = -1;
				if (b.kind == KIND_HOLD) {
					sink.action(b.action, false);
				} else if (b.kind == KIND_ESC) {
					removeCallbacks(escLongPress);
					if (!escLongFired) {
						sink.key(android.view.KeyEvent.KEYCODE_ESCAPE);
					}
				}
				invalidate();
			}
		}
	}

	private void moveStick(float x, float y) {
		float dx = x - stickX, dy = y - stickY;
		float dist = (float) Math.hypot(dx, dy);
		float max = stickR;
		if (dist > max) {
			knobX = stickX + dx / dist * max;
			knobY = stickY + dy / dist * max;
		} else {
			knobX = x;
			knobY = y;
		}
		float mag = dist / max;
		if (mag < 0.28f) {
			setDirs(false, false, false, false, false);
		} else {
			// 8 sectors of 45 degrees; screen y grows downwards.
			double ang = Math.toDegrees(Math.atan2(-dy, dx));
			if (ang < 0) {
				ang += 360;
			}
			int sector = (int) Math.round(ang / 45.0) % 8;
			boolean r = sector == 0 || sector == 1 || sector == 7;
			boolean u = sector == 1 || sector == 2 || sector == 3;
			boolean l = sector == 3 || sector == 4 || sector == 5;
			boolean d = sector == 5 || sector == 6 || sector == 7;
			setDirs(u, d, l, r, mag > 0.86f);
		}
		invalidate();
	}

	private void setDirs(boolean u, boolean d, boolean l, boolean r, boolean rn) {
		// Run first so the first step is already a run.
		if (rn != run) {
			run = rn;
			sink.action("MOVE_RUN", rn);
		}
		if (u != up) {
			up = u;
			sink.action("MOVE_UP", u);
		}
		if (d != down) {
			down = d;
			sink.action("MOVE_DOWN", d);
		}
		if (l != left) {
			left = l;
			sink.action("MOVE_LEFT", l);
		}
		if (r != right) {
			right = r;
			sink.action("MOVE_RIGHT", r);
		}
	}

	public void releaseEverything() {
		stickPointer = -1;
		stickX = stickHomeX;
		stickY = stickHomeY;
		knobX = stickX;
		knobY = stickY;
		up = down = left = right = run = false;
		for (Button b : buttons) {
			b.pointer = -1;
		}
		sink.releaseAll();
		invalidate();
	}

	private void setHidden(boolean h) {
		if (h) {
			releaseEverything();
		}
		hidden = h;
		EngineConfig.prefs(getContext()).edit().putBoolean(EngineConfig.HUD_ENABLED, !h).apply();
		invalidate();
	}

	private void buzz() {
		if (haptics) {
			performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
		}
	}

	@Override
	public void onWindowFocusChanged(boolean hasWindowFocus) {
		super.onWindowFocusChanged(hasWindowFocus);
		if (!hasWindowFocus) {
			releaseEverything();
		}
	}

	private static float clamp(float v, float lo, float hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	// ------------------------------------------------------------------ draw

	@Override
	protected void onDraw(Canvas c) {
		if (hidden) {
			drawButton(c, hide, 0.6f);
			return;
		}
		// Stick
		fill.setColor(Color.argb(alpha * 40 / 100, 0, 0, 0));
		c.drawCircle(stickX, stickY, stickR, fill);
		rim.setStrokeWidth(2 * dp);
		rim.setColor(withAlpha(Color.WHITE, alpha * 55 / 100));
		c.drawCircle(stickX, stickY, stickR, rim);
		// Run ring
		rim.setColor(withAlpha(run ? accent : Color.WHITE, run ? alpha : alpha * 25 / 100));
		rim.setStrokeWidth((run ? 4 : 1.5f) * dp);
		c.drawCircle(stickX, stickY, stickR * 0.86f, rim);
		fill.setColor(withAlpha(stickPointer != -1 ? accent : Color.WHITE, alpha * 80 / 100));
		c.drawCircle(knobX, knobY, stickR * 0.42f, fill);

		for (Button b : buttons) {
			drawButton(c, b, 1f);
		}
	}

	private void drawButton(Canvas c, Button b, float fade) {
		int a = Math.round(alpha * fade);
		boolean pressed = b.pointer != -1;
		fill.setColor(pressed ? withAlpha(accent, Math.min(255, a + 40)) : Color.argb(a * 55 / 100, 10, 10, 14));
		c.drawCircle(b.cx, b.cy, b.r, fill);
		rim.setStrokeWidth((b == fire ? 3 : 2) * dp);
		rim.setColor(withAlpha(b == fire ? accent : Color.WHITE, b == fire ? a : a * 70 / 100));
		c.drawCircle(b.cx, b.cy, b.r, rim);

		text.setColor(withAlpha(Color.WHITE, Math.min(255, a + 60)));
		String[] lines = b.label.split("\n");
		float size = (b == fire ? 15 : (lines.length > 1 ? 9 : 11)) * dp * scale;
		text.setTextSize(size);
		float total = size * lines.length * 1.05f;
		float y = b.cy - total / 2f + size * 0.85f;
		for (String line : lines) {
			c.drawText(line, b.cx, y, text);
			y += size * 1.05f;
		}
	}

	private static int withAlpha(int color, int a) {
		return Color.argb(Math.max(0, Math.min(255, a)), Color.red(color), Color.green(color), Color.blue(color));
	}
}
