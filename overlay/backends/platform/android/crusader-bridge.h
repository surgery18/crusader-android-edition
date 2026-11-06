/* Crusader: Android Edition - native side of the touch HUD.
 *
 * This file is part of Crusader: Android Edition, a ScummVM-based Android
 * port of the Crusader engine. It is distributed under the terms of the GNU
 * General Public License, version 3 or (at your option) any later version.
 * See LICENSE in the repository root.
 */

#ifndef ANDROID_CRUSADER_BRIDGE_H_
#define ANDROID_CRUSADER_BRIDGE_H_

class OSystem_Android;

namespace CrusaderBridge {

/**
 * Queue a press or release of a keymapper action, by its action id
 * (e.g. "ATTACK", "MOVE_UP", "MENU"). Safe to call from any thread.
 */
void queueAction(const char *actionId, bool down);

/**
 * Release every action that is currently held. Safe to call from any thread.
 */
void releaseAll();

/**
 * Register the HUD's event source with the event dispatcher (once). The source
 * resolves queued actions against the active keymaps and delivers them as
 * custom engine action events. Must be called on the engine (main) thread.
 */
void drain(OSystem_Android *system);

} // End of namespace CrusaderBridge

#endif
