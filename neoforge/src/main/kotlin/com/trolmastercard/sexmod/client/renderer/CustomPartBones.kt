package com.trolmastercard.sexmod.client.renderer

import com.trolmastercard.sexmod.client.gui.ClothingScreen
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.proxy.ClientProxy
import com.trolmastercard.sexmod.util.ServerWhitelistManager

/**
 * Resolves which bones are currently provided by custom model parts, i.e. the
 * bones the stock model must not draw (see
 * [GirlRenderer.getActiveBones]).
 *
 * **Selection.** Bone names come from the [ClothingScreen] preview
 * (locally registered girls) or from the girl's own custom-part set; a part
 * contributes its bones when its model is disabled or the girl is dressed.
 * Empty in the preload world.
 *
 * CLIENT-side render thread only (reads client GUI / whitelist state).
 */
object CustomPartBones {
    /**
     * The set of bones currently replaced by custom parts.
     *
     * @param isPreview   choose bone names from the [ClothingScreen] preview
     * @param isNudeOutfit whether the girl is nude (parts still hide their bones)
     */
    @JvmStatic
    fun resolve(girl: BaseGirlEntity, isPreview: Boolean, isNudeOutfit: Boolean): HashSet<String> {
        if (ClientProxy.IS_PRELOADING) {
            return HashSet()
        }

        val boneNameSet: Collection<String> = if (isPreview) {
            ClothingScreen.getCustomBoneNames()
        } else {
            girl.getCustomPartsSet()
        }

        val resultSet = HashSet<String>()

        for (boneName in boneNameSet) {
            val modelData = ServerWhitelistManager.getModelDataForGirl(boneName)
            if (modelData != null && (modelData.isDisabled() || !isNudeOutfit)) {
                resultSet.addAll(modelData.getCustomPartBones())
            }
        }

        return resultSet
    }
}
