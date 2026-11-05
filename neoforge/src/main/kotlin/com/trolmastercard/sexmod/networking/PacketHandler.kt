package com.trolmastercard.sexmod.networking

import com.trolmastercard.sexmod.util.WorldUtils
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ChunkPos
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.server.ServerLifecycleHooks

/**
 * Registers every mod payload (1.12: the `"sexmodchannel"` simple channel).
 *
 * 1.12 assigned sequential packet IDs at registration time; 1.21 payloads are
 * identified by their `Main.rl(...)` type ID, so registration order no longer
 * matters. Each payload class exposes `TYPE`, `STREAM_CODEC` and
 * `handle(packet, ctx)` in its companion (§2.7). Packets registered twice in
 * 1.12 (once per side) are bidirectional here.
 *
 * The scene-critical packets (see their class docs): [KoboldStatePacket]
 * (scene entry), [ChangeDataParameterPacket] (data-manager bridge),
 * [ResetGirlPacket] (scene end), [SendGirlToSexPacket] (bed scenes),
 * [TeleportPlayerPacket] (scene camera positioning),
 * [SetPlayerMovementPacket] (movement lock restore).
 *
 * [networkWrapper] keeps the 1.12 send API (`PacketHandler.networkWrapper.sendTo(...)`).
 */
object PacketHandler {
    /** Limit for large strings (model data, block sets): `buf.writeUtf(s, MAX_STRING)`. */
    const val MAX_STRING = 262144

    /** 1.12 `SimpleNetworkWrapper` send API on top of [PacketDistributor]. */
    @JvmField
    val networkWrapper: NetworkWrapper = NetworkWrapper()

