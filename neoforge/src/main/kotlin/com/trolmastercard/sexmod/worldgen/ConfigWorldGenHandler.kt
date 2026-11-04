package com.trolmastercard.sexmod.worldgen

import com.trolmastercard.sexmod.entity.GoblinEntity
import com.trolmastercard.sexmod.util.KoboldManager
import com.trolmastercard.sexmod.util.Point2D
import com.trolmastercard.sexmod.util.WorldUtils
import net.minecraft.core.BlockPos
import net.minecraft.core.HolderLookup
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.Biome
import net.minecraft.world.level.biome.Biomes
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.levelgen.FlatLevelSource
import net.minecraft.world.level.saveddata.SavedData
import net.minecraft.world.phys.Vec3
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.level.ChunkEvent
import net.neoforged.neoforge.event.level.LevelEvent
import net.neoforged.neoforge.event.tick.LevelTickEvent
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Forge world-gen event handler that routes structure generation to the
 * configured generators (girl houses etc.) — see GirlHouseGenerator.
 *
 * Port notes (PORTING.md §3 S3 "Worldgen replacement"):
 * - 1.21 has no `IWorldGenerator`. [onChunkLoad] queues every newly generated
 *   overworld chunk ([ChunkEvent.Load] with `isNewChunk`), and [onLevelTick]
 *   drains the queue at the end of the overworld tick, calling
 *   [generate] with a [Random] seeded exactly like 1.12
 *   `GameRegistry.generateWorld` (including its `>> 2 + 1` precedence, which
 *   is a shift by 3).
 * - Persistence: [SavedData] `sexmod_generation`. The loaded data is read into
 *   the registered singleton (1.12 loaded it into a throwaway instance created
 *   by `MapStorage`, so the generator never saw saved positions).
 * - Biome sets are `ResourceKey<Biome>` sets (PORTING.md §2.4 mapping).
 * - `WorldType.FLAT` check -> the overworld generator is a [FlatLevelSource].
 */
open class ConfigWorldGenHandler private constructor() : SavedData() {
    @JvmField
    val KOBOBLIN_SPAWN_CHANCE: Double = 0.004f.toDouble()

    @JvmField
    val girlHouseConfigs: MutableList<BiomeRule> = ArrayList()

    @JvmField
    val generatedPositions: MutableList<StructureData> = ArrayList()

    /** Newly generated chunks waiting for [generate] (filled on chunk load, drained on the level tick). */
    private val pendingChunks = ConcurrentLinkedQueue<ChunkPos>()

    constructor(name: String) : this()

    init {
        INSTANCE = this
        this.girlHouseConfigs.add(
            BiomeRule(
                "ellie",
                hashSetOf(Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.SNOWY_TAIGA, Biomes.TAIGA, Biomes.DARK_FOREST),
                Vec3i(30, 27, 26),
                9,
                true
            )
        )
        this.girlHouseConfigs.add(BiomeRule("jenny", hashSetOf(Biomes.PLAINS, Biomes.FOREST), Vec3i(9, 4, 9), 1, true))
        this.girlHouseConfigs.add(
            BiomeRule(
                "ellie",
                hashSetOf(Biomes.OLD_GROWTH_PINE_TAIGA, Biomes.SNOWY_TAIGA, Biomes.TAIGA, Biomes.DARK_FOREST),
                Vec3i(30, 27, 26),
                9,
                true
            )
        )
        this.girlHouseConfigs.add(BiomeRule("bia", hashSetOf(Biomes.OLD_GROWTH_BIRCH_FOREST, Biomes.BIRCH_FOREST), Vec3i(11, 9, 15), 2, true))
        this.girlHouseConfigs.add(BiomeRule("luna", hashSetOf(Biomes.OCEAN, Biomes.DEEP_OCEAN), Vec3i(3, 7, 10), 0, false))
    }

    open fun clear() {
        this.generatedPositions.clear()
        this.pendingChunks.clear()
    }

    @SubscribeEvent
    open fun onSave(event: LevelEvent.Save) {
        val world = event.level as? ServerLevel ?: return
        world.server.overworld().dataStorage.set(DATA_NAME, this)
        this.setDirty()
    }

