/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The ring between the overlapping reaction icons in a post's footer (#147). Read from 582, 2026-10-14;
 * the obfuscated names here are for reviewers, the code never writes one down.
 *
 * The footer's reactions add-on is a Litho component named "FDSInlineReactions" (arm64 LX/3Dz;,
 * armv7 LX/3Et;). Its one static factory for the facepile (arm64 A0F) reads the bling bar style's
 * clipColor (an enum whose fields keep their names: COLOR and MEDIA give ALWAYS_TRANSPARENT, SURFACE
 * gives SURFACE_BACKGROUND), resolves it through FDSColors (a class Redex keeps) on the row's context,
 * and hands the answer to the facepile drawable (arm64 LX/3E0;) as its ring colour. That drawable
 * cuts each ring out with clipPath when the colour has any transparency, and paints a disc of it
 * otherwise. A light answer on a black page is the white disc behind the icons.
 */

internal const val INLINE_REACTIONS = "FDSInlineReactions"

/** The bling bar style's field the ring's colour token comes from. */
internal const val CLIP_COLOR = "clipColor"

internal const val REACTION_RING = "Lapp/morphe/extension/facebook/theme/ReactionRing;->ring(I)I"

/**
 * The index of the move-result of each ring colour [method] resolves: an FDSColors call answering an
 * int, after a read of a `clipColor` field and before any other such read.
 */
internal fun reactionRingReads(method: Method): List<Int> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    val reads = mutableListOf<Int>()
    code.forEachIndexed { at, instruction ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference
        if (instruction.opcode != Opcode.IGET_OBJECT || field?.name != CLIP_COLOR) return@forEachIndexed
        val resolve = (at + 1 until code.size).firstOrNull { index ->
            val call = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
            call != null && code[index].opcode.name.startsWith("invoke")
        } ?: return@forEachIndexed
        val call = (code[resolve] as ReferenceInstruction).reference as MethodReference
        if (call.definingClass == FDS_COLORS && call.returnType == "I" && code.getOrNull(resolve + 1)?.opcode == Opcode.MOVE_RESULT) {
            reads += resolve + 1
        }
    }
    return reads
}

/** Whether [method] already hands the ring's colour to the extension. */
private fun handsRingColour(method: Method): Boolean =
    method.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference)?.toString() == REACTION_RING } == true

/**
 * Hands the ring colour FDSInlineReactions resolves for its facepile to the extension, which in dark
 * mode with AMOLED or Material You in the build gives back a clear one, so the facepile cuts its rings
 * and the page shows through. AMOLED and Material You both call this; the second call finds the hook
 * in place. Refuses unless the component has one method with one such read.
 */
internal fun BytecodePatchContext.hookReactionRing() {
    val components = classDefByStrings(INLINE_REACTIONS, StringComparisonType.EQUALS)
    val component = components.singleOrNull()
        ?: throw PatchException("${components.size} classes are named $INLINE_REACTIONS, expected the footer's reactions")
    val factories = component.methods.filter { reactionRingReads(it).isNotEmpty() }
    val factory = factories.singleOrNull()
        ?: throw PatchException("$INLINE_REACTIONS has ${factories.size} methods resolving a $CLIP_COLOR, expected the facepile's one")
    val parameters = factory.parameterTypes.map(CharSequence::toString)
    val method = mutableClassDefBy(component.type).methods.single {
        it.name == factory.name && it.parameterTypes.map(CharSequence::toString) == parameters
    }
    if (handsRingColour(method)) return
    method.recolourReactionRing()
}

/** [hookReactionRing] for the factory itself: the extension right after the one read, on its register. */
internal fun MutableMethod.recolourReactionRing() {
    val reads = reactionRingReads(this)
    if (reads.size != 1) {
        throw PatchException("$definingClass->$name resolves ${reads.size} ring colours, expected one")
    }
    val moveResult = reads.single()
    val register = (implementation!!.instructions[moveResult] as OneRegisterInstruction).registerA
    addInstructions(
        moveResult + 1,
        """
            invoke-static/range { v$register .. v$register }, $REACTION_RING
            move-result v$register
        """,
    )
}
