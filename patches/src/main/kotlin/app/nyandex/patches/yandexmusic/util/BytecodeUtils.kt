/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.patches.yandexmusic.util

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByStrings
import app.morphe.patcher.patch.getAllClassesWithStrings
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.util.proxy.mutableTypes.MutableAnnotation
import app.morphe.patcher.util.proxy.mutableTypes.encodedValue.MutableStringEncodedValue
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

/**
 * One literal that is replaced wherever it occurs in a string constant of the app.
 *
 * If [from] is only a part of a larger literal, only that part is replaced. This keeps
 * URLs with a path and formatted AppsFlyer endpoints intact while redirecting the host.
 */
internal data class StringReplacement(val from: String, val to: String)

/**
 * Replace string literals in the instructions of every class that contains one of them.
 *
 * Longer replacements are preferred, so replacing `...IAppUpdateServiceCallback` does not
 * leave a broken `...IAppUpdateService` suffix behind.
 *
 * @return The number of replaced instructions.
 */
internal fun replaceStringConstants(vararg replacements: StringReplacement): Int {
    require(replacements.isNotEmpty()) { "At least one replacement is required" }

    val classesByType = linkedMapOf<String, com.android.tools.smali.dexlib2.iface.ClassDef>()
    replacements.forEach { replacement ->
        classDefByStrings(replacement.from, StringComparisonType.CONTAINS).forEach { classDef ->
            classesByType[classDef.type] = classDef
        }
    }

    var changed = 0
    classesByType.values.forEach { classDef ->
        val mutableClass = mutableClassDefBy(classDef.type)

        mutableClass.methods.toList().forEach { method ->
            val implementation = method.implementation ?: return@forEach
            val matchingIndexes = implementation.instructions.mapIndexedNotNull { index, instruction ->
                val literal = (instruction as? ReferenceInstruction)?.reference as? StringReference
                    ?: return@mapIndexedNotNull null

                index.takeIf { findReplacement(literal.string, replacements) != null }
            }

            matchingIndexes.asReversed().forEach { index ->
                val instruction = implementation.instructions[index]
                val register = (instruction as OneRegisterInstruction).registerA
                val literal = (instruction as ReferenceInstruction).reference as StringReference
                val replacement = findReplacement(literal.string, replacements)
                    ?: return@forEach

                method.replaceInstruction(
                    index,
                    constantStringInstruction(register, replacement),
                )
                changed++
            }
        }
    }

    return changed
}

/**
 * Replace string literals that are exactly equal to [from] with [to].
 *
 * Unlike [replaceStringConstants], this does not touch longer strings that merely contain
 * [from]. That matters for the Passport account type: action names, permissions and
 * provider authorities share the `com.yandex.passport` prefix but must keep their
 * original values.
 *
 * @return The number of replaced instructions.
 */
internal fun replaceExactStringConstants(from: String, to: String): Int {
    require(from != to) { "The replacement must differ from the original string" }

    var changed = 0
    classDefByStrings(from, StringComparisonType.EQUALS).forEach { classDef ->
        val mutableClass = mutableClassDefBy(classDef.type)

        mutableClass.methods.toList().forEach { method ->
            val implementation = method.implementation ?: return@forEach
            val matchingIndexes = implementation.instructions.mapIndexedNotNull { index, instruction ->
                val literal = (instruction as? ReferenceInstruction)?.reference as? StringReference
                index.takeIf { literal?.string == from }
            }

            matchingIndexes.asReversed().forEach { index ->
                val instruction = implementation.instructions[index]
                val register = (instruction as OneRegisterInstruction).registerA
                method.replaceInstruction(index, constantStringInstruction(register, to))
                changed++
            }
        }
    }

    // Static field initial values are encoded values, not const-string instructions.
    getAllClassesWithStrings().forEach { classDef ->
        if (classDef.staticFields.none { field ->
                (field.initialValue as? StringEncodedValue)?.value == from
            }
        ) {
            return@forEach
        }

        val mutableClass = mutableClassDefBy(classDef.type)
        mutableClass.staticFields.forEach { field ->
            val value = field.initialValue as? MutableStringEncodedValue ?: return@forEach
            if (value.value == from) {
                value.setValue(to)
                changed++
            }
        }
    }

    return changed
}

/**
 * Replace string literals inside annotations (for example Retrofit `@GET` paths).
 *
 * Instruction-only replacement cannot see these values because annotation values are
 * not `const-string` instructions, so endpoint annotations need their own pass.
 *
 * @return The number of annotation values that were changed.
 */
internal fun replaceAnnotationStrings(
    classType: String,
    vararg replacements: StringReplacement,
): Int {
    require(replacements.isNotEmpty()) { "At least one replacement is required" }

    val mutableClass = mutableClassDefBy(classType)
    var changed = 0

    fun MutableAnnotation.patchElements() {
        elements.forEach { element ->
            val value = element.value as? MutableStringEncodedValue ?: return@forEach
            val replacement = findReplacement(value.value, replacements) ?: return@forEach
            value.setValue(replacement)
            changed++
        }
    }

    mutableClass.annotations.forEach { it.patchElements() }
    mutableClass.methods.forEach { method -> method.annotations.forEach { it.patchElements() } }
    mutableClass.fields.forEach { field -> field.annotations.forEach { it.patchElements() } }

    return changed
}

private fun findReplacement(value: String, replacements: Array<out StringReplacement>): String? {
    // Exact matches first: "auth/games" must not win over "auth/games_lite".
    replacements.firstOrNull { it.from == value }?.let { return it.to }

    // Then the most specific partial match.
    return replacements
        .filter { value.contains(it.from) }
        .maxByOrNull { it.from.length }
        ?.let { value.replace(it.from, it.to) }
}

private fun constantStringInstruction(register: Int, value: String): String =
    "const-string/jumbo v$register, ${value.toSmaliStringLiteral()}"

/**
 * Encode a Kotlin string as a smali string literal. The endpoint and header values used
 * here are ASCII, but escaping is kept complete so a future replacement cannot inject
 * an invalid string into the assembled DEX.
 */
private fun String.toSmaliStringLiteral(): String = buildString {
    append('"')
    this@toSmaliStringLiteral.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> when {
                character.code < 0x20 || character.code > 0x7e ->
                    append("\\u${character.code.toString(16).padStart(4, '0')}")
                else -> append(character)
            }
        }
    }
    append('"')
}