    @SubscribeEvent
    open fun onLoad(event: LevelEvent.Load) {
        val world = event.level as? ServerLevel ?: return
        world.server.overworld().dataStorage.computeIfAbsent(factory(), DATA_NAME)
    }

    /** 1.21 replacement of `IWorldGenerator` registration: remember newly generated overworld chunks. */
    @SubscribeEvent
    open fun onChunkLoad(event: ChunkEvent.Load) {
        if (!event.isNewChunk) {
            return
        }

        val level = event.level as? ServerLevel ?: return
        if (level.dimension() != Level.OVERWORLD) {
            return
        }

        this.pendingChunks.add(event.chunk.pos)
    }

    /** Runs the queued chunk generation on the server thread after the overworld tick. */
    @SubscribeEvent
    open fun onLevelTick(event: LevelTickEvent.Post) {
        val level = event.level as? ServerLevel ?: return
        if (level.dimension() != Level.OVERWORLD) {
            return
        }

        while (true) {
            val chunk = this.pendingChunks.poll() ?: break
            this.generate(createChunkRandom(level.seed, chunk.x, chunk.z), chunk.x, chunk.z, level)
        }
    }

    open fun readFromNBT(tag: CompoundTag) {
        this.generatedPositions.clear()
        val generation = tag.getCompound("sexmod:generation")
        var i = 0

        while (true) {
            val name = generation.getString("sexmod:name$i")
            val pos = generation.getString("sexmod:pos$i")
            if ("" == name || "" == pos) {
                return
            }

            this.generatedPositions.add(StructureData(parseSpawnEntry(pos), name))
            i++
        }
    }

    open fun writeToNBT(tag: CompoundTag): CompoundTag {
        tag.put("sexmod:generation", CompoundTag())
        val generation = CompoundTag()
        var i = 0

        for (data in this.generatedPositions) {
            generation.putString("sexmod:name$i", data.girlName)
            generation.putString("sexmod:pos$i", getChunkHash(data.pos))
            i++
        }

        tag.put("sexmod:generation", generation)
        return tag
    }

    override fun save(tag: CompoundTag, registries: HolderLookup.Provider): CompoundTag = this.writeToNBT(tag)

    open fun generate(random: Random, chunkX: Int, chunkZ: Int, world: Level) {
        if (GENERATION_ENABLED) {
            if (!isFlatWorld(world)) {
                this.spawnStructures(world, random, chunkX, chunkZ)
                this.generateChunk(world, random, chunkX, chunkZ)
                this.generateStructure(random, chunkX, chunkZ, world)
            }
        }
    }

    open fun generateStructure(random: Random, chunkX: Int, chunkZ: Int, world: Level) {
        if (IS_GENERATING) {
            IS_GENERATING = false

            for (rule in this.girlHouseConfigs) {
                this.placeStructure(rule, random, chunkX, chunkZ, world)
            }

            IS_GENERATING = true
        }
    }

    open fun placeStructure(rule: BiomeRule, random: Random, chunkX: Int, chunkZ: Int, world: Level) {
        for (data in this.generatedPositions) {
            val minDistance = if (data.girlName == rule.girlName) 156 else 62
            if (data.pos.distanceTo(chunkX, chunkZ) < minDistance) {
                return
            }
        }

        val sizeX = rule.size.x
        val sizeZ = rule.size.z
        var startX = chunkX * 16 + (16 - sizeX) / 2
        var startZ = chunkZ * 16 + (16 - sizeZ) / 2
        val biome = world.getBiome(BlockPos(startX, 80, startZ))
        if (rule.biomes.any { biome.`is`(it) }) {
            var maxHeight = Int.MIN_VALUE
            var minHeight = Int.MAX_VALUE

            for (x in startX until startX + sizeX) {
                for (z in startZ until startZ + sizeZ) {
                    val height = WorldUtils.getHeightAt(world, x, z)
                    if (rule.flattenGround && world.getBlockState(BlockPos(x, height, z)).block === Blocks.WATER) {
                        return
                    }

                    if (height > maxHeight) {
                        maxHeight = height
                    }

                    if (height < minHeight) {
                        minHeight = height
                    }
                }
            }

            if (maxHeight - minHeight <= rule.maxHeightDiff) {
                val flatHeight = maxHeight
                this.generatedPositions.add(StructureData(Point2D(chunkX, chunkZ), rule.girlName))
                this.setDirty()
                rule.generator.generate(world, random, BlockPos(startX, flatHeight, startZ))
                if (rule.flattenGround) {
                    var modified = true

                    var y = flatHeight - 1
                    while (modified) {
                        modified = false
                        val extents = Vec3i(sizeX + 2, 0, sizeZ + 2)
                        startX--
                        startZ--

                        for (x2 in startX until startX + extents.x) {
                            for (z2 in startZ until startZ + extents.z) {
                                val pos = BlockPos(x2, y, z2)
                                var state = world.getBlockState(pos)
                                // 1.12 Block.isPassable = !material.blocksMovement()
                                if (!state.blocksMotion()) {
                                    state = if (world.canSeeSky(pos)) Blocks.GRASS_BLOCK.defaultBlockState() else Blocks.DIRT.defaultBlockState()
                                    world.setBlockAndUpdate(pos, state)
                                    modified = true
                                }
                            }
                        }
                        y--
                    }
                }
            }
        }
    }

