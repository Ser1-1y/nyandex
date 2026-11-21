/*
 * Copyright 2026 Nyandex contributors.
 *
 * Nyandex: Yandex Music patches for Morphe Patcher.
 * Licensed under the GNU General Public License v3.0; see LICENSE and NOTICE.
 */

package app.nyandex.patches.yandexmusic.misc.lyrics

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.classDefByStrings
import app.morphe.patcher.patch.mutableClassDefBy
import app.morphe.patcher.patch.patch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.ExternalLabel
import app.nyandex.patches.yandexmusic.shared.Constants.COMPATIBILITY_YANDEX_MUSIC
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.util.logging.Logger

private val logger = Logger.getLogger("YandexMusicLyricsFallback")

internal const val LRCLIB_EXTENSION =
    "Lapp/nyandex/extension/yandexmusic/lyrics/LrcLibLyrics;"
internal const val REMEMBER_TRACK_METHOD = "nyandexRememberLyricsTrack"
internal const val FALLBACK_METHOD = "nyandexLrcLibFallback"

/** Signing key of the lyrics request. Only the lyrics repository contains it. */
private const val LYRICS_SIGN_KEY = "p93jhgh689SBReK6ghtw62"
private const val TRACK_TO_STRING = "Track{id='"
private const val LYRICS_HTTP_API = "Lcom/yandex/music/shared/lyrics/LyricsHttpApi;"
private const val FILES_API = "Lcom/yandex/music/shared/lyrics/FilesApi;"

private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"
private const val LIST = "Ljava/util/List;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"

val lyricsFallbackPatch = patch(
    name = "Lyrics fallback (LRCLIB)",
    description = "Loads lyrics from LRCLIB (lrclib.net) when Yandex sends its Plus subscription " +
        "notice instead of the lyrics. Real Yandex lyrics and errors are left untouched.",
) {
    compatibleWith(COMPATIBILITY_YANDEX_MUSIC)

    extendWith("extensions/lrclib-lyrics.mpe")

    execute {
        hookTrackConstructor()
        hookLyricsRepository()
    }
}

/**
 * Remember title, duration and artists of every track model. The lyrics repository only
 * receives the track id, and it is called from several screens, so the constructor is the one
 * place that sees every track.
 *
 * Track model (`b0x` in 2026.11.2, `oqv` in 2026.11.1): `a` id, `c` title, `e` duration in
 * milliseconds, `f` artists (their `toString()` returns the artist name).
 */
private fun hookTrackConstructor() {
    val trackClass = classDefByStrings(TRACK_TO_STRING, StringComparisonType.EQUALS)
        .singleOrNull { it.interfaces.contains("Landroid/os/Parcelable;") }
        ?: throw PatchException("Track model was not found")
    val type = trackClass.type

    trackClass.requireField("a", STRING)
    trackClass.requireField("c", STRING)
    trackClass.requireField("e", "J")
    trackClass.requireField("f", LIST)

    val mutableTrack = mutableClassDefBy(type)
    val constructor = mutableTrack.methods.singleOrNull { method ->
        method.name == "<init>" &&
            method.parameterTypes.size > 5 &&
            method.parameterTypes[0].toString() == STRING &&
            method.parameterTypes[2].toString() == STRING &&
            method.parameterTypes[4].toString() == "J" &&
            method.parameterTypes[5].toString() == LIST &&
            // The primary constructor; the Kotlin default-arguments overload delegates to it.
            method.instructionsList().any { instruction ->
                val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
                instruction.opcode == Opcode.IPUT_OBJECT &&
                    field?.definingClass == type && field.name == "a"
            }
    } ?: throw PatchException("Track model constructor was not found")

    val remember = ImmutableMethod(
        type,
        REMEMBER_TRACK_METHOD,
        listOf(ImmutableMethodParameter(type, null, null)),
        "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(6),
    ).toMutable()
    remember.addInstructions(
        0,
        """
            iget-object v0, p0, $type->a:$STRING
            iget-object v1, p0, $type->c:$STRING
            iget-wide v2, p0, $type->e:J
            iget-object v4, p0, $type->f:$LIST
            invoke-static/range {v0 .. v4}, $LRCLIB_EXTENSION->rememberTrack($STRING${STRING}J$LIST)V
            return-void
        """,
    )
    mutableTrack.methods.add(remember)

    val returns = constructor.instructionsList().mapIndexedNotNull { index, instruction ->
        index.takeIf { instruction.opcode == Opcode.RETURN_VOID }
    }
    if (returns.isEmpty()) throw PatchException("Track model constructor has no return")
    if (constructor.writesRegister(constructor.thisRegister())) {
        throw PatchException("Track model constructor reuses the this register")
    }

    returns.asReversed().forEach { index ->
        constructor.addInstructions(
            index,
            "invoke-static/range {p0 .. p0}, $type->$REMEMBER_TRACK_METHOD($type)V",
        )
    }
    logger.info("Hooked track model $type (${returns.size} return(s))")
}

