package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.renderer.GoblinRenderer
import com.trolmastercard.sexmod.client.renderer.KoboldRenderer
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.KoboldEntity
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.AABB
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.neoforge.client.event.ClientChatEvent
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent

/**
 * **Role.** Developer-only debug tooling, active only in a deobfuscated
 * (dev) environment — see [isDeobfuscated]. Registers client chat
 * commands (`set N value`, `get N`, `time`, `girls`,
 * `kobs`, `setcumtime`, `resetcolor`) and prints tribe-task
 * diagnostics when a kobold is hurt. The shared dev-float array `DEV_FLOATS` is a
 * tuning surface for renderer constants; keep all debug logging behind the
 * deobfuscated check so obfuscated builds never touch it.
 *
 * Port notes: COMMON handler (registered on both sides). The chat commands
 * live in [ClientEvents] (PORTING.md §2.3 rule E); the hurt diagnostics stay
 * here (`LivingHurtEvent` -> [LivingIncomingDamageEvent]) and reach the local
 * player through the client-only [Client] object, only on a physical client.
 * `Launch.blackboard["fml.deobfuscatedEnvironment"]` -> `!FMLEnvironment.production`.
 * [DEV_FLOATS] is always allocated (1.12 left it null in obfuscated builds).
 */
open class DebugMode {
    init {
        if (isDeobfuscated()) {
            DEV_FLOATS = FloatArray(60)
        }
    }

    @SubscribeEvent
    open fun onLivingHurt(event: LivingIncomingDamageEvent) {
        if (isDeobfuscated() && FMLEnvironment.dist.isClient) {
            val hurtEntity = event.entity
            if (hurtEntity is KoboldEntity) {
                Client.koboldHurtDiagnostics(hurtEntity)
            }
        }
    }

    /** Client-only chat commands (PORTING.md §2.3 rule E). */
    object ClientEvents {
        @SubscribeEvent
        fun onDebugResetColor(event: ClientChatEvent) {
            if (isDeobfuscated()) {
                if ("resetcolor".equals(event.message, ignoreCase = true)) {
                    KoboldRenderer.clearBoneColors()
                    PlayerKoboldRenderer.clearRenderCache()
                    GoblinRenderer.clearBoneColors()
                    PlayerGoblinRenderer.clearRenderCache()
                }
            }
        }

        @SubscribeEvent
        fun onDebugSetCommand(event: ClientChatEvent) {
            if (isDeobfuscated()) {
                val message = event.originalMessage
                val parts = message.split(" ")
                if (parts.size == 3) {
                    if ("set".equals(parts[0], ignoreCase = true)) {
                        val floats = DEV_FLOATS
                        val index: Int
                        val value: Float
                        try {
                            index = Integer.parseInt(parts[1])
                            value = java.lang.Float.parseFloat(parts[2])
                            if (floats.size - 1 < index) {
                                return
                            }
                        } catch (exception: Exception) {
                            return
                        }

                        Minecraft.getInstance().player?.sendSystemMessage(
                            Component.literal(String.format("%sSet dev float N.%s from %s to %s", ChatFormatting.GRAY, index, floats[index], value))
                        )
                        floats[index] = value
                        event.isCanceled = true
                    }
                }
            }
        }

        @SubscribeEvent
        fun onDebugGetCommand(event: ClientChatEvent) {
            if (isDeobfuscated()) {
                val message = event.originalMessage
                val parts = message.split(" ")
                if (parts.size == 2) {
                    if ("get".equals(parts[0], ignoreCase = true)) {
                        val floats = DEV_FLOATS
                        val index: Int
                        try {
                            index = Integer.parseInt(parts[1])
                            if (floats.size - 1 < index) {
                                return
                            }
                        } catch (exception: Exception) {
                            return
                        }

                        Minecraft.getInstance().player?.sendSystemMessage(
                            Component.literal(String.format("%sdev float N.%s is %s", ChatFormatting.YELLOW, index, floats[index]))
                        )
                        event.isCanceled = true
                    }
                }
            }
        }