    @JvmStatic
    fun register(event: RegisterPayloadHandlersEvent) {
        val registrar = event.registrar("1")
        // ---- S1 ----
        registrar.playBidirectional(SendChatMessagePacket.TYPE, SendChatMessagePacket.STREAM_CODEC) { p, c -> SendChatMessagePacket.handle(p, c) }
        registrar.playToClient(SetPlayerMovementPacket.TYPE, SetPlayerMovementPacket.STREAM_CODEC) { p, c -> SetPlayerMovementPacket.handle(p, c) }
        registrar.playToServer(TeleportPlayerPacket.TYPE, TeleportPlayerPacket.STREAM_CODEC) { p, c -> TeleportPlayerPacket.handle(p, c) }
        registrar.playToServer(SendGirlToSexPacket.TYPE, SendGirlToSexPacket.STREAM_CODEC) { p, c -> SendGirlToSexPacket.handle(p, c) }
        registrar.playToServer(SetPlayerForGirlPacket.TYPE, SetPlayerForGirlPacket.STREAM_CODEC) { p, c -> SetPlayerForGirlPacket.handle(p, c) }
        registrar.playToServer(KoboldStatePacket.TYPE, KoboldStatePacket.STREAM_CODEC) { p, c -> KoboldStatePacket.handle(p, c) }
        registrar.playBidirectional(ResetControllerPacket.TYPE, ResetControllerPacket.STREAM_CODEC) { p, c -> ResetControllerPacket.handle(p, c) }
        registrar.playToServer(ResetGirlPacket.TYPE, ResetGirlPacket.STREAM_CODEC) { p, c -> ResetGirlPacket.handle(p, c) }
        registrar.playToServer(ChangeDataParameterPacket.TYPE, ChangeDataParameterPacket.STREAM_CODEC) { p, c -> ChangeDataParameterPacket.handle(p, c) }
        registrar.playToServer(PlayerActionPacket.TYPE, PlayerActionPacket.STREAM_CODEC) { p, c -> PlayerActionPacket.handle(p, c) }
        registrar.playToServer(SendCompanionHomePacket.TYPE, SendCompanionHomePacket.STREAM_CODEC) { p, c -> SendCompanionHomePacket.handle(p, c) }
        registrar.playToServer(SetNewHomePacket.TYPE, SetNewHomePacket.STREAM_CODEC) { p, c -> SetNewHomePacket.handle(p, c) }
        registrar.playToServer(UploadInventoryToServerPacket.TYPE, UploadInventoryToServerPacket.STREAM_CODEC) { p, c -> UploadInventoryToServerPacket.handle(p, c) }
        registrar.playToServer(RemoveItemsPacket.TYPE, RemoveItemsPacket.STREAM_CODEC) { p, c -> RemoveItemsPacket.handle(p, c) }
        registrar.playToServer(UploadInventoryToServerPacket2.TYPE, UploadInventoryToServerPacket2.STREAM_CODEC) { p, c -> UploadInventoryToServerPacket2.handle(p, c) }
        registrar.playToServer(UpdatePlayerModelPacket.TYPE, UpdatePlayerModelPacket.STREAM_CODEC) { p, c -> UpdatePlayerModelPacket.handle(p, c) }
        registrar.playBidirectional(SexPromptPacket.TYPE, SexPromptPacket.STREAM_CODEC) { p, c -> SexPromptPacket.handle(p, c) }
        registrar.playToServer(StartStandingSexAnimationPacket.TYPE, StartStandingSexAnimationPacket.STREAM_CODEC) { p, c -> StartStandingSexAnimationPacket.handle(p, c) }
        registrar.playToClient(SpawnParticlePacket.TYPE, SpawnParticlePacket.STREAM_CODEC) { p, c -> SpawnParticlePacket.handle(p, c) }
        registrar.playToClient(GirlDataPacket.TYPE, GirlDataPacket.STREAM_CODEC) { p, c -> GirlDataPacket.handle(p, c) }
        registrar.playToClient(ForcePlayerGirlUpdatePacket.TYPE, ForcePlayerGirlUpdatePacket.STREAM_CODEC) { p, c -> ForcePlayerGirlUpdatePacket.handle(p, c) }
        registrar.playToServer(UpdateVelocityPacket.TYPE, UpdateVelocityPacket.STREAM_CODEC) { p, c -> UpdateVelocityPacket.handle(p, c) }
        registrar.playBidirectional(UnknownPacket.TYPE, UnknownPacket.STREAM_CODEC) { p, c -> UnknownPacket.handle(p, c) }
        registrar.playToClient(SetPlayerCamPacket.TYPE, SetPlayerCamPacket.STREAM_CODEC) { p, c -> SetPlayerCamPacket.handle(p, c) }
        // UpdateEquipmentPacket is deliberately not registered: 1.12 never registered or sent it, and its
        // handler lets a client overwrite any girl's inventory with arbitrary NBT.

        // ---- S3 ----
        registrar.playToServer(UploadModelStringPacket.TYPE, UploadModelStringPacket.STREAM_CODEC) { p, c -> UploadModelStringPacket.handle(p, c) }
        registrar.playBidirectional(DownloadServerModelPacket.TYPE, DownloadServerModelPacket.STREAM_CODEC) { p, c -> DownloadServerModelPacket.handle(p, c) }

        // ---- S4 ----
        registrar.playToServer(SummonAlliePacket.TYPE, SummonAlliePacket.STREAM_CODEC) { p, c -> SummonAlliePacket.handle(p, c) }
        registrar.playToServer(MakeRichWishPacket.TYPE, MakeRichWishPacket.STREAM_CODEC) { p, c -> MakeRichWishPacket.handle(p, c) }
        registrar.playToServer(BeeOpenChestPacket.TYPE, BeeOpenChestPacket.STREAM_CODEC) { p, c -> BeeOpenChestPacket.handle(p, c) }
        registrar.playToServer(CatActivateFishingPacket.TYPE, CatActivateFishingPacket.STREAM_CODEC) { p, c -> CatActivateFishingPacket.handle(p, c) }
        registrar.playToServer(CatEatingDonePacket.TYPE, CatEatingDonePacket.STREAM_CODEC) { p, c -> CatEatingDonePacket.handle(p, c) }
        registrar.playToServer(CatThrowAwayItemPacket.TYPE, CatThrowAwayItemPacket.STREAM_CODEC) { p, c -> CatThrowAwayItemPacket.handle(p, c) }

        // ---- S5 ----
        registrar.playToServer(GalathRapePouncePacket.TYPE, GalathRapePouncePacket.STREAM_CODEC) { p, c -> GalathRapePouncePacket.handle(p, c) }
        registrar.playToServer(GalathBackOffRapePacket.TYPE, GalathBackOffRapePacket.STREAM_CODEC) { p, c -> GalathBackOffRapePacket.handle(p, c) }
        registrar.playToServer(RequestRidingPacket.TYPE, RequestRidingPacket.STREAM_CODEC) { p, c -> RequestRidingPacket.handle(p, c) }
        registrar.playToClient(InformOfOwnershipPacket.TYPE, InformOfOwnershipPacket.STREAM_CODEC) { p, c -> InformOfOwnershipPacket.handle(p, c) }
        registrar.playToClient(SpawnEnergyBallParticlesPacket.TYPE, SpawnEnergyBallParticlesPacket.STREAM_CODEC) { p, c -> SpawnEnergyBallParticlesPacket.handle(p, c) }
        registrar.playToClient(SpawnEnergyBallParticlesPacket2.TYPE, SpawnEnergyBallParticlesPacket2.STREAM_CODEC) { p, c -> SpawnEnergyBallParticlesPacket2.handle(p, c) }

        // ---- S6 ----
        registrar.playToServer(ClaimTribePacket.TYPE, ClaimTribePacket.STREAM_CODEC) { p, c -> ClaimTribePacket.handle(p, c) }
        registrar.playBidirectional(GetTribeUiValuesPacket.TYPE, GetTribeUiValuesPacket.STREAM_CODEC) { p, c -> GetTribeUiValuesPacket.handle(p, c) }
        registrar.playToServer(SetTribeFollowModePacket.TYPE, SetTribeFollowModePacket.STREAM_CODEC) { p, c -> SetTribeFollowModePacket.handle(p, c) }
        registrar.playToServer(FallTreePacket.TYPE, FallTreePacket.STREAM_CODEC) { p, c -> FallTreePacket.handle(p, c) }
        registrar.playBidirectional(SendBlocksPacket.TYPE, SendBlocksPacket.STREAM_CODEC) { p, c -> SendBlocksPacket.handle(p, c) }
        registrar.playToServer(CancelTaskPacket.TYPE, CancelTaskPacket.STREAM_CODEC) { p, c -> CancelTaskPacket.handle(p, c) }
        registrar.playToServer(SendEggPacket.TYPE, SendEggPacket.STREAM_CODEC) { p, c -> SendEggPacket.handle(p, c) }
        registrar.playToServer(MinePacket.TYPE, MinePacket.STREAM_CODEC) { p, c -> MinePacket.handle(p, c) }
    }

