package com.trolmastercard.sexmod.util

import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent

/**
 * Legacy update checker — no longer used (kept for reference). CLIENT-ONLY.
 *
 * Port note: the empty tick handler stays because `EventBus.register` throws
 * for objects without `@SubscribeEvent` methods.
 */
open class DeprecatedCheckForUpdates {
    @SubscribeEvent
    open fun onClientTick(event: ClientTickEvent.Post) {
    }
}