    open fun spawnStructures(world: Level, random: Random, chunkX: Int, chunkZ: Int) {
        if (!(random.nextDouble() > 0.004f)) {
            val x = chunkX * 16 + 8
            val z = chunkZ * 16 + 8
            val height = WorldUtils.getHeightAt(world, x, z)
            if (world.getBlockState(BlockPos(x, height, z)).fluidState.isEmpty) {
                KoboldManager.spawnKoboldAt(world, Vec3(x.toDouble(), height.toDouble(), z.toDouble()))
            }
        }
    }

    open fun generateChunk(world: Level, random: Random, chunkX: Int, chunkZ: Int) {
        val x = 16 * chunkX + 3
        val z = 16 * chunkZ + 3
        val y = random.nextInt(255)
        val origin = BlockPos(x, y, z)
        val spots = ArrayList<BlockPos>()

        for (x2 in 0..GoblinEntity.THRONE_SIZE.x) {
            for (y2 in -1..GoblinEntity.THRONE_SIZE.y) {
                for (z2 in 0..GoblinEntity.THRONE_SIZE.z) {
                    val pos = origin.offset(x2, y2, z2)
                    // 1.12 Material.isSolid()
                    @Suppress("DEPRECATION")
                    val solid = world.getBlockState(pos).isSolid
                    if (!solid && (y2 == -1 || y2 == GoblinEntity.THRONE_SIZE.y)) {
                        return
                    }

                    if ((x2 == 0 || x2 == GoblinEntity.THRONE_SIZE.x || z2 == 0 || z2 == GoblinEntity.THRONE_SIZE.z)
                        && y2 == 0
                        && world.isEmptyBlock(pos)
                        && world.isEmptyBlock(pos.above())
                    ) {
                        spots.add(pos)
                    }
                }
            }
        }

        if (spots.size != 0 && spots.size <= 4) {
            var candidate: BlockPos? = null

            for (spot in spots) {
                var relative = spot
                val center = origin.offset(6, 0, 6)
                relative = relative.subtract(center)
                if (Math.abs(relative.x) != Math.abs(relative.z)
                    && Math.abs(relative.x) != Math.abs(relative.z) - 1
                    && Math.abs(relative.x) - 1 != Math.abs(relative.z)
                ) {
                    candidate = relative
                    break
                }
            }

            if (candidate != null) {
                var offset = Vec3i(0, 0, 0)
                var yaw = 0.0f
                val rotation: Rotation
                var offsetPos: Vec3
                if (candidate.z == -6) {
                    rotation = Rotation.NONE
                    offsetPos = GoblinEntity.THRONE_OFFSET_YAW_180
                    yaw = 180.0f
                } else if (candidate.x == 5) {
                    rotation = Rotation.CLOCKWISE_90
                    offsetPos = GoblinEntity.THRONE_OFFSET_YAW_NEG_90
                    offset = Vec3i(GoblinEntity.THRONE_SIZE.x - 1, 0, 0)
                    yaw = -90.0f
                } else if (candidate.z == 5) {
                    rotation = Rotation.CLOCKWISE_180
                    offsetPos = GoblinEntity.THRONE_OFFSET_YAW_0
                    offset = Vec3i(GoblinEntity.THRONE_SIZE.x - 1, 0, GoblinEntity.THRONE_SIZE.z - 1)
                } else {
                    rotation = Rotation.COUNTERCLOCKWISE_90
                    offsetPos = GoblinEntity.THRONE_OFFSET_YAW_90
                    offset = Vec3i(0, 0, GoblinEntity.THRONE_SIZE.z - 1)
                    yaw = 90.0f
                }

                GirlHouseGenerator("goblin").generateStructureRotated(world, origin.offset(0, -1, 0).offset(offset), rotation)
                // 1.12: Vec3d is immutable and the result of add(...) was discarded; kept as a no-op
                offsetPos.add(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble())
                offsetPos = Vec3(
                    origin.x + offsetPos.x + 0.5, origin.y + offsetPos.y, origin.z + offsetPos.z + 0.5
                )
                val goblin = GoblinEntity(world, true, yaw, offsetPos)
                // 1.12 Entity.forceSpawn has no 1.21 equivalent (addFreshEntity always spawns into a loaded chunk)
                world.addFreshEntity(goblin)
                world.getChunk(chunkX, chunkZ).setUnsaved(true)
            }
        }
    }

