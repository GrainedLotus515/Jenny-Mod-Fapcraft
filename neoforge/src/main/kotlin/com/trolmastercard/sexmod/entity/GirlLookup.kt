package com.trolmastercard.sexmod.entity

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.util.ClientServerCheck
import java.util.ConcurrentModificationException
import java.util.UUID
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.entity.EntityTypeTest
import net.neoforged.neoforge.server.ServerLifecycleHooks

/**
 * Registry-free lookups over the live girl entities (all levels on the
 * integrated/dedicated server, the client level otherwise).
 *
 * Extracted verbatim from [BaseGirlEntity]; every method keeps its
 * original `BaseGirlEntity` static delegate, so call sites elsewhere do
 * not change.
 */
object GirlLookup {
    @JvmStatic
    fun getGirlEntityList(): List<BaseGirlEntity> {
        if (!ClientServerCheck.getInstance()) {
            return getClientGirls()
        }

        val server = ServerLifecycleHooks.getCurrentServer() ?: return ArrayList()
        val worlds = server.allLevels.toList()
        if (worlds.isEmpty()) {
            return ArrayList()
        }

        val girls = ArrayList<BaseGirlEntity>()

        for (world in worlds) {
            girls.addAll(world.getEntities(EntityTypeTest.forClass(BaseGirlEntity::class.java)) { true })
        }

        return girls
    }

    /** CLIENT. */
    private fun getClientGirls(): List<BaseGirlEntity> = Client.getClientGirls()

    @JvmStatic
    fun getClientGirlEntity(girlId: UUID?): BaseGirlEntity? {
        if (girlId == null) {
            return null
        }

        for (girl in girlList(girlId)) {
            if (girl.level().isClientSide) {
                return girl
            }
        }

        return null
    }

    @JvmStatic
    fun getServerGirlEntity(girlId: UUID?): BaseGirlEntity? {
        if (girlId == null) {
            return null
        }

        for (girl in girlList(girlId)) {
            if (!girl.level().isClientSide) {
                return girl
            }
        }

        return null
    }

    @JvmStatic
    fun girlList(girlId: UUID?): ArrayList<BaseGirlEntity> {
        val girls = ArrayList<BaseGirlEntity>()

        try {
            for (girl in getGirlEntityList()) {
                if (girl != null && girl.getGirlId() == girlId) {
                    girls.add(girl)
                }
            }
        } catch (ex: ConcurrentModificationException) {
            Main.LOGGER.warn("had a ConcurrentModificationException while cycling through the girl list... hopefully nothin borke owo", ex)
        }

        return girls
    }

    @JvmStatic
    fun getCompanionInteractingWithPlayer(player: Player?): BaseGirlEntity? {
        return if (player == null) null else getGirlByUUID(player.uuid)
    }

    @JvmStatic
    fun getGirlByUUID(uuid: UUID): BaseGirlEntity? {
        return getGirlByUUID(uuid, null)
    }

    @JvmStatic
    fun getGirlByUUID(uuid: UUID, serverOnly: Boolean?): BaseGirlEntity? {
        try {
            for (girl in getGirlEntityList()) {
                if (!girl.isRemoved && uuid == girl.getInteractionPlayerUUID()) {
                    if (serverOnly == null) {
                        return girl
                    }

                    val isRemote = girl.level().isClientSide
                    if (isRemote && !serverOnly) {
                        return girl
                    }

                    if (!isRemote && serverOnly) {
                        return girl
                    }
                }
            }
        } catch (ex: ConcurrentModificationException) {
            // ignored: entity list mutated during iteration; retried on the next tick
        }

        return null
    }

    @JvmStatic
    fun getActiveSceneInfo(uuid: UUID): BaseGirlEntity? {
        val noServer = ServerLifecycleHooks.getCurrentServer() == null

        try {
            for (girl in getGirlEntityList()) {
                if (!girl.isRemoved) {
                    val isRemote = girl.level().isClientSide
                    if (isRemote == noServer && uuid == girl.getInteractionPlayerUUID()) {
                        return girl
                    }
                }
            }
        } catch (ex: ConcurrentModificationException) {
            // ignored: entity list mutated during iteration; retried on the next tick
        }

        return null
    }

    @JvmStatic
    fun getActiveSceneInfo(player: Player): BaseGirlEntity? {
        return getActiveSceneInfo(player.uuid)
    }

    /** Client-only helpers (PORTING.md §2.3 rule C). */
    private object Client {
        fun getClientGirls(): List<BaseGirlEntity> {
            val world = Minecraft.getInstance().level ?: return ArrayList()
            val girls = ArrayList<BaseGirlEntity>()
            for (entity in world.entitiesForRendering()) {
                if (entity is BaseGirlEntity) {
                    girls.add(entity)
                }
            }
            return girls
        }
    }
}