    /**
     * 1.12 `SimpleNetworkWrapper` send API. Server-side helpers are no-ops when no
     * server is running (or the target level cannot be resolved).
     */
    class NetworkWrapper {
        /** Client -> server. Only call on the client. */
        fun sendToServer(msg: CustomPacketPayload) {
            PacketDistributor.sendToServer(msg)
        }

        /** Server -> one player. */
        fun sendTo(msg: CustomPacketPayload, player: ServerPlayer) {
            PacketDistributor.sendToPlayer(player, msg)
        }

        /** Server -> every player on the server. */
        fun sendToAll(msg: CustomPacketPayload) {
            if (ServerLifecycleHooks.getCurrentServer() == null) return
            PacketDistributor.sendToAllPlayers(msg)
        }

        /** Server -> every player tracking `entity`. */
        fun sendToAllTracking(msg: CustomPacketPayload, entity: Entity) {
            PacketDistributor.sendToPlayersTrackingEntity(entity, msg)
        }

        /** Server -> every player tracking the chunk containing the point. */
        fun sendToAllTracking(msg: CustomPacketPayload, point: TargetPoint) {
            val level = point.getLevel() ?: return
            PacketDistributor.sendToPlayersTrackingChunk(level, ChunkPos(BlockPos.containing(point.x, point.y, point.z)), msg)
        }

        /** Server -> every player within `point.range` of the point in its dimension. */
        fun sendToAllAround(msg: CustomPacketPayload, point: TargetPoint) {
            val level = point.getLevel() ?: return
            PacketDistributor.sendToPlayersNear(level, null, point.x, point.y, point.z, point.range, msg)
        }

        /** Server -> every player in the dimension with the 1.12 integer id (`WorldUtils.getDimensionId`). */
        fun sendToDimension(msg: CustomPacketPayload, dimensionId: Int) {
            val server = ServerLifecycleHooks.getCurrentServer() ?: return
            val level = WorldUtils.getLevelById(server, dimensionId) ?: return
            PacketDistributor.sendToPlayersInDimension(level, msg)
        }
    }
}
