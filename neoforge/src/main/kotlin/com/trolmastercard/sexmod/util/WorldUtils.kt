package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.client.SexWorldClient
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.Nameable
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BedPart
import net.minecraft.world.level.block.state.properties.DirectionProperty
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import net.neoforged.neoforge.server.ServerLifecycleHooks

/**
 * **Role.** World helpers for girl AI, bed placement and tribe logic:
 * angle normalization, light-based look-vector estimation, surface height,
 * structure/bed placement checks, ring particles, double-block (bed/chest)
 * resolution and entity tracking queries. Also the 1.21 replacement for the
 * 1.12 integer `entity.dimension` ([getDimensionId] / [getLevelById]).
 *
 * **Pitfalls.** [getEntityLookVector] deliberately returns straight-up
 * inside the [SexWorldClient] preload world (which no longer exists in 1.21,
 * the check is always false) — keep that branch. [getStatePos] returns `null`
 * for malformed beds and the callers (tribe bed tracking, `SendBlocksPacket`)
 * must handle it.
 */
object WorldUtils {
    @JvmStatic
    fun normalizeAngleDiff(angle1: Float, angle2: Float): Float {
        val a1 = TrigMath.NormalizeAngle(angle1)
        val a2 = TrigMath.NormalizeAngle(angle2)
        val diff = Math.abs(a1 - a2)
        val opposite = 360.0f - diff
        val minDiff = Math.min(diff, opposite)
        return if (a1 > a2) -minDiff else minDiff
    }

    @JvmStatic
    fun getEntityLookVector(entity: LivingEntity, partialTicks: Float): Vec3 {
        val world = entity.level()
        if (SexWorldClient.isPreloadWorld(world)) {
            return Vec3(0.0, 1.0, 0.0)
        }

        val pos = BlockPos.containing(Math.floor(entity.x), Math.floor(entity.y), Math.floor(entity.z))
        val lightMap = HashMap<Vec3, Int>()
        var maxLight = 0

        for (dx in -1 until 2) {
            for (dy in -1 until 2) {
                for (dz in -1 until 2) {
                    // 1.12 getLight(pos, false): raw light, sky light reduced by the sky darkening
                    val light = world.getMaxLocalRawBrightness(pos.offset(dx, dy, dz))
                    lightMap[Vec3(dx.toDouble(), dy.toDouble(), dz.toDouble())] = light
                    if (light > maxLight) {
                        maxLight = light
                    }
                }
            }
        }

        var bestOffset: Vec3? = null

        for ((key, value) in lightMap) {
            if (value == maxLight) {
                if (bestOffset != null) {
                    bestOffset = null
                    break
                }

                bestOffset = key
            }
        }

        if (bestOffset == null) {
            bestOffset = Vec3(0.2, 0.8, 0.0)
        } else {
            bestOffset = Vec3(bestOffset.x, bestOffset.y, -bestOffset.z)
            val yaw = -RotationHelper.lerp(entity.yBodyRotO, entity.yBodyRot, partialTicks)
            bestOffset = VectorMath.rotateByYaw(bestOffset, yaw)
        }

        return bestOffset.normalize()
    }

    @JvmStatic
    fun getHeightAt(world: Level, x: Int, z: Int): Int {
        val transparentBlocks: HashSet<Block> = hashSetOf(
            Blocks.GRASS_BLOCK, Blocks.SAND, Blocks.RED_SANDSTONE, Blocks.WATER, Blocks.STONE, Blocks.COBBLESTONE
        )
        // 1.12: y = world.getHeight() (256) and loop while y-- >= 0; the 1.21 world spans minBuildHeight..maxBuildHeight
        var y = world.maxBuildHeight
        var isTransparent = false

        while (!isTransparent && y-- >= world.minBuildHeight) {
            val block = world.getBlockState(BlockPos(x, y, z)).block
            isTransparent = transparentBlocks.contains(block)
        }

        return y
    }

    @JvmStatic
    fun getSurfaceBlockPos(world: Level, pos: BlockPos): BlockPos =
        BlockPos(pos.x, getHeightAt(world, pos.x, pos.z), pos.z)

    @JvmStatic
    fun canPlaceStructure(world: Level, pos: BlockPos): Boolean = canPlaceBlock(world, pos, null, null, null)

    @JvmStatic
    fun canPlaceBlock(world: Level, pos: BlockPos, hitVec: Vec3?, face: Direction?, player: Player?): Boolean {
        val state = world.getBlockState(pos)
        if (state.isBed(world, pos, null)) {
            return true
        }

        val tileEntity = world.getBlockEntity(pos)
        if (tileEntity is Nameable) {
            val displayName = tileEntity.displayName.string
            if (displayName.contains(" bed") || displayName.contains("bed ")) {
                return true
            }
        }

        return if (face != null && hitVec != null) {
            val blockName = state.getCloneItemStack(BlockHitResult(hitVec, face, pos, false), world, pos, player)
                .hoverName.string.lowercase()
            blockName.contains(" bed") || blockName.contains("bed ")
        } else {
            false
        }
    }

