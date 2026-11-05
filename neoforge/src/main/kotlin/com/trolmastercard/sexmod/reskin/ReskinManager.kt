package com.trolmastercard.sexmod.reskin

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.mojang.blaze3d.platform.NativeImage
import com.trolmastercard.sexmod.Main
import com.trolmastercard.sexmod.entity.BaseGirlEntity
import com.trolmastercard.sexmod.entity.NpcType
import com.trolmastercard.sexmod.util.ServerWhitelistManager
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ReloadableResourceManager
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import software.bernie.geckolib.cache.`object`.BakedGeoModel
import software.bernie.geckolib.loading.json.raw.Model
import software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter
import software.bernie.geckolib.loading.`object`.BakedAnimations
import software.bernie.geckolib.loading.`object`.BakedModelFactory
import software.bernie.geckolib.loading.`object`.GeometryTree
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.Objects
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/**
 * **Role.** CLIENT-side manager for lazy reskin-pack import.
 *
 * Discovers packs at ANY nesting depth under the current custom-models group
 * ([ServerWhitelistManager.getGlobalModelOverride]) using only header
 * metadata ([ReskinScanner]); decodes geo/texture/animation bytes ONLY
 * when the user clicks Apply ([ReskinReader] + [ReskinApplier]).
 *
 * **How a reskin works.** The pack's entries live at
 * `assets/sexmod/geo|textures|animations/...` — exactly the resource
 * locations the girl models already ask for. Apply parses the archive content
 * and OVERRIDES those `sexmod:` locations in-memory:
 * - geo -> GeckoLib raw `Model` -> `GeometryTree` -> `BakedModelFactory` -> [GEO_OVERRIDES]
 * - texture -> `DynamicTexture` registered under the same location in the texture manager
 * - animation -> `BakedAnimations` -> [ANIMATION_OVERRIDES]
 *
 * No file in the user's folders is ever moved or rewritten, and no resource
 * pack is touched: this is a pure render-time override that takes effect next
 * frame.
 *
 * **Persistence.** A [ResourceManagerReloadListener] hook re-applies
 * every active reskin after F3+T / resource reloads.
 *
 * Port notes (PORTING.md §2.8): GeckoLib 4 rebuilds its caches on every reload
 * and must not be mutated, so geo/animation overrides live in [GEO_OVERRIDES] /
 * [ANIMATION_OVERRIDES]; `GirlModelBase.getBakedModel` / `getAnimation` and
 * `SexSceneModel` consult [getGeoOverride] / [getAnimationOverride] first.
 * `refreshResources()` is the async `reloadResourcePacks()`; the re-apply runs
 * when it completes.
 */
