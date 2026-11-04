package com.trolmastercard.sexmod.util

import com.trolmastercard.sexmod.api.LightingType
import com.trolmastercard.sexmod.entity.BoneType
import com.trolmastercard.sexmod.entity.NpcType
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.util.Properties

/**
 * **Role.** Parser for one custom model's `.cfg` file: reads the
 * properties into a [ServerWhitelistManager.ModelData] (wear type, allowed
 * girls, lighting, author/model-code, hidden bones, nude flag, GUI scale and
 * position) and records the first validation problem in
 * [ServerWhitelistManager.ModelData.errorMessage].
 *
 * **Pitfalls.** The property names are the user-facing mod format — renaming
 * one breaks every existing custom model. Parsing stops at the first illegal
 * value for the enum/name fields, but numeric fields only record the error and
 * keep parsing. The property stream is never closed (matches the original).
 */
object ModelCfgParser {
    @JvmStatic
    fun parse(file: File, modelName: String, model: ServerWhitelistManager.ModelData) {
        if (modelName.contains(" ") || modelName.contains("#") || modelName.contains("$")) {
            model.errorMessage = String.format("You cannot call your custom model '%s'. '#', '$' and spaces are illegal characters", modelName)
        } else if ("cross".equals(modelName, ignoreCase = true)) {
            model.errorMessage = "You cannot call your custom model 'cross'. Im sorry, but I need that specific name for internal stuff"
        } else {
            val properties = Properties()

            val inputStream: FileInputStream
            try {
                inputStream = FileInputStream(file)
            } catch (notFoundExc: FileNotFoundException) {
                model.errorMessage = String.format("couldn't find cfg File for '%s'. It should have been at '%s'. Are you sure it exists?", modelName, file.absolutePath)
                return
            }

            try {
                properties.load(inputStream)
            } catch (ioExc: IOException) {
                model.errorMessage = String.format(
                    "couldn't read the cfg File for '%s' at '%s'. It appears to be corrupted. Try making a new one", modelName, file.absolutePath
                )
                return
            }

            var wearType: String? = properties.getProperty("wear_type")
            if (wearType == null) {
                model.errorMessage = String.format(
                    "The cfg File for the model '%s' at '%s' is missing the 'wear_type'. Go to the bottom of the cfg File and write 'wear_type=HEAD'. Check the cfg files of my examples to see what values for 'wear_type' are possible",
                    modelName,
                    file.absolutePath
                )
            } else {
                try {
                    wearType = wearType.replace(" ", "")
                    model.boneType = BoneType.valueOf(wearType)
                } catch (illegalArg: IllegalArgumentException) {
                    model.errorMessage = String.format(
                        "you entered '%s' into the 'wear_type' field of the %s's cfg file at '%s'. This is not a valid value. Check my examples on what valid values are to enter into the field 'wear_type",
                        wearType,
                        modelName,
                        file.absolutePath
                    )
                    return
                }

                if (BoneType.CUSTOM_BONE == model.boneType) {
                    model.modelName = properties.getProperty("custom_bone")
                    if ("" == model.modelName) {
                        model.errorMessage = String.format(
                            "You selected CUSTOM_BONE as the 'wear_type' in the cfg file for '%s' at '%s', yet you left the 'custom_bone' field right underneath it empty. If you want ur model to be parented to a specific bone, you have to enter the name of that bone at the field 'custom_bone'.",
                            modelName,
                            file.absolutePath
                        )
                        return
                    }
                }

                var girlsStr: String? = properties.getProperty("which_girls")
                if (girlsStr == null) {
                    girlsStr = ""
                }

                girlsStr = girlsStr.replace(" ", "")
                val girlNames = girlsStr.split(",")

                for (girlName in girlNames) {
                    try {
                        if ("" != girlName) {
                            model.allowedNpcTypes.add(NpcType.valueOf(girlName))
                        }
                    } catch (illegalArg2: IllegalArgumentException) {
                        model.errorMessage = String.format(
                            "you entered '%s' as one of the girls, you put into the 'which_girls' field of the %s's cfg file at '%s'. This is not a valid value. Check my examples on what valid values are to enter into the field 'which_girls'.",
                            girlName,
                            modelName,
                            file.absolutePath
                        )
                        return
                    }
                }

                var lightingStr: String? = properties.getProperty("which_lighting")
                if (lightingStr == null) {
                    model.errorMessage = String.format(
                        "The %s's cfg file at '%s' doesn't contain the field 'which_lighting'. Go to the bottom of the cfg file and write either 'which_lighting=DEFAULT', 'which_lighting=SEXMOD', or 'which_lighting=NONE'.",
                        modelName,
                        file.absolutePath
                    )
                } else {
                    lightingStr = lightingStr.replace(" ", "")

                    try {
                        model.lightingType = LightingType.valueOf(lightingStr)
                    } catch (illegalArg3: IllegalArgumentException) {
                        model.errorMessage = String.format(
                            "you entered '%s' into the 'which_lighting' field of the %s's cfg file at '%s'. This is not a valid value. Check my examples on what valid values are to enter into the field 'which_lighting'.",
                            lightingStr,
                            modelName,
                            file.absolutePath
                        )
                    }

                    val author: String? = properties.getProperty("author")
                    if (author != null && "" != author) {
                        model.modelCode = author
                    } else {
                        model.modelCode = "anon"
                    }

                    var hideBonesStr: String? = properties.getProperty("bones_to_hide")
                    if (hideBonesStr != null && "" != hideBonesStr) {
                        hideBonesStr = hideBonesStr.replace(" ", "")
                        val boneNames = hideBonesStr.split(",")
                        model.customPartBones.addAll(boneNames)
                    }

                    var nudeStr: String? = properties.getProperty("enable_when_nude")
                    if (nudeStr == null) {
                        model.disabled = false
                    } else {
                        nudeStr = nudeStr.replace(" ", "")
                        model.disabled = nudeStr.equals("yes", ignoreCase = true)
                    }

                    var scaleStr: String? = properties.getProperty("gui_size_factor")
                    if (scaleStr != null && "" != scaleStr) {
                        scaleStr = scaleStr.replace(" ", "")
                        scaleStr = scaleStr.replace(",", ".")

                        try {
                            model.scale = java.lang.Float.parseFloat(scaleStr)
                        } catch (numberExc: NumberFormatException) {
                            model.errorMessage = String.format(
                                "you entered '%s' into the 'gui_size_factor' field of the %s's cfg file at '%s'. This is not a valid value. Check my examples on what valid values are to enter into the field 'gui_size_factor'.",
                                scaleStr,
                                modelName,
                                file.absolutePath
                            )
                        }
                    }

                    var posStr: String? = properties.getProperty("gui_vertical_positioning")
                    if (posStr != null && "" != posStr) {
                        posStr = posStr.replace(" ", "")
                        posStr = posStr.replace(",", ".")

                        try {
                            model.xOffset = java.lang.Float.parseFloat(posStr)
                        } catch (numberExc2: NumberFormatException) {
                            model.errorMessage = String.format(
                                "you entered '%s' into the 'gui_vertical_positioning' field of the %s's cfg file at '%s'. This is not a valid value. Check my examples on what valid values are to enter into the field 'gui_vertical_positioning'.",
                                posStr,
                                modelName,
                                file.absolutePath
                            )
                        }
                    }

                    // java-npe: a cfg without 'version' threw a NullPointerException in 1.12 too
                    var versionStr: String = properties.getProperty("version")!!
                    versionStr = versionStr.replace(" ", "")
                    versionStr = versionStr.replace(",", ".")

                    try {
                        model.zOffset = java.lang.Float.parseFloat(versionStr)
                    } catch (numberExc3: NumberFormatException) {
                        model.errorMessage = String.format(
                            "you entered '%s' into the 'versionString' field of the %s's cfg file at '%s'. This is not a valid value. Check my examples on what valid values are to enter into the field 'versionString'.",
                            versionStr,
                            modelName,
                            file.absolutePath
                        )
                    }
                }
            }
        }
    }
}