/**
 * Yandex does not refuse lyrics to accounts without Plus with an error. The lyrics request
 * succeeds, and the downloaded lyrics file is a one-line notice («Подключите подписку на Плюс,
 * чтобы видеть тексты песен») that the app shows as the lyrics.
 *
 * In the lyrics repository (`h8j.a` in 2026.11.2, `yli.a` in 2026.11.1) the downloaded text is
 * parsed once, right before the method returns: `parser(trackInfo, info, format, content)`.
 * Right before that call the track id, format, download info and text are handed to a new
 * static helper. The extension returns LRCLIB lyrics only when the text is the Plus notice; the
 * helper then builds the lyrics with the app's own classes and the method returns them. For
 * every other text the helper returns null and the original parse runs unchanged. Error
 * results never reach this point.
 */
private fun hookLyricsRepository() {
    val repository = classDefByStrings(LYRICS_SIGN_KEY).singleOrNull { candidate ->
        candidate.methods.any { it.references(LYRICS_HTTP_API, "a") && it.references(FILES_API, "a") }
    } ?: throw PatchException("Lyrics repository was not found")
    val mutableRepository = mutableClassDefBy(repository.type)

    val method = mutableRepository.methods.singleOrNull { candidate ->
        candidate.returnType == OBJECT &&
            candidate.parameterTypes.size == 4 &&
            candidate.parameterTypes[0].toString() == STRING &&
            candidate.parameterTypes[2].toString() == "Ljava/lang/Long;" &&
            candidate.references(LYRICS_HTTP_API, "a") &&
            candidate.references(FILES_API, "a")
    } ?: throw PatchException("Lyrics download method was not found")
    val instructions = method.instructionsList()

    // parser(trackInfo, downloadInfo, format, content) -> lyrics, called once at the end.
    val parser = instructions.mapNotNull { it.methodReference() }
        .distinct()
        .singleOrNull { reference ->
            reference.parameterTypes.size == 4 &&
                reference.parameterTypes[3].toString() == STRING &&
                reference.parameterTypes.take(3).all { it.toString().startsWith("L") } &&
                reference.returnType.startsWith("L") &&
                reference.returnType != OBJECT
        } ?: throw PatchException("Lyrics parser call was not found")
    val trackInfoType = parser.parameterTypes[0].toString()
    val infoType = parser.parameterTypes[1].toString()
    val formatType = parser.parameterTypes[2].toString()

    if (method.parameterTypes[1].toString() != formatType) {
        throw PatchException("Lyrics format type mismatch: ${method.parameterTypes[1]} != $formatType")
    }

    // The success wrapper is the only class built with a single Object argument.
    val successType = instructions.mapNotNull { it.methodReference() }
        .filter { it.name == "<init>" && it.parameterTypes.map(Any::toString) == listOf(OBJECT) }
        .map { it.definingClass }
        .distinct()
        .singleOrNull()
        ?: throw PatchException("Lyrics success result type was not found")

    val infoClass = mutableClassDefBy(infoType)
    val infoConstructor = infoClass.methods.singleOrNull { it.name == "<init>" }
        ?: throw PatchException("Download info constructor was not found")
    val infoParameters = infoConstructor.parameterTypes.map(Any::toString)
    if (infoParameters.size != 5 ||
        infoParameters[0] != "I" || infoParameters[1] != STRING ||
        infoParameters[2] != STRING || infoParameters[3] != ARRAY_LIST
    ) {
        throw PatchException("Unexpected download info constructor: $infoParameters")
    }
    val majorType = infoParameters[4]
    mutableClassDefBy(majorType).methods.singleOrNull { candidate ->
        candidate.name == "<init>" &&
            candidate.parameterTypes.map(Any::toString) == listOf("I", STRING, STRING)
    } ?: throw PatchException("Lyrics major constructor (I, String, String) was not found in $majorType")

    mutableClassDefBy(trackInfoType).methods.singleOrNull { candidate ->
        candidate.name == "<init>" &&
            candidate.parameterTypes.map(Any::toString) == listOf(STRING, STRING, STRING)
    } ?: throw PatchException("Track info constructor (String, String, String) was not found")

    mutableClassDefBy(formatType).methods.singleOrNull { candidate ->
        candidate.name == "valueOf" && candidate.parameterTypes.map(Any::toString) == listOf(STRING)
    } ?: throw PatchException("$formatType.valueOf(String) was not found")

    // Final parse: `new-instance vT, trackInfo`, `invoke-direct {vT, vId, ...}`, then
    // `invoke-static {vT, vInfo, vFormat, vContent}, parser`. vT is written by the new-instance,
    // so it is free right before it.
    val parserIndex = instructions.indexOfFirst { it.methodReference() == parser }
    val parserCall = instructions[parserIndex] as FiveRegisterInstruction
    val trackInfoRegister = parserCall.registerC
    val infoRegister = parserCall.registerD
    val formatRegister = parserCall.registerE
    val contentRegister = parserCall.registerF

    val newTrackInfoIndex = (parserIndex - 1 downTo 0).firstOrNull { index ->
        val instruction = instructions[index]
        instruction.opcode == Opcode.NEW_INSTANCE &&
            (instruction as OneRegisterInstruction).registerA == trackInfoRegister &&
            ((instruction as ReferenceInstruction).reference as TypeReference).type == trackInfoType
    } ?: throw PatchException("Track info construction before the parser was not found")
    val trackInfoInit = instructions.subList(newTrackInfoIndex, parserIndex)
        .singleOrNull { it.methodReference()?.let { r -> r.definingClass == trackInfoType && r.name == "<init>" } == true }
        as? FiveRegisterInstruction
        ?: throw PatchException("Track info constructor call was not found")
    val trackIdRegister = trackInfoInit.registerD

    val previous = instructions[newTrackInfoIndex - 1]
    if (previous.opcode != Opcode.CHECK_CAST ||
        (previous as OneRegisterInstruction).registerA != contentRegister ||
        ((previous as ReferenceInstruction).reference as TypeReference).type != STRING
    ) {
        throw PatchException("Downloaded lyrics are not cast to String right before the parser")
    }

    val callRegisters = listOf(trackIdRegister, formatRegister, infoRegister, contentRegister)
    if (callRegisters.any { it > 15 } || trackInfoRegister in callRegisters) {
        throw PatchException("Unexpected parser registers: v$trackInfoRegister, $callRegisters")
    }

    // The helper builds the lyrics with the app's own classes from LRCLIB content.
    val repositoryType = repository.type
    val helper = ImmutableMethod(
        repositoryType,
        FALLBACK_METHOD,
        listOf(
            ImmutableMethodParameter(STRING, null, null),
            ImmutableMethodParameter(formatType, null, null),
            ImmutableMethodParameter(infoType, null, null),
            ImmutableMethodParameter(STRING, null, null),
        ),
        OBJECT,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        null,
        null,
        MutableMethodImplementation(17),
    ).toMutable()
    helper.addInstructions(
        0,
        """
            :try_start
            invoke-static/range {p0 .. p3}, $LRCLIB_EXTENSION->replacePaywall($STRING$OBJECT$OBJECT$STRING)[$STRING
            move-result-object v0
            if-eqz v0, :none
            const/4 v1, 0x0
            aget-object v1, v0, v1
            invoke-static {v1}, $formatType->valueOf($STRING)$formatType
            move-result-object v1
            const/4 v2, 0x1
            aget-object v2, v0, v2
            new-instance v3, $trackInfoType
            const/4 v4, 0x0
            move-object/from16 v12, p0
            invoke-direct {v3, v12, v4, v4}, $trackInfoType-><init>($STRING$STRING$STRING)V
            const-string v4, "LRCLIB"
            new-instance v10, $majorType
            const/4 v6, 0x0
            invoke-direct {v10, v6, v4, v4}, $majorType-><init>(I$STRING$STRING)V
            new-instance v9, $ARRAY_LIST
            invoke-direct {v9}, $ARRAY_LIST-><init>()V
            const-string v7, "lrclib"
            const-string v8, "lrclib"
            new-instance v5, $infoType
            invoke-direct/range {v5 .. v10}, $infoType-><init>(I$STRING$STRING$ARRAY_LIST$majorType)V
            invoke-static {v3, v5, v1, v2}, ${parser.definingClass}->${parser.name}($trackInfoType$infoType$formatType$STRING)${parser.returnType}
            move-result-object v11
            new-instance v12, $successType
            invoke-direct {v12, v11}, $successType-><init>($OBJECT)V
            return-object v12
            :try_end
            .catch Ljava/lang/Throwable; {:try_start .. :try_end} :failed
            :failed
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )
    mutableRepository.methods.add(helper)

    val scratch = "v$trackInfoRegister"
    method.addInstructionsWithLabels(
        newTrackInfoIndex,
        """
            invoke-static {v$trackIdRegister, v$formatRegister, v$infoRegister, v$contentRegister}, $repositoryType->$FALLBACK_METHOD($STRING$formatType$infoType$STRING)$OBJECT
            move-result-object $scratch
            if-eqz $scratch, :original
            return-object $scratch
        """,
        ExternalLabel("original", method.getInstruction(newTrackInfoIndex)),
    )

    logger.info(
        "Hooked lyrics repository $repositoryType.${method.name} " +
            "(parser ${parser.definingClass}.${parser.name}, info $infoType, format $formatType, " +
            "success $successType)",
    )
}

private fun ClassDef.requireField(name: String, type: String) {
    if (fields.none { it.name == name && it.type == type }) {
        throw PatchException("${this.type} has no field $name:$type")
    }
}

private fun Method.references(definingClass: String, name: String): Boolean =
    implementation?.instructions?.any { instruction ->
        val reference = instruction.methodReference()
        reference?.definingClass == definingClass && reference.name == name
    } == true

private fun Instruction.methodReference(): MethodReference? =
    (this as? ReferenceInstruction)?.reference as? MethodReference

private fun MutableMethod.instructionsList(): List<Instruction> =
    implementation?.instructions?.toList() ?: emptyList()

private fun MutableMethod.thisRegister(): Int {
    val implementation = implementation ?: throw PatchException("$name has no implementation")
    val parameterRegisters = parameterTypes.sumOf { type ->
        val t = type.toString()
        if (t == "J" || t == "D") 2 else 1
    }
    return implementation.registerCount - parameterRegisters - 1
}

private fun MutableMethod.writesRegister(register: Int): Boolean =
    instructionsList().any { instruction ->
        instruction.opcode.setsRegister() &&
            when (instruction) {
                is OneRegisterInstruction -> instruction.registerA == register
                else -> false
            }
    }
