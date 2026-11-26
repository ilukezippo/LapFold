package com.ilukezippo.lapfold

enum class ModState { OFF, ONESHOT, LOCKED }

/** Sticky modifiers: tap = next key only, tap again = locked, tap again = off. */
class Modifiers {
    private val states = mutableMapOf<Mod, ModState>()
    var caps = false

    fun state(m: Mod) = states[m] ?: ModState.OFF
    fun isActive(m: Mod) = state(m) != ModState.OFF

    fun tap(m: Mod) {
        states[m] = when (state(m)) {
            ModState.OFF -> ModState.ONESHOT
            ModState.ONESHOT -> ModState.LOCKED
            ModState.LOCKED -> ModState.OFF
        }
    }

    fun metaState(): Int = Mod.entries.filter { isActive(it) }.fold(0) { acc, m -> acc or m.meta }

    fun consumeOneShots() {
        for (m in Mod.entries) if (state(m) == ModState.ONESHOT) states[m] = ModState.OFF
    }

    fun clear() { states.clear(); caps = false }
}
