package com.trolmastercard.sexmod.util

/**
 * <b>Role.</b> Tribe activity state: `REST` (idle) vs `ACTIVE`
 * (working/fighting). Stored on [KoboldManager.Tribe] and consumed by the
 * kobold AI and the tribe UI.
 */
enum class TribeState {
    ACTIVE,
    REST,
}
