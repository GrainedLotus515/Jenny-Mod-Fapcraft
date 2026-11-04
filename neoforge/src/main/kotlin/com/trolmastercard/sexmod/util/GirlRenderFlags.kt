package com.trolmastercard.sexmod.util

/**
 * **Role.** Per-frame render flags shared between the girl renderers and the
 * scene camera code. The `flag*` booleans are written by renderers and
 * read by `GirlRenderer` layers to decide what to draw (hair, clothing,
 * extra parts). The single [instance] is reassigned each render frame —
 * do not cache it across frames.
 */
class GirlRenderFlags(
    @JvmField var flagC: Boolean,
    @JvmField var flagD: Boolean,
    @JvmField var flagB: Boolean,
) {
    companion object {
        @JvmField
        var instance: GirlRenderFlags? = null
    }
}
