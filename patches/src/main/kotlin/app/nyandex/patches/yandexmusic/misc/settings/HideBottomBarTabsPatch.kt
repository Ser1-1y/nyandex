/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.patches.yandexmusic.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByStrings
import app.morphe.patcher.patch.classDefForEach
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.nyandex.patches.yandexmusic.shared.Constants.COMPATIBILITY_YANDEX_MUSIC
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.logging.Logger

private val logger = Logger.getLogger("YandexMusicHideBottomBarTabs")

internal const val BOTTOM_TABS_HOOK = "Lapp/nyandex/extension/yandexmusic/settings/BottomTabsHook;"

/** Constant names of the app's bottom tab enum (`v64` in 2026.11.2). */
private val TAB_NAMES = setOf("LANDING", "PODCASTS", "CONCERTS", "KIDS", "MY_MUSIC", "WAVE")

private val SET_TYPES = setOf(
    "Ljava/util/LinkedHashSet;", "Ljava/util/HashSet;", "Ljava/util/Set;", "Ljava/util/Collection;",
)

val hideBottomBarTabsPatch = patch(
    name = "Hide bottom bar tabs",
    description = "Adds switches to the Nyandex settings page to hide bottom bar tabs " +
        "(My Vibe, Main, Podcasts, Concerts, For kids, Collection). Applies after a restart.",
) {
    compatibleWith(COMPATIBILITY_YANDEX_MUSIC)

    dependsOn(nyandexSettingsPatch)

    execute {
        // The settings page shows the tab switches only when this patch is applied.
        mutableClassDefBy(NYANDEX_SETTINGS_CLASS).methods
            .single { it.name == "bottomTabsAvailable" }
            .addInstructions(0, "const/4 v0, 0x1\nreturn v0")

        hookBottomBar()
    }
}

/**
 * The bottom bar view (`bwk.w()` in 2026.11.2) collects the tabs to show into a
 * `LinkedHashSet` of the tab enum (`v64`), one `add` per tab with its conditions, and then turns
 * the set into tab items. Right after the last `add`, the set is handed to the extension, which
 * removes the tabs hidden on the Nyandex settings page.
 */
private fun hookBottomBar() {
    val tabEnum = classDefByStrings("MY_MUSIC").singleOrNull { candidate ->
        candidate.superclass == "Ljava/lang/Enum;" &&
            TAB_NAMES.all { name -> candidate.hasString(name) }
    } ?: throw PatchException("Bottom tab enum was not found")
    val tabType = tabEnum.type

    // The bar method has no string of its own, so every class is scanned for the add pattern.
    val candidates = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.tabAdds(tabType).size >= 4) candidates += classDef to method
        }
    }

    val (ownerClass, ownerMethod) = candidates.singleOrNull()
        ?: throw PatchException(
            "Expected one bottom bar method, found ${candidates.map { "${it.first.type}.${it.second.name}" }}",
        )

    val method = mutableClassDefBy(ownerClass.type).methods.single {
        it.name == ownerMethod.name && it.parameterTypes == ownerMethod.parameterTypes &&
            it.returnType == ownerMethod.returnType
    }
    val adds = method.tabAdds(tabType)
    val lastAdd = adds.last()
    val setRegister = (method.implementation!!.instructions.elementAt(lastAdd) as FiveRegisterInstruction).registerC

    // Insert after the add (and its move-result, if the boolean result is kept).
    val instructions = method.implementation!!.instructions.toList()
    var insertAt = lastAdd + 1
    if (instructions.getOrNull(insertAt)?.opcode == Opcode.MOVE_RESULT) insertAt++

    method.addInstructions(
        insertAt,
        "invoke-static {v$setRegister}, $BOTTOM_TABS_HOOK->filterTabs(Ljava/util/Set;)V",
    )
    logger.info("Hooked bottom bar tabs in ${ownerClass.type}.${method.name} (${adds.size} tab adds)")
}

private fun ClassDef.hasString(value: String): Boolean = methods.any { method ->
    method.implementation?.instructions?.any { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    } == true
}

/** Indexes of `set.add(TabEnum.X)` calls: an `sget-object` of a tab constant passed to `add`. */
private fun Method.tabAdds(tabType: String): List<Int> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    val tabRegisters = mutableMapOf<Int, Int>() // register -> index of the sget-object
    val result = mutableListOf<Int>()
    instructions.forEachIndexed { index, instruction ->
        if (instruction.opcode == Opcode.SGET_OBJECT) {
            val field = (instruction as ReferenceInstruction).reference as FieldReference
            val register = (instruction as OneRegisterInstruction).registerA
            if (field.definingClass == tabType && field.type == tabType) {
                tabRegisters[register] = index
            } else {
                tabRegisters.remove(register)
            }
            return@forEachIndexed
        }
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        if (reference != null && reference.name == "add" && reference.definingClass in SET_TYPES &&
            instruction is FiveRegisterInstruction && instruction.registerCount == 2 &&
            instruction.registerD in tabRegisters
        ) {
            result += index
        }
    }
    return result
}
