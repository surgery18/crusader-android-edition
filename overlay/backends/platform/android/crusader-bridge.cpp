/* Crusader: Android Edition - native side of the touch HUD.
 *
 * The Java HUD (org.crusader.edition.CrusaderHud) calls
 * ScummVM.pushEngineAction("ATTACK", true) and friends. Those land here,
 * get queued, and are resolved on the engine thread against the keymaps the
 * Crusader engine registered (see engines/ultima/ultima8/metaengine.cpp).
 * Resolving by action *name* instead of by enum value keeps the HUD working
 * even if the engine renumbers its actions.
 *
 * This file is part of Crusader: Android Edition and is distributed under the
 * terms of the GNU General Public License, version 3 or later.
 */

// Allow use of stuff in <time.h> and abort()
#define FORBIDDEN_SYMBOL_EXCEPTION_time_h
#define FORBIDDEN_SYMBOL_EXCEPTION_abort

// Disable printf override in common/forbidden.h to avoid
// clashes with log.h from the Android SDK.
#define FORBIDDEN_SYMBOL_EXCEPTION_printf

#include "backends/platform/android/android.h"
#include "backends/platform/android/crusader-bridge.h"

#include "backends/keymapper/action.h"
#include "backends/keymapper/keymap.h"
#include "backends/keymapper/keymapper.h"
#include "common/array.h"
#include "common/events.h"
#include "common/mutex.h"
#include "common/str.h"

namespace CrusaderBridge {

struct PendingAction {
	Common::String id;
	bool down;
	bool releaseAll;
};

static Common::Mutex *g_lock = nullptr;
static Common::Array<PendingAction> *g_pending = nullptr;
// Actions the engine currently believes are held, so we can release them all
// when the HUD is hidden or the app is paused (otherwise the Silencer keeps
// running into a wall forever).
static Common::Array<Common::String> *g_held = nullptr;

static void ensureInit() {
	// Called first from the UI thread, before the engine ever drains.
	if (!g_lock) {
		g_lock = new Common::Mutex();
		g_pending = new Common::Array<PendingAction>();
		g_held = new Common::Array<Common::String>();
	}
}

void queueAction(const char *actionId, bool down) {
	ensureInit();
	Common::StackLock l(*g_lock);
	PendingAction p;
	p.id = actionId;
	p.down = down;
	p.releaseAll = false;
	g_pending->push_back(p);
}

void releaseAll() {
	ensureInit();
	Common::StackLock l(*g_lock);
	PendingAction p;
	p.down = false;
	p.releaseAll = true;
	g_pending->push_back(p);
}

static bool findCustomType(const Common::String &id, Common::CustomEventType &out) {
	Common::Keymapper *km = g_system->getEventManager()->getKeymapper();
	if (!km)
		return false;

	const Common::KeymapArray &maps = km->getKeymaps();
	for (uint i = 0; i < maps.size(); i++) {
		Common::Keymap *map = maps[i];
		if (map->getType() != Common::Keymap::kKeymapTypeGame || !map->isEnabled())
			continue;
		const Common::Keymap::ActionArray &actions = map->getActions();
		for (uint j = 0; j < actions.size(); j++) {
			const Common::Action *a = actions[j];
			if (id.equals(a->id) && a->event.type == Common::EVENT_CUSTOM_ENGINE_ACTION_START) {
				out = a->event.customType;
				return true;
			}
		}
	}
	return false;
}

// Resolved events waiting to be handed to the dispatcher (engine thread only).
static Common::Array<Common::Event> *g_out = nullptr;

static void resolveOne(const Common::String &id, bool down) {
	Common::CustomEventType type;
	if (!findCustomType(id, type)) {
		LOGW("Crusader HUD: no engine action named %s", id.c_str());
		return;
	}

	Common::Event ev;
	ev.type = down ? Common::EVENT_CUSTOM_ENGINE_ACTION_START : Common::EVENT_CUSTOM_ENGINE_ACTION_END;
	ev.customType = type;
	g_out->push_back(ev);

	if (down) {
		bool already = false;
		for (uint i = 0; i < g_held->size(); i++)
			if ((*g_held)[i] == id)
				already = true;
		if (!already)
			g_held->push_back(id);
	} else {
		for (uint i = 0; i < g_held->size(); i++) {
			if ((*g_held)[i] == id) {
				g_held->remove_at(i);
				break;
			}
		}
	}
}

/**
 * The dispatcher asserts that sources which go through the keymapper never
 * emit engine actions themselves (those are the keymapper's output). The HUD
 * already speaks in engine actions, so it gets its own source that opts out
 * of mapping, the same way the keymapper's own output is delivered.
 */
class HudEventSource : public Common::EventSource {
public:
	bool allowMapping() const override { return false; }

	bool pollEvent(Common::Event &event) override {
		if (g_out->empty()) {
			Common::Array<PendingAction> work;
			{
				Common::StackLock l(*g_lock);
				if (g_pending->empty())
					return false;
				work = *g_pending;
				g_pending->clear();
			}
			for (uint i = 0; i < work.size(); i++) {
				if (work[i].releaseAll) {
					Common::Array<Common::String> held = *g_held;
					for (uint j = 0; j < held.size(); j++)
						resolveOne(held[j], false);
					g_held->clear();
				} else {
					resolveOne(work[i].id, work[i].down);
				}
			}
			if (g_out->empty())
				return false;
		}
		event = (*g_out)[0];
		g_out->remove_at(0);
		return true;
	}
};

static bool g_registered = false;

void drain(OSystem_Android *system) {
	// Called from every pollEvent on the engine thread; registers our event
	// source once the event manager exists.
	if (g_registered)
		return;
	Common::EventManager *em = system->getEventManager();
	if (!em)
		return;
	ensureInit();
	g_out = new Common::Array<Common::Event>();
	em->getEventDispatcher()->registerSource(new HudEventSource(), true);
	g_registered = true;
}

} // End of namespace CrusaderBridge
