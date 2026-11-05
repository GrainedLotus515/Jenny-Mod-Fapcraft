package com.trolmastercard.sexmod.client.renderer.api

import com.trolmastercard.sexmod.entity.BodyParts
import software.bernie.geckolib.cache.`object`.GeoBone

/**
 * Contract implemented by girl renderers for geckolib bone visibility control.
 * Custom parts (dildos, etc.) are attached to [BodyParts.CUSTOM_PART_BONES];
 * the renderer must not render a bone (nor its children) when that bone — or an
 * `armor*` ancestor — is blacklisted.
 *
 * CLIENT-side render thread only.
 */
interface IGirlRenderer {
    /**
     * The set of bones that must never be rendered (custom part anchor bones).
     */
    fun getBlacklistedBones(): HashSet<String> = BodyParts.CUSTOM_PART_BONES

    /**
     * Walks `bone`'s parent chain up to the root.
     *
     * @return `false` if any ancestor is a blacklisted custom-part bone or
     *         an armor bone; `true` otherwise (bone may be rendered)
     */
    fun hasParentBone(blacklistedBones: HashSet<String>, bone: GeoBone): Boolean {
        var current = bone
        while (current.parent != null) {
            val boneName = current.name
            if (blacklistedBones.contains(boneName)) {
                return false
            }

            if (boneName.startsWith("armor")) {
                return false
            }

            current = current.parent
        }

        return true
    }
}