    open class StructureData(@JvmField var pos: Point2D, name: String) {
        @JvmField
        var girlName: String = name
    }

    open class BiomeRule(name: String, biomes: HashSet<ResourceKey<Biome>>, size: Vec3i, maxHeightDiff: Int, flatten: Boolean) {
        @JvmField
        val girlName: String = name

        @JvmField
        val generator: GirlHouseGenerator = GirlHouseGenerator(name)

        @JvmField
        val biomes: HashSet<ResourceKey<Biome>> = biomes

        @JvmField
        val size: Vec3i = size

        @JvmField
        val flattenGround: Boolean = flatten

        @JvmField
        val maxHeightDiff: Int = maxHeightDiff
    }

    companion object {
        /** 1.12 "sexmod:generation"; `:` is not allowed in 1.21 data file names (PORTING.md §2.11). */
        const val DATA_NAME: String = "sexmod_generation"
        const val ELLIE_MIN_DISTANCE: Int = 156
        const val DEFAULT_MIN_DISTANCE: Int = 62
        const val spacing: Int = 6

        @JvmField
        var GENERATION_ENABLED: Boolean = true

        private var INSTANCE: ConfigWorldGenHandler? = null

        @JvmField
        var IS_GENERATING: Boolean = true

        @JvmStatic
        fun getInstance(): ConfigWorldGenHandler {
            if (INSTANCE == null) {
                INSTANCE = ConfigWorldGenHandler()
            }

            return INSTANCE!!
        }

        /** Saved data loads into the registered singleton (see class doc). */
        @JvmStatic
        fun load(tag: CompoundTag, registries: HolderLookup.Provider): ConfigWorldGenHandler {
            val data = getInstance()
            data.readFromNBT(tag)
            return data
        }

        @JvmStatic
        fun factory(): SavedData.Factory<ConfigWorldGenHandler> =
            SavedData.Factory({ getInstance() }, ::load, null)

        @JvmStatic
        fun getChunkHash(pos: Point2D): String = pos.x.toString() + "|" + pos.y

        @JvmStatic
        fun parseSpawnEntry(entry: String): Point2D {
            val parts = entry.split("|")
            return Point2D(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]))
        }

        /**
         * 1.12 `GameRegistry.generateWorld` seeding. Note `nextLong() >> 2 + 1L`
         * parses as `nextLong() >> 3` in Java; the exact 1.12 values are kept.
         */
        @JvmStatic
        fun createChunkRandom(worldSeed: Long, chunkX: Int, chunkZ: Int): Random {
            val fmlRandom = Random(worldSeed)
            val xSeed = fmlRandom.nextLong() shr (2 + 1)
            val zSeed = fmlRandom.nextLong() shr (2 + 1)
            val chunkSeed = (xSeed * chunkX + zSeed * chunkZ) xor worldSeed
            fmlRandom.setSeed(chunkSeed)
            return fmlRandom
        }

        @JvmStatic
        fun isFlatWorld(world: Level): Boolean =
            world is ServerLevel && world.chunkSource.generator is FlatLevelSource
    }
}
