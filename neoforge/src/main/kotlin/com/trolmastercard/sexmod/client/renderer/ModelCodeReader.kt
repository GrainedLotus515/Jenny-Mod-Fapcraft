package com.trolmastercard.sexmod.client.renderer

import com.trolmastercard.sexmod.Main
import java.io.BufferedReader
import java.io.FileReader
import java.io.IOException

/**
 * Reads a model-code file from disk into the string form the model code
 * evaluator expects (see [GirlRenderer.buildModelCode]).
 *
 * **Format.** Every source line gets a trailing `//` so line
 * comments survive concatenation. A read failure is logged through
 * [Main.LOGGER] and yields whatever was read so far.
 *
 * CLIENT-side render thread only.
 */
object ModelCodeReader {
    /**
     * Reads `path` line by line, appending `"//\n"` after each line.
     *
     * @return the assembled model code, empty if the file could not be read
     */
    @JvmStatic
    fun read(path: String): String {
        val builder = StringBuilder()

        try {
            val reader = BufferedReader(FileReader(path))

            var line = reader.readLine()
            while (line != null) {
                builder.append(line).append("//\n")
                line = reader.readLine()
            }

            reader.close()
        } catch (e: IOException) {
            Main.LOGGER.error("could not read the model code file", e)
        }

        return builder.toString()
    }
}