    /**
     * Ring of particles around `center`. Like 1.12 `World.spawnParticle`, this only
     * has a visible effect on the client (`Level.addParticle` is a no-op on the server).
     */
    @JvmStatic
    fun spawnParticles(world: Level, particleType: ParticleOptions, center: Vec3, count: Int, radius: Double, speed: Double) {
        for (i in 0 until count) {
            val progress = i.toFloat() / count
            val angle = (Math.PI * 2) * progress
            var sin = Math.sin(angle)
            var cos = Math.cos(angle)
            sin *= radius
            cos *= radius
            world.addParticle(
                particleType, center.x + sin, center.y, center.z + cos, 0.0, Reference.RANDOM.nextFloat() * speed, 0.0
            )
        }
    }

    @JvmStatic
    fun getStatePos(pos: BlockPos, state: BlockState): BlockPos? {
        var facing: Direction? = null
        var partType: BedPart? = null

        for ((key, value) in state.values) {
            if (key is DirectionProperty) {
                facing = value as Direction
            } else if (key is EnumProperty<*>) {
                partType = value as BedPart
            }
        }

        if (facing == null) {
            Main.LOGGER.info("bed is fucked up - it has no facing value")
            return null
        }

        if (partType == null) {
            Main.LOGGER.info("bed is fucked up - it has no partType value")
            return null
        }

        var pairedPos: BlockPos? = null
        if (partType == BedPart.FOOT) {
            if (facing == Direction.NORTH) {
                pairedPos = pos.north()
            }

            if (facing == Direction.EAST) {
                pairedPos = pos.east()
            }

            if (facing == Direction.SOUTH) {
                pairedPos = pos.south()
            }

            if (facing == Direction.WEST) {
                pairedPos = pos.west()
            }
        } else {
            if (facing == Direction.NORTH) {
                pairedPos = pos.south()
            }

            if (facing == Direction.EAST) {
                pairedPos = pos.west()
            }

            if (facing == Direction.SOUTH) {
                pairedPos = pos.north()
            }

            if (facing == Direction.WEST) {
                pairedPos = pos.east()
            }
        }

        return if (pairedPos == null) {
            Main.LOGGER.info("bed is fucked up - it appears to be positioned vertically (wtf?)")
            null
        } else {
            pairedPos
        }
    }

    /**
     * Players that can see `entity`. 1.12 asked the `EntityTracker` for the
     * entity's tracking players; 1.21 keeps that set private, so this applies the
     * same test as `ChunkMap.TrackedEntity.updatePlayer`: the player watches the
     * entity's chunk, is within the entity type's (scaled) tracking range capped
     * by the view distance, and the entity broadcasts to them.
     */
    @JvmStatic
    fun getNearbyPlayers(entity: Entity?): Set<Player> {
        if (entity == null) {
            return emptySet()
        }
        val server = ServerLifecycleHooks.getCurrentServer() ?: return emptySet()
        val level = server.getLevel(entity.level().dimension()) ?: return emptySet()
        val range = Math.min(
            server.getScaledTrackingDistance(entity.type.clientTrackingRange() * 16),
            server.playerList.viewDistance * 16,
        ).toDouble()
        val pos = entity.position()
        return level.chunkSource.chunkMap.getPlayers(ChunkPos(entity.blockPosition()), false)
            .filter { player ->
                val dx = player.x - pos.x
                val dz = player.z - pos.z
                dx * dx + dz * dz <= range * range && entity.broadcastToPlayer(player)
            }
            .toSet()
    }

    /**
     * 1.12 integer dimension id of a level: 0 = overworld, -1 = nether, 1 = end,
     * otherwise `dimension().location().hashCode()`.
     */
    @JvmStatic
    fun getDimensionId(level: Level): Int {
        val key = level.dimension()
        return when (key) {
            Level.OVERWORLD -> 0
            Level.NETHER -> -1
            Level.END -> 1
            else -> key.location().hashCode()
        }
    }

    /** Inverse of [getDimensionId]: the server level with that id, or `null`. */
    @JvmStatic
    fun getLevelById(server: MinecraftServer, id: Int): ServerLevel? {
        when (id) {
            0 -> return server.getLevel(Level.OVERWORLD)
            -1 -> return server.getLevel(Level.NETHER)
            1 -> return server.getLevel(Level.END)
        }
        for (level in server.allLevels) {
            if (getDimensionId(level) == id) {
                return level
            }
        }
        return null
    }
}
