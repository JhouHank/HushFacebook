/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.externalbrowser

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.facebook.misc.extension.requireLocals
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/*
 * Facebook's browser's two link history writers, read from 582 on 2026-10-10. The obfuscated names
 * here are for reviewers; the code finds each factory by the kept writer class it creates.
 *
 * Each writer has one factory, an instance method taking the browser's launch state and answering
 * the writer, or null when the launch intent and Facebook's server config leave the feature off
 * (582 LX/IeR;->ATc for the signals writer, LX/IeS;->ATc for the page data writer). Neither factory
 * reads your Enhanced browsing setting. That's read later, where the writers' records are sent
 * (CONTRIBUTING.md has the map). The extension's LinkHistory.hold() goes first in each factory, and a
 * yes answers null, the factory's own off answer, so the writer never exists.
 *
 * The page data goes out through one upload, read from 583 on 2026-10-20: an instance method
 * returning void (583 LX/Ipa;->A00) that builds IABLinkHistorySaveMutation from the page's address,
 * title, session and times and runs it, or logs "No callback executor for link history request" and
 * returns. Two places call it, the browser callback service and a newer link history event handler
 * (583 LX/R3K;->CXX) that the writer hook may not cover, and both hand it a callback that only logs.
 * LinkHistory.holdUpload() goes first in it, and a yes returns before anything is built or sent.
 */

private const val PATCH = "Open links in external browser"

internal val LINK_HISTORY_WRITERS = listOf(
    // Records the links you open with when each page started, became usable and finished loading.
    "Lcom/facebook/browser/lite/extensions/browserhistory/LinkHistorySignalsWriter;",
    // Records each page's address and title, which Facebook saves as your link history.
    "Lcom/facebook/browser/lite/extensions/browserhistory/LinkHistoryUiDataWriter;",
)

internal const val HOLD_LINK_HISTORY = "Lapp/morphe/extension/facebook/misc/LinkHistory;->hold()Z"

internal const val HOLD_LINK_HISTORY_UPLOAD = "Lapp/morphe/extension/facebook/misc/LinkHistory;->holdUpload()Z"

/** What the link history upload logs when it has nowhere to send its answer. */
internal const val LINK_HISTORY_UPLOAD_ANCHOR = "No callback executor for link history request"

/** The save request the upload builds, which its cast check names. */
internal const val LINK_HISTORY_SAVE_MUTATION = "IABLinkHistorySaveMutation"

/**
 * Whether [method] is a factory of [writer]: an instance method outside it that takes one parameter
 * (the browser's launch state), creates the writer, and can answer null on its own, which is what
 * makes the hook's null an answer its callers already handle.
 */
internal fun isWriterFactory(method: Method, writer: String): Boolean {
    if (method.definingClass == writer || !method.returnType.startsWith("L") || method.parameterTypes.size != 1 ||
        AccessFlags.STATIC.isSet(method.accessFlags)
    ) {
        return false
    }
    val code = method.implementation?.instructions?.toList() ?: return false
    val creates = code.any {
        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == writer
    }
    return creates && answersNull(code)
}

/** Whether [code] returns a register it loaded with 0. */
private fun answersNull(code: List<Instruction>): Boolean {
    val nulls = code.filter { it.opcode == Opcode.CONST_4 && (it as NarrowLiteralInstruction).narrowLiteral == 0 }
        .map { (it as OneRegisterInstruction).registerA }.toSet()
    return code.any { it.opcode == Opcode.RETURN_OBJECT && (it as OneRegisterInstruction).registerA in nulls }
}

/**
 * The one factory of each writer in [LINK_HISTORY_WRITERS], found in one walk over the app. None, or
 * two, for either writer stops the patch rather than ship a switch that leaves one writer running.
 */
internal fun BytecodePatchContext.linkHistoryFactories(): List<Method> {
    val found = LINK_HISTORY_WRITERS.associateWith { mutableListOf<Method>() }
    classDefForEach { classDef ->
        if (classDef.type.startsWith("Lapp/morphe/")) return@classDefForEach
        for (method in classDef.methods) {
            for (writer in LINK_HISTORY_WRITERS) if (isWriterFactory(method, writer)) found.getValue(writer) += method
        }
    }
    val wrong = found.filterValues { it.size != 1 }
    if (wrong.isNotEmpty()) {
        throw PatchException(
            "$PATCH: expected one factory of each link history writer, found " +
                wrong.entries.joinToString { (writer, factories) -> "${factories.size} of $writer" },
        )
    }
    return found.values.map { it.single() }
}

/** The mutable copy of [factory] (or the upload), to hook. */
internal fun BytecodePatchContext.mutableFactory(factory: Method): MutableMethod =
    mutableClassDefBy(factory.definingClass).findMutableMethodOf(factory)

/** First thing in a writer's factory: ask the extension, and answer null on a yes. Otherwise the factory runs as before. */
internal fun MutableMethod.holdLinkHistory() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_LINK_HISTORY
            move-result v0
            if-eqz v0, :build
            const/4 v0, 0x0
            return-object v0
        """,
        ExternalLabel("build", getInstruction(0)),
    )
}

/**
 * Whether [method] is the link history upload: an instance method returning void that holds
 * [LINK_HISTORY_UPLOAD_ANCHOR] and names [LINK_HISTORY_SAVE_MUTATION].
 */
internal fun isLinkHistoryUpload(method: Method): Boolean {
    if (method.returnType != "V" || AccessFlags.STATIC.isSet(method.accessFlags)) return false
    val strings = method.implementation?.instructions
        ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }.orEmpty()
    return LINK_HISTORY_UPLOAD_ANCHOR in strings && strings.any { LINK_HISTORY_SAVE_MUTATION in it }
}

/** The one link history upload, or a refusal. */
internal fun BytecodePatchContext.linkHistoryUpload(): Method {
    val uploads = classDefByStrings(LINK_HISTORY_UPLOAD_ANCHOR, StringComparisonType.EQUALS)
        .filterNot { it.type.startsWith("Lapp/morphe/") }.distinctBy { it.type }
        .flatMap { it.methods }.filter(::isLinkHistoryUpload)
    return uploads.singleOrNull()
        ?: throw PatchException("$PATCH: expected one link history upload, found ${uploads.size}")
}

/** First thing in the upload: ask the extension, and return on a yes. Otherwise it runs as before. */
internal fun MutableMethod.holdLinkHistoryUpload() {
    requireLocals(PATCH, 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $HOLD_LINK_HISTORY_UPLOAD
            move-result v0
            if-eqz v0, :send
            return-void
        """,
        ExternalLabel("send", getInstruction(0)),
    )
}