class ReskinManager private constructor() {
    /** Registered on the event bus from `ForgeEventHandler.registerAll(true)`. */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        val t = Thread(r, "sexmod-reskin")
        t.isDaemon = true
        t
    }

    @Volatile
    private var entries: List<ReskinEntry>? = null
    private val previews: MutableMap<String, BufferedImage?> = Collections.synchronizedMap(HashMap())
    private val applied: MutableSet<ReskinEntry> = Collections.synchronizedSet(LinkedHashSet())
    private val appliedTextures: MutableMap<String, DynamicTexture> = ConcurrentHashMap()
    private val retainedTextures: MutableMap<String, DynamicTexture> = ConcurrentHashMap()
    private val applyErrors: MutableMap<String, String> = ConcurrentHashMap()
    private val watcher = ReloadWatcher()
    private var lastTick: Long = 0

    /** Currently-active world key (single-<folder> / multi-<ip>), null when no world is open. */
    private var activeWorldKey: String? = null

    /** Packs that were applied previously but whose archive file is now missing (path -> display name). */
    private val missingPacks: MutableMap<String, String> = ConcurrentHashMap()

    private var watcherRegistered = false

    /** Watches resource reloads (F3+T etc.) and marks reskins as needing re-apply. */
    private class ReloadWatcher : ResourceManagerReloadListener {
        @Volatile
        var dirty = false

        override fun onResourceManagerReload(resourceManager: ResourceManager) {
            this.dirty = true
        }
    }

    /** Returns a cached GL texture for a pack thumbnail, or null (upload later on render thread). */
    fun getGlThumbnail(path: String): ResourceLocation? = GL_THUMBS[path]

    /** Uploads a decoded preview to GL and caches it for every future GUI open. */
    fun uploadThumbnail(path: String, img: BufferedImage): ResourceLocation? {
        val existing = GL_THUMBS[path]
        if (existing != null) return existing
        return try {
            val dt = DynamicTexture(toNativeImage(img))
            val rl = Minecraft.getInstance().textureManager.register("reskin_thumb_" + (GL_THUMBS.size % 100000), dt)
            GL_THUMBS[path] = rl
            GL_THUMB_TEX[path] = dt
            rl
        } catch (t: Throwable) {
            null
        }
    }

    /* ------------------------------------------------------------------ */
    /* Discovery                                                          */
    /* ------------------------------------------------------------------ */

    /** Lazily scans the custom-models tree (metadata only — fast). */
    fun scan(): List<ReskinEntry> {
        val local = entries
        if (local != null) return local
        synchronized(this) {
            if (entries == null) {
                entries = try {
                    val root = File(ServerWhitelistManager.getGlobalModelOverride())
                    ReskinScanner().scan(root)
                } catch (t: Throwable) {
                    Main.LOGGER.error("could not scan the custom model directory", t)
                    ArrayList()
                }
            }
            return entries!!
        }
    }

    /** Re-scan (e.g. after the user drops new archives into the folder). */
    @Synchronized
    fun rescan() {
        entries = null
        scan()
    }

    fun appliedCopy(): List<ReskinEntry> {
        synchronized(applied) {
            return ArrayList(applied)
        }
    }

    fun isApplied(e: ReskinEntry): Boolean = applied.contains(e)

    /** True if any applied reskin targets the given lowercase girl name (e.g. "jenny"). */
    fun isReskinActiveFor(girlLower: String?): Boolean {
        if (girlLower == null) return false
        synchronized(applied) {
            for (e in applied) {
                for (g in e.girls) {
                    if (girlLower.equals(g, ignoreCase = true)) return true
                }
            }
        }
        return false
    }

    /** True if the girl (by NPC type) currently has an applied reskin. */
    fun isGirlReskinned(girl: BaseGirlEntity?): Boolean {
        if (girl == null) return false
        val type = NpcType.getNpcType(girl)
        return type != null && isReskinActiveFor(type.name.lowercase(Locale.ROOT))
    }

    /**
     * Clears the legacy bone-part custom models from every client girl whose
     * NPC type matches this pack. A full-girl reskin replaces the girl entirely;
     * leaving the old custom part set would render the pack as a bone-attached
     * part on top of the reskinned body (the "double layer / texture mashup").
     */
    private fun clearLegacyPartsFor(e: ReskinEntry) {
        try {
            for (girl in BaseGirlEntity.getGirlEntityList()) {
                val type = NpcType.getNpcType(girl) ?: continue
                val tn = type.name.lowercase(Locale.ROOT)
                for (g in e.girls) {
                    if (tn.equals(g, ignoreCase = true)) {
                        girl.setCustomModelCode("")
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            // never crash the apply for cleanup
        }
    }

    /** Last apply error text for an entry (or null). */
    fun applyError(e: ReskinEntry): String? = applyErrors[key(e)]

    /* ------------------------------------------------------------------ */
    /* Previews                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Decoded + downscaled preview of the pack's PNG (pack.png preferred).
     * Safe to call from any thread; the result is cached. Returns null if the
     * pack has no usable PNG.
     */
    fun preview(e: ReskinEntry?): BufferedImage? {
        if (e == null) return null
        val k = key(e)
        val cached = previews[k]
        if (cached != null) return cached
        // try the preferred preview, then fall back to other PNGs in the pack
        // (some packs ship a corrupt pack.png but a usable texture)
        val candidates = ArrayList<String>()
        if (e.previewName != null) candidates.add(e.previewName!!)
        for (fi in e.files) {
            val low = fi.name.lowercase(Locale.ROOT)
            if ((low.endsWith(".png") || low.endsWith(".jpg")) && fi.name != e.previewName) {
                candidates.add(fi.name)
                if (candidates.size >= 4) break
            }
        }
        for (cand in candidates) {
            try {
                val bytes = ReskinReader.read(e, cand) ?: continue
                val img = ReskinReader.decodeImage(bytes) ?: continue
                val small = downscale(img, MAX_PREVIEW_DIM)
                previews[k] = small
                return small
            } catch (ignored: IOException) {
                // try next candidate
            } catch (ignored: RuntimeException) {
                // try next candidate
            }
        }
        previews[k] = null
        return null
    }

    /** Decodes + downscales off-thread, then the GUI turns it into a GL texture. */
    fun requestPreview(e: ReskinEntry) {
        worker.execute { preview(e) }
    }

    /* ------------------------------------------------------------------ */
    /* Apply / Revert                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Applies a pack (decodes geo/texture/animation and overrides the
     * `sexmod:` resource locations). Must be called on the client (render)
     * thread. Returns a user-facing status
     * string; re-applying an already-applied pack just re-overrides.
     */
    @Synchronized
    fun apply(e: ReskinEntry): String {
        registerWatcherOnce()
        val ops = ReskinApplier.plan(e)
        if (ops.isEmpty()) {
            val msg = "No usable reskin content found in '" + e.displayName + "' (needs assets/sexmod/geo|textures|animations)."
            applyErrors[key(e)] = msg
            return msg
        }
        var ok = 0
        val problems = ArrayList<String>()
        for (op in ops) {
            try {
                val bytes = ReskinReader.read(e, op.relName)
                if (bytes == null) {
                    problems.add("missing " + op.relName)
                    continue
                }
                when (op.kind) {
                    ReskinApplier.Kind.GEO -> applyGeo(e, op, bytes)
                    ReskinApplier.Kind.TEXTURE -> applyTexture(e, op, bytes)
                    ReskinApplier.Kind.ANIMATION -> applyAnimation(e, op, bytes)
                }
                ok++
            } catch (t: Throwable) {
                problems.add(op.relName + " (" + t.javaClass.simpleName + ": " + t.message + ")")
            }
        }
        applyErrors.remove(key(e))
        applied.add(e)
        clearLegacyPartsFor(e)
        // clean audit: written to sexmod/reskin_audit.log so the user can see
        // exactly which packs are applied and which were replaced.
        writeAudit()
        saveAppliedForWorld()
        var status = "Applied '" + e.displayName + "' (" + ok + "/" + ops.size + " files)"
        if (problems.isNotEmpty()) status += " — problems: " + problems.joinToString("; ")
        Main.LOGGER.info("[reskin] $status")
        return status
    }

    /** Writes the currently-applied reskin list to `sexmod/reskin_audit.log`. */
    @Synchronized
    fun writeAudit() {
        try {
            val f = File(auditLogPath())
            f.parentFile.mkdirs()
            val sb = StringBuilder()
            sb.append("# reskin audit ").append(Date()).append("\n")
            synchronized(applied) {
                for (e in applied) {
                    sb.append("APPLIED  ").append(e.displayName).append("  girls=").append(e.girlSummary()).append("\n")
                }
            }
            for (en in missingPacks.entries) {
                sb.append("MISSING  ").append(en.value).append("  (").append(en.key).append(")\n")
            }
            sb.append("# end\n")
            Files.write(f.toPath(), sb.toString().toByteArray(StandardCharsets.UTF_8))
        } catch (t: Throwable) {
            // audit is best-effort
        }
    }

    /* ------------------------------------------------------------------ */
    /* Per-world persistence                                              */
    /* ------------------------------------------------------------------ */

    /** Saves the applied pack paths for the current world (no-op outside a world). */
    @Synchronized
    fun saveAppliedForWorld() {
        saveAppliedForWorld(currentWorldKey())
    }

    @Synchronized
    private fun saveAppliedForWorld(worldKey: String?) {
        if (worldKey == null) return
        try {
            val f = appliedConfigFile(worldKey)
            f.parentFile.mkdirs()
            val arr = JsonArray()
            synchronized(applied) {
                for (e in applied) {
                    arr.add(e.source.absolutePath)
                }
            }
            val root = JsonObject()
            root.addProperty("world", worldKey)
            root.add("applied", arr)
            Files.write(f.toPath(), Gson().toJson(root).toByteArray(StandardCharsets.UTF_8))
        } catch (t: Throwable) {
            // persistence is best-effort; never crash apply/revert for it
        }
    }

    /**
     * Re-applies every pack saved for the given world. Packs whose archive file
     * no longer exists are skipped and recorded as missing (no crash). Must be
     * called on the client thread.
     */
    @Synchronized
    fun loadAppliedForWorld(worldKey: String) {
        try {
            val f = appliedConfigFile(worldKey)
            if (!f.isFile) return
            val root = Gson().fromJson(
                String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
                JsonObject::class.java
            )
            if (root == null || !root.has("applied")) return
            val arr = root.getAsJsonArray("applied")
            // map current scan by absolute path so applied entries are the live objects
            val all = scan()
            val byPath = HashMap<String, ReskinEntry>()
            for (e in all) byPath[e.source.absolutePath] = e
            missingPacks.clear()
            var appliedOk = 0
            var missing = 0
            for (el in arr) {
                val path = el.asString
                val e = byPath[path]
                val src = File(path)
                if (e == null || !src.isFile) {
                    missingPacks[path] = e?.displayName ?: File(path).name
                    Main.LOGGER.warn("[reskin] missing pack, skipping: $path")
                    missing++
                    continue
                }
                try {
                    apply(e)
                    appliedOk++
                } catch (t: Throwable) {
                    Main.LOGGER.warn("[reskin] failed to re-apply " + e.displayName + ": " + t.message)
                }
            }
            if (missing > 0) {
                Main.LOGGER.warn("[reskin] world '$worldKey': re-applied $appliedOk pack(s), $missing missing")
            }
            writeAudit()
        } catch (t: Throwable) {
            // never crash world join on a bad config
        }
    }

    /** Paths of previously-applied packs whose archive is now missing (path -> display name). */
    fun missingCopy(): Map<String, String> = HashMap(missingPacks)

    @Throws(Exception::class)
    private fun applyGeo(e: ReskinEntry, op: ReskinApplier.Op, bytes: ByteArray) {
        val json = ReskinApplier.prepareGeoJson(String(bytes, StandardCharsets.UTF_8)) ?: return // decoy / misplaced animation file — skip
        val rl = ResourceLocation.fromNamespaceAndPath(op.rlNs, op.rlPath)
        val raw = KeyFramesAdapter.GEO_GSON.fromJson(json, Model::class.java)
        val tree = GeometryTree.fromModel(raw)
        val model = BakedModelFactory.getForNamespace(rl.namespace).constructGeoModel(tree)
        GEO_OVERRIDES[rl] = model
    }

    @Throws(IOException::class)
    private fun applyTexture(e: ReskinEntry, op: ReskinApplier.Op, bytes: ByteArray) {
        val img = ReskinReader.decodeImage(bytes) ?: return // undecodable/decoy image — keep default texture
        val rl = ResourceLocation.fromNamespaceAndPath(op.rlNs, op.rlPath)
        val dt = DynamicTexture(toNativeImage(img))
        Minecraft.getInstance().textureManager.register(rl, dt)
        appliedTextures[rl.toString()] = dt
        retainedTextures[rl.toString()] = dt
    }

    @Throws(Exception::class)
    private fun applyAnimation(e: ReskinEntry, op: ReskinApplier.Op, bytes: ByteArray) {
        val json = String(bytes, StandardCharsets.UTF_8)
        if (!ReskinApplier.isAnimationJson(json)) return // decoy or not an animation — skip
        val root = Gson().fromJson(json, JsonObject::class.java)
        if (root == null || !root.has("animations")) return // empty animation file is fine
        val rl = ResourceLocation.fromNamespaceAndPath(op.rlNs, op.rlPath)
        val animations = KeyFramesAdapter.GEO_GSON.fromJson(root, BakedAnimations::class.java)
        ANIMATION_OVERRIDES[rl] = animations
    }

    /**
     * Reorders an applied pack within the stack. `delta` is in DISPLAYED
     * stack terms: -1 = move UP (higher priority/overrides), +1 = move DOWN
     * (lower priority/fallback). Internal `applied` is ordered oldest-first
     * (the display reverses it), so display-up == internal-toward-end.
     */
    @Synchronized
    fun move(e: ReskinEntry?, delta: Int): String {
        if (e == null) return "No pack."
        val copy = ArrayList(appliedCopy())
        val idx = copy.indexOf(e)
        if (idx < 0) return "'" + e.displayName + "' is not applied."
        val displayIdx = copy.size - 1 - idx // top-first index
        val newDisplay = displayIdx + delta
        if (newDisplay < 0) return "'" + e.displayName + "' is already at the top."
        if (newDisplay >= copy.size) return "'" + e.displayName + "' is already at the bottom."
        copy.removeAt(idx)
        // After removal the list is one shorter. newDisplay is in 0..size-1 (original).
        // Correct internal insert position (bottom-first) = newSize - newDisplay.
        // Example: internal [A,B,C] => display [C,B,A]; move B to display 0:
        //   remove B -> [A,C] (size 2), newDisplay 0 -> insert at 2 -> [A,C,B]
        //   = display [B,C,A], correct. (Old code used size-1-newDisplay which
        //   produced -1 for a 1-element stack -> IndexOutOfBoundsException.)
        var newInternal = copy.size - newDisplay
        newInternal = Math.max(0, Math.min(newInternal, copy.size))
        try {
            copy.add(newInternal, e)
        } catch (t: Throwable) {
            return "Could not move (internal error)."
        }
        applied.clear()
        applied.addAll(copy)
        // Re-apply bottom->top so the new top (highest priority) is applied LAST and
        // therefore overrides shared resource keys — vanilla texture-pack semantics.
        for (re in copy) {
            try {
                applySilent(re)
            } catch (t: Throwable) {
                /* keep going */
            }
        }
        writeAudit()
        saveAppliedForWorld()
        return "Moved '" + e.displayName + "' to priority " + (newDisplay + 1) + "."
    }

    /**
     * Add a pack to the applied set WITHOUT loading (for the debounce queue) —
     * shows it on the right immediately; flushing with apply() loads it for real.
     */
    @Synchronized
    fun addToAppliedOnly(e: ReskinEntry?) {
        if (e == null) return
        applied.add(e)
    }

    /** Applies without writing chat/status (used for stack re-application). */
    @Synchronized
    private fun applySilent(e: ReskinEntry) {
        try {
            apply(e)
        } catch (t: Throwable) {
            Main.LOGGER.error("could not apply a reskin", t)
        }
    }

    /** Reverts a pack; [applied] is a LinkedHashSet so order is preserved. */
    @Synchronized
    fun revert(e: ReskinEntry): String {
        val ops = ReskinApplier.plan(e)
        for (op in ops) {
            val rl = ResourceLocation.fromNamespaceAndPath(op.rlNs, op.rlPath)
            try {
                if (op.kind == ReskinApplier.Kind.GEO) {
                    GEO_OVERRIDES.remove(rl)
                } else if (op.kind == ReskinApplier.Kind.ANIMATION) {
                    ANIMATION_OVERRIDES.remove(rl)
                } else if (op.kind == ReskinApplier.Kind.TEXTURE) {
                    Minecraft.getInstance().textureManager.release(rl)
                    appliedTextures.remove(rl.toString())
                    retainedTextures.remove(rl.toString())
                }
            } catch (t: Throwable) {
                Main.LOGGER.error("could not unload a reskin resource", t)
            }
        }
        applied.remove(e)
        applyErrors.remove(key(e))
        restoreDefaults()
        saveAppliedForWorld()
        return "Reverted '" + e.displayName + "'"
    }

    /** Removes ALL applied reskins and restores defaults. */
    @Synchronized
    fun revertAll(): String {
        val copy = appliedCopy()
        for (e in copy) revert(e)
        if (copy.isEmpty()) restoreDefaults()
        return if (copy.isEmpty()) "No reskins applied." else "Reverted " + copy.size + " reskin(s)."
    }

    /**
     * Full resource reload: restores the default textures (geo + animation
     * defaults come back simply by dropping the overrides), and our ReloadWatcher
     * fires so pending re-applies happen. 1.21: the reload is asynchronous, so the
     * still-active reskins are re-applied when it completes.
     */
    private fun restoreDefaults() {
        val mc = Minecraft.getInstance()
        try {
            mc.reloadResourcePacks().thenRun {
                mc.execute {
                    // re-apply the still-active reskins (the reload reset the textures too)
                    val still = appliedCopy()
                    for (e in still) {
                        try {
                            apply(e)
                        } catch (t: Throwable) {
                            Main.LOGGER.error("could not re-apply a reskin after the resource reload", t)
                        }
                    }
                    watcher.dirty = false
                }
            }
        } catch (t: Throwable) {
            Main.LOGGER.error("could not refresh the resource manager", t)
        }
    }

    /** Hands over the RenderThread job to the generic client tick handler. */
    @SubscribeEvent
    fun onClientTick(event: ClientTickEvent.Post) {
        val now = System.currentTimeMillis()
        if (now - lastTick < 500) return
        lastTick = now

        // Per-world persistence: detect world enter / leave / switch.
        // Saving the OLD key first is important: currentWorldKey() has already
        // changed by the time we detect the transition.
        val wk = currentWorldKey()
        if (!Objects.equals(wk, this.activeWorldKey)) {
            if (this.activeWorldKey != null) {
                saveAppliedForWorld(this.activeWorldKey)
            }
            this.activeWorldKey = wk
            if (wk != null) {
                loadAppliedForWorld(wk)
            }
        }

        if (watcher.dirty) {
            watcher.dirty = false
            val still = appliedCopy()
            for (e in still) {
                try {
                    apply(e)
                } catch (t: Throwable) {
                    Main.LOGGER.error("could not re-apply a reskin after the resource reload", t)
                }
            }
        }
    }

    @Synchronized
    private fun registerWatcherOnce() {
        if (watcherRegistered) return
        try {
            val mc = Minecraft.getInstance()
            val manager = mc.resourceManager
            if (manager is ReloadableResourceManager) {
                manager.registerReloadListener(this.watcher)
                this.watcher.dirty = false
                watcherRegistered = true
            }
        } catch (t: Throwable) {
            Main.LOGGER.error("could not register the resource reload listener", t)
        }
    }

    /** Background executor exposed for one-off image tasks (daemon). */
    fun submit(r: Runnable) {
        worker.execute(r)
    }

    companion object {
        @JvmField
        val INSTANCE: ReskinManager = ReskinManager()

        private const val MAX_PREVIEW_DIM = 64

        /** Shared GL thumbnails across all GUI instances (keyed by absolute path). */
        private val GL_THUMBS: MutableMap<String, ResourceLocation> = ConcurrentHashMap()
        private val GL_THUMB_TEX: MutableMap<String, DynamicTexture> = ConcurrentHashMap()

        /** Geo overrides by `sexmod:geo/...` location (replaces the GeckoLib 3 cache put). */
        @JvmField
        val GEO_OVERRIDES: MutableMap<ResourceLocation, BakedGeoModel> = ConcurrentHashMap()

        /** Animation overrides by `sexmod:animations/...` location. */
        @JvmField
        val ANIMATION_OVERRIDES: MutableMap<ResourceLocation, BakedAnimations> = ConcurrentHashMap()

        /** Lookup for `GeoModel.getBakedModel` overrides (S2). */
        @JvmStatic
        fun getGeoOverride(location: ResourceLocation): BakedGeoModel? = GEO_OVERRIDES[location]

        /** Lookup for `GeoModel.getAnimation` overrides (S2). */
        @JvmStatic
        fun getAnimationOverride(location: ResourceLocation): BakedAnimations? = ANIMATION_OVERRIDES[location]

        @JvmStatic
        fun downscale(src: BufferedImage, maxDim: Int): BufferedImage {
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0) return src
            val scale = Math.min(1.0, maxDim.toDouble() / Math.max(w, h))
            if (scale >= 1.0) return src
            val nw = Math.max(1, Math.round(w * scale).toInt())
            val nh = Math.max(1, Math.round(h * scale).toInt())
            val out = BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB)
            val g = out.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(src, 0, 0, nw, nh, null)
            g.dispose()
            return out
        }

        /** AWT image -> [NativeImage] (PNG round trip; 1.21 `DynamicTexture` takes a NativeImage). */
        @JvmStatic
        @Throws(IOException::class)
        fun toNativeImage(img: BufferedImage): NativeImage {
            val bos = ByteArrayOutputStream()
            ImageIO.write(img, "png", bos)
            return NativeImage.read(ByteArrayInputStream(bos.toByteArray()))
        }

        @JvmStatic
        fun auditLogPath(): String = "sexmod/reskin_audit.log"

        /**
         * Stable key for the currently open world: `single-<save folder>` for
         * singleplayer, `multi-<server ip>` for multiplayer, else null when no
         * world is loaded (title screen).
         */
        private fun currentWorldKey(): String? {
            try {
                val mc = Minecraft.getInstance()
                val server = mc.singleplayerServer
                if (server != null) {
                    val folder = server.getWorldPath(LevelResource.ROOT).normalize().fileName?.toString()
                    if (folder != null && folder.isNotEmpty()) return "single-$folder"
                }
                val sd = mc.currentServer
                if (sd != null && sd.ip.isNotEmpty()) return "multi-" + sd.ip
            } catch (t: Throwable) {
                // headless / not in a world
            }
            return null
        }

        private fun sanitizeWorldKey(key: String): String = key.replace(Regex("[^A-Za-z0-9._-]"), "_")

        /** Config file storing the applied-pack list for one world. */
        private fun appliedConfigFile(worldKey: String): File = File("sexmod/reskins/" + sanitizeWorldKey(worldKey) + ".json")

        private fun key(e: ReskinEntry): String = e.source.absolutePath

        /** Debug helper: prints the planned override table for a pack. */
        @JvmStatic
        fun debugPlan(e: ReskinEntry) {
            for (op in ReskinApplier.plan(e)) {
                Main.LOGGER.info("  [reskin] $op")
            }
        }
    }
}