        @SubscribeEvent
        fun onDebugTimeCommand(event: ClientChatEvent) {
            if (isDeobfuscated()) {
                val command = event.originalMessage.lowercase()
                val player = Minecraft.getInstance().player ?: return
                if ("time" == command) {
                    player.sendSystemMessage(Component.literal(player.level().gameTime.toString()))
                }

                if ("girls" == command) {
                    val girls = BaseGirlEntity.getGirlEntityList().filter { it.level() === player.level() }
                    player.sendSystemMessage(Component.literal(girls.size.toString()))

                    for (girl in girls) {
                        Main.LOGGER.info("{} at {} {} {} {}", girl, girl.x, girl.y, girl.z)
                    }
                }

                if ("kobs" == command) {
                    val tribeUuid = KoboldManager.getTribeUUID(player.uuid) ?: return
                    val memberCount = KoboldManager.getTribeMemberCount(tribeUuid)

                    for (kobold in KoboldManager.getTribeMembersList(tribeUuid)) {
                        sendDebugMessage(
                            String.format(
                                "alive member %s at %s world.isremote? %s isdead %s girlID %s entityID %s",
                                kobold.getDisplayNameText(),
                                kobold.blockPosition(),
                                kobold.level().isClientSide,
                                !kobold.isAlive,
                                kobold.getGirlId(),
                                kobold.id
                            )
                        )
                        sendDebugMessage(
                            if (player.level().getEntitiesOfClass(KoboldEntity::class.java, AABB(kobold.blockPosition())).isEmpty())
                                "couldn't be located"
                            else
                                "appears to actually exist"
                        )
                    }

                    val savedPositions = KoboldManager.getTribeSavedPositions(tribeUuid, player.level())

                    for (entry in savedPositions.entries) {
                        sendDebugMessage(String.format("saved pos of %s at %s", entry.key.toString(), entry.value.toString()))
                    }

                    sendDebugMessage("total amount members: $memberCount")
                }

                if (command.startsWith("setcumtime ")) {
                    val parts = command.split(" ")

                    val cumTime: Long
                    try {
                        cumTime = java.lang.Long.parseLong(parts[1])
                    } catch (exception: NumberFormatException) {
                        Main.LOGGER.info("long: " + parts[1])
                        Main.LOGGER.error("could not parse the cum time", exception)
                        return
                    }

                    GirlSavedData.saveCumTime(player.uuid, cumTime)
                    player.sendSystemMessage(Component.literal("set to: $cumTime"))
                }
            }
        }
    }

    /** Client-only half of the hurt diagnostics (PORTING.md §2.3 rule C). */
    private object Client {
        fun koboldHurtDiagnostics(kobold: KoboldEntity) {
            val player = Minecraft.getInstance().player ?: return
            val tribeUuid = KoboldManager.getTribeUUID(player.uuid) ?: return

            for (task in KoboldManager.getTribeTasks(tribeUuid) ?: emptyList()) {
                sendDebugMessage("task: " + task.getTaskType().name)
                sendDebugMessage("workers involved: ")

                for (worker in task.getWorkers()) {
                    sendDebugMessage(worker.getDisplayNameText() + " " + worker.getGirlId())
                }
            }

            sendDebugMessage("tribe contains my exact reference: " + KoboldManager.getTribeMembersList(tribeUuid).contains(kobold))
            sendDebugMessage("tribe contains my ID: ")
            var memberFound = false

            for (member in KoboldManager.getTribeMembersList(tribeUuid)) {
                if (member.getGirlId() == kobold.getGirlId()) {
                    memberFound = true
                }
            }

            var savedFound = false

            for (entry in KoboldManager.getTribeSavedPositions(tribeUuid, player.level()).entries) {
                if (entry.key == kobold.getGirlId()) {
                    savedFound = true
                }
            }

            sendDebugMessage("loaded : $memberFound")
            sendDebugMessage("saved : $savedFound")
        }
    }

    companion object {
        const val debugFlag: Int = 60

        @JvmField
        var DEV_FLOATS: FloatArray = FloatArray(60)

        @JvmStatic
        fun isDeobfuscated(): Boolean = !FMLEnvironment.production

        /** CLIENT only. */
        @JvmStatic
        fun sendDebugMessage(message: String) {
            ClientChat.send(message)
        }
    }

    private object ClientChat {
        fun send(message: String) {
            Minecraft.getInstance().player?.sendSystemMessage(Component.literal(message))
        }
    }
}
