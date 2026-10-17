/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.misc.sharesheet

import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.facebook.feed.methodsNaming
import app.morphe.patches.facebook.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.facebook.misc.extension.facebookExtensionPatch
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/*
 * The share sheet's row of people to send to, read from 583 on 2026-10-17 (#169). The obfuscated
 * names are for reviewers; the code finds the row by a kept name and a method shape.
 *
 * The sheet's body render (583 arm64 LX/Zya;->render, armv7 LX/ZkL;->render, the one loading
 * "messenger_suggested_recipients_hscroll") builds the row of round profile pictures in a helper
 * of its own class, `A0E(?, ?, List, int, boolean)`, which returns the row's component, and adds
 * that component to the list of children it is collecting. It does so twice: once for the row of
 * people and once for the shimmer placeholder drawn while they load ("..._grid_glimmer"). Both are
 * `invoke-static` of that helper, `move-result-object`, then `AbstractCollection.add` in the next
 * two instructions. The add is the hook: with the switch on, the row never joins the children.
 */

/** The extension class the hook asks. */
internal const val SHARE_SHEET_PEOPLE = "$EXTENSION_PACKAGE/misc/ShareSheetPeople;"
internal const val ADD_PEOPLE_ROW =
    "$SHARE_SHEET_PEOPLE->addRow(Ljava/util/AbstractCollection;Ljava/lang/Object;)Z"

/** A Litho component's `render`, a name the framework keeps. */
private const val RENDER = "render"

private const val PATCH = "Share sheet items (people row)"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The share sheet's row of people goes through the extension's ShareSheetPeople. Nameless, so Share
 * sheet items carries it and its fixture tests run it on its own. Everything is found before
 * anything changes, and it refuses unless the render has exactly the row and its placeholder.
 */
internal val shareSheetPeoplePatch = bytecodePatch {
    dependsOn(facebookExtensionPatch)

    execute {
        hookShareSheetPeople()
    }
}

/** Where a people row joins its parent's children: the `add` instruction's index and its two registers. */
internal data class PeopleRowAdd(val index: Int, val children: Int, val row: Int)

internal fun BytecodePatchContext.hookShareSheetPeople() {
    val bodies = methodsNaming(SHARE_BODY_ANCHOR) { it.name == RENDER }
    val body = bodies.singleOrNull() ?: refuse("expected one render loading \"$SHARE_BODY_ANCHOR\", found ${bodies.size}")
    val adds = peopleRowAdds(body)
    if (adds.size != 2) refuse("expected the people row and its placeholder in the share sheet body, found ${adds.size}")

    val method = mutableClassDefBy(body.definingClass).findMutableMethodOf(body)
    adds.forEach { add ->
        method.replaceInstruction(add.index, "invoke-static { v${add.children}, v${add.row} }, $ADD_PEOPLE_ROW")
    }
}

private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/**
 * Each place [render] adds a people row to its children: a call to a static helper of its own class
 * that takes `(?, ?, List, int, boolean)` and returns what `render` returns, whose result goes
 * straight into an `AbstractCollection.add`.
 */
internal fun peopleRowAdds(render: Method): List<PeopleRowAdd> {
    val code = render.implementation?.instructions?.toList().orEmpty()
    val found = mutableListOf<PeopleRowAdd>()
    for (index in 0 until code.size - 2) {
        val call = code[index].reference() as? MethodReference ?: continue
        if (code[index].opcode != Opcode.INVOKE_STATIC && code[index].opcode != Opcode.INVOKE_STATIC_RANGE) continue
        if (call.definingClass != render.definingClass || call.returnType != render.returnType) continue
        val parameters = call.parameterTypes.map(CharSequence::toString)
        if (parameters.size != 5 || parameters.subList(2, 5) != listOf("Ljava/util/List;", "I", "Z")) continue
        val moved = code[index + 1]
        if (moved.opcode != Opcode.MOVE_RESULT_OBJECT) continue
        val add = code[index + 2]
        val target = add.reference() as? MethodReference ?: continue
        if (add.opcode != Opcode.INVOKE_VIRTUAL || target.definingClass != "Ljava/util/AbstractCollection;" ||
            target.name != "add" || target.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/Object;")
        ) continue
        val registers = add as? FiveRegisterInstruction ?: continue
        if (registers.registerCount != 2) continue
        val row = (moved as OneRegisterInstruction).registerA
        if (registers.registerD != row) continue
        found += PeopleRowAdd(index + 2, registers.registerC, registers.registerD)
    }
    return found
}
