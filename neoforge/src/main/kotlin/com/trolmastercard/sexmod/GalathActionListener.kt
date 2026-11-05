package com.trolmastercard.sexmod

import com.trolmastercard.sexmod.entity.GalathEntity

/**
 * Callback contract for Galath's action flow. Invoked when Galath's current action
 * (e.g. the flight/petting sequence) must be stopped or cancelled.
 *
 * Implementations are registered by the code that drives [GalathEntity]'s
 * state machine (see the entity-side listeners); the entity calls `stop(...)`
 * when the action ends, so any hover/flight HUD or scene state held by the caller
 * can be released.
 */
fun interface GalathActionListener {
    /**
     * Signals that the action for the given Galath has ended and should be stopped.
     * CLIENT-side; invoked from the entity's update loop, so implementations must not
     * re-enter entity updates or throw.
     */
    fun stop(galath: GalathEntity)
}
