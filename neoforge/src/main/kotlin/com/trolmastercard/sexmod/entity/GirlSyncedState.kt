package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.util.SceneDebug
import java.util.UUID
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * Every accessor for the state [BaseGirlEntity] keeps in the vanilla
 * `SynchedEntityData`: action, outfit, interaction partner, anchor
 * target/yaw, master binding, custom name and custom-model code.
 *
 * Extracted verbatim from [BaseGirlEntity], which keeps each method as a
 * delegate, so the public API seen by subclasses is unchanged.
 * Client writes still go through `changeDataParameterFromClient` and
 * overridable accessors are called back through the girl instance.
 */
object GirlSyncedState {
    @JvmStatic
    fun setWalkSpeed(girl: BaseGirlEntity, state: BaseGirlEntity.BaseGirlEntityState) {
        girl.entityDataManager.set(BaseGirlEntity.WALK_SPEED, state.toString())
    }

    @JvmStatic
    fun getWalkType(girl: BaseGirlEntity): BaseGirlEntity.BaseGirlEntityState {
        return BaseGirlEntity.BaseGirlEntityState.valueOf(girl.entityDataManager.get(BaseGirlEntity.WALK_SPEED))
    }

    @JvmStatic
    fun getGirlId(girl: BaseGirlEntity): UUID {
        return try {
            UUID.fromString(girl.entityDataManager.get(BaseGirlEntity.GIRL_ID))
        } catch (ex: Exception) {
            val id = UUID.randomUUID()
            girl.entityDataManager.set(BaseGirlEntity.GIRL_ID, id.toString())
            id
        }
    }

    @JvmStatic
    fun getCurrentAction(girl: BaseGirlEntity): Action {
        return Action.valueOf(girl.entityDataManager.get(BaseGirlEntity.CUR_ACTION))
    }

    @JvmStatic
    fun setCurrentAction(girl: BaseGirlEntity, action: Action?) {
        var action = action
        val previousAction = girl.getCurrentAction()
        SceneDebug.log(
            SceneDebug.ACTIONS, "setCurrentAction %s -> %s (%s, remote=%s, anchored=%s)",
            girl.getDisplayNameText(), previousAction, action, girl.level().isClientSide, girl.isAnchored(),
        )
        if (previousAction !== action) {
            if (action !== Action.ATTACK || previousAction === Action.NULL) {
                action = action ?: Action.NULL
                if (girl.level().isClientSide) {
                    girl.changeDataParameterFromClient("currentAction", action.toString())
                } else {
                    previousAction.ticksPlaying = intArrayOf(0, 0)
                    girl.entityDataManager.set(BaseGirlEntity.CUR_ACTION, action.toString())
                }
            }
        }
    }

    @JvmStatic
    fun getOutfitIndex(girl: BaseGirlEntity): Int {
        return girl.entityDataManager.get(BaseGirlEntity.OUTFIT_INDEX)
    }

    @JvmStatic
    fun setOutfitIndex(girl: BaseGirlEntity, index: Int) {
        if (girl.level().isClientSide) {
            girl.changeDataParameterFromClient("currentModel", "0")
        } else {
            girl.entityDataManager.set(BaseGirlEntity.OUTFIT_INDEX, index)
        }
    }

    @JvmStatic
    fun getPlayerEntity(girl: BaseGirlEntity): Player? {
        val uuid = girl.getInteractionPlayerUUID()
        return if (uuid == null) null else girl.level().getPlayerByUUID(uuid)
    }

    @JvmStatic
    fun getInteractionPlayerUUID(girl: BaseGirlEntity): UUID? {
        val uuidStr = girl.entityDataManager.get(BaseGirlEntity.INTERACTION_PARTNER_UUID)
        if (girl.cachedInteractionPlayerRaw == null || girl.cachedInteractionPlayerRaw != uuidStr) {
            girl.cachedInteractionPlayerUUID = if (uuidStr == "null") null else UUID.fromString(uuidStr)
            girl.cachedInteractionPlayerRaw = uuidStr
        }

        return girl.cachedInteractionPlayerUUID
    }

    @JvmStatic
    fun setInteractionPlayerUUID(girl: BaseGirlEntity, uuid: UUID?) {
        if (girl.level().isClientSide) {
            if (uuid == null) {
                girl.changeDataParameterFromClient("playerSheHasSexWith", null)
            } else {
                girl.changeDataParameterFromClient("playerSheHasSexWith", uuid.toString())
            }
        } else {
            if (uuid == null) {
                girl.entityDataManager.set(BaseGirlEntity.INTERACTION_PARTNER_UUID, "null")
            } else {
                girl.entityDataManager.set(BaseGirlEntity.INTERACTION_PARTNER_UUID, uuid.toString())
            }
        }
    }

