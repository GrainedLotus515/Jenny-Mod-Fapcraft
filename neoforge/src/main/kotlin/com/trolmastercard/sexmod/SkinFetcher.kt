package com.trolmastercard.sexmod

import com.mojang.blaze3d.platform.NativeImage
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.util.Base64
import java.util.UUID
import java.util.stream.Collectors
import net.minecraft.client.Minecraft

/**
 * Downloads a player's Minecraft skin texture from the Mojang session server
 * and returns it as a [NativeImage] for custom NPC/player-girl textures.
 *
 * CLIENT-side only (network + [Minecraft] resource access). Fails safe:
 * any exception (network error, malformed JSON, missing texture URL) falls back
 * to the bundled `steve.png` texture; if even that cannot be loaded it
 * returns `null` — callers must null-check the result.
 *
 * **Pitfall:** this performs a synchronous HTTP request on the calling
 * thread; do not invoke it from the render thread during frame rendering.
 *
 * Port note: returns a [NativeImage] instead of a `BufferedImage` (1.21 textures).
 */
object SkinFetcher {
    /**
     * Fetches the skin for `uuid` by querying the Mojang session profile
     * endpoint, base64-decoding the skin `value`, extracting the texture
     * URL and downloading the PNG.
     *
     * @param uuid the player UUID (hyphens stripped for the API call)
     * @return the skin image, the bundled steve texture on any failure, or
     *         `null` if even the fallback is unavailable
     */
    @JvmStatic
    fun fetchSkin(uuid: UUID): NativeImage? {
        try {
            val url = URI("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString().replace("-", "")).toURL()
            val profileJson: String
            BufferedReader(InputStreamReader(url.openStream())).use { reader ->
                profileJson = reader.lines().collect(Collectors.joining())
            }

            val valueStart = profileJson.indexOf("\"value\" : ")
            val valueIdx = valueStart + 11
            val value = StringBuilder()

            var i = 0
            while (profileJson[valueIdx + i] != '"') {
                value.append(profileJson[valueIdx + i])
                i++
            }

            val decoded = String(Base64.getDecoder().decode(value.toString()))
            val urlStart = decoded.indexOf("\"url\" : ")
            val urlIdx = urlStart + 9
            val urlBuilder = StringBuilder()

            var i2 = 0
            while (decoded[urlIdx + i2] != '"') {
                urlBuilder.append(decoded[urlIdx + i2])
                i2++
            }

            val textureUrl = URI(urlBuilder.toString()).toURL()
            return textureUrl.openStream().use { NativeImage.read(it) }
        } catch (e: Exception) {
            return try {
                Minecraft.getInstance().resourceManager.open(Main.rl("textures/player/steve.png")).use { NativeImage.read(it) }
            } catch (ioe: java.io.IOException) {
                null
            }
        }
    }
}