    @JvmStatic
    fun setInteractionPlayer(girl: BaseGirlEntity, player: Player) {
        girl.setInteractionPlayerUUID(player.uuid)
    }

    @JvmStatic
    fun getTargetPosition(girl: BaseGirlEntity): Vec3 {
        val raw = girl.entityDataManager.get(BaseGirlEntity.TARGET_POS)
        if (girl.cachedTargetPosRaw == null || girl.cachedTargetPosRaw != raw) {
            val parts = raw.split("|")
            girl.cachedTargetPos = Vec3(parts[0].toDouble(), parts[1].toDouble(), parts[2].toDouble())
            girl.cachedTargetPosRaw = raw
        }

        return girl.cachedTargetPos
    }

    @JvmStatic
    fun setTargetPosition(girl: BaseGirlEntity, pos: Vec3) {
        if (girl.level().isClientSide) {
            val formatted = pos.x.toString() + "f" + pos.y + "f" + pos.z + "f"
            girl.changeDataParameterFromClient("targetPos", formatted)
        } else {
            girl.entityDataManager.set(BaseGirlEntity.TARGET_POS, pos.x.toString() + "|" + pos.y + "|" + pos.z)
        }
    }

    @JvmStatic
    fun setTargetPositionDirect(girl: BaseGirlEntity, pos: Vec3) {
        girl.entityDataManager.set(BaseGirlEntity.TARGET_POS, pos.x.toString() + "|" + pos.y + "|" + pos.z)
    }

    @JvmStatic
    fun getYawRotation(girl: BaseGirlEntity): Float {
        return girl.entityDataManager.get(BaseGirlEntity.YAW_ROTATION)
    }

    @JvmStatic
    fun setYawRotation(girl: BaseGirlEntity, yaw: Float) {
        girl.entityDataManager.set(BaseGirlEntity.YAW_ROTATION, yaw)
    }

    @JvmStatic
    fun setAnchored(girl: BaseGirlEntity, anchored: Boolean) {
        if (girl.level().isClientSide) {
            girl.changeDataParameterFromClient("shouldbeattargetpos", anchored.toString())
        } else {
            girl.entityDataManager.set(BaseGirlEntity.IS_ANCHORED, anchored)
        }
    }

    @JvmStatic
    fun isAnchored(girl: BaseGirlEntity): Boolean {
        return girl.entityDataManager.get(BaseGirlEntity.IS_ANCHORED)
    }

    @JvmStatic
    fun goHome(girl: BaseGirlEntity) {
        if (girl.level().isClientSide) {
            girl.changeDataParameterFromClient("master", "")
            girl.changeDataParameterFromClient("walk speed", BaseGirlEntity.BaseGirlEntityState.WALK.toString())
        } else {
            girl.entityDataManager.set(BaseGirlEntity.MASTER, "")
            girl.entityDataManager.set(BaseGirlEntity.WALK_SPEED, BaseGirlEntity.BaseGirlEntityState.WALK.toString())
        }
    }

    @JvmStatic
    fun hasMaster(girl: BaseGirlEntity): Boolean {
        return girl.entityDataManager.get(BaseGirlEntity.MASTER) != ""
    }

    @JvmStatic
    fun getMasterUUID(girl: BaseGirlEntity): UUID? {
        val masterUuid = girl.entityDataManager.get(BaseGirlEntity.MASTER)
        if ("" == masterUuid) {
            return null
        }

        return try {
            UUID.fromString(masterUuid)
        } catch (ex: IllegalArgumentException) {
            null
        }
    }

    @JvmStatic
    fun getMasterPlayer(girl: BaseGirlEntity): Player? {
        val masterUuid = girl.getMasterUUID()
        return if (masterUuid == null) null else girl.level().getPlayerByUUID(masterUuid)
    }

    @JvmStatic
    fun setCustomNameOverride(girl: BaseGirlEntity, name: String) {
        girl.entityDataManager.set(BaseGirlEntity.CUSTOM_NAME, name)
    }

    @JvmStatic
    fun getCustomName(girl: BaseGirlEntity): String {
        return girl.entityDataManager.get(BaseGirlEntity.CUSTOM_NAME)
    }

    @JvmStatic
    fun getEffectiveDisplayName(girl: BaseGirlEntity): String {
        val customName = girl.entityDataManager.get(BaseGirlEntity.CUSTOM_NAME)
        return if ("" != customName) customName else girl.getDisplayNameText()
    }

    @JvmStatic
    fun setCustomModelCode(girl: BaseGirlEntity, modelCode: String) {
        girl.entityDataManager.set(BaseGirlEntity.CUSTOM_MODEL_KEY, modelCode)
    }

    @JvmStatic
    fun getCustomModelCode(girl: BaseGirlEntity): String {
        return girl.entityDataManager.get(BaseGirlEntity.CUSTOM_MODEL_KEY)
    }
}
