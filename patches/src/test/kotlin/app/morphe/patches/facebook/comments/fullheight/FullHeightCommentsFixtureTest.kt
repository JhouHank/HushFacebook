/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.comments.fullheight

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.comments.summaries.descriptor
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Open comments at full height on every Facebook build the bundle declares. The comment sheet's
 * parameters have one constructor taking a float, and the register the patch computes for it is
 * the one the constructor stores in the parameters' float field. The hook hands that register to
 * the extension as a range call first thing and keeps the answer in it, and nothing of Facebook's
 * code moves. Five split-screen launchers are found, three by the factory Intent they build and
 * two by the reason they log, each a boolean method with a local register; each asks the
 * extension first, a yes answers false, and a no lands on Facebook's first instruction. Reads the
 * fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class FullHeightCommentsFixtureTest {
    private fun bundles(check: (File) -> Unit) {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    private fun holders(bundle: File, string: String) =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

    private fun sheet(bundle: File) {
        val params = FixtureDex.classes(bundle, setOf(POPOVER_PARAMS)).values.single()
        val constructor = sheetConstructor(params)
        val where = "${bundle.name}: ${constructor.descriptor()}"
        val height = floatRegister(constructor)
        val stores = constructor.implementation!!.instructions.filter { instruction ->
            instruction.opcode == Opcode.IPUT &&
                ((instruction as ReferenceInstruction).reference as FieldReference).let {
                    it.definingClass == POPOVER_PARAMS && it.type == "F"
                }
        }
        assertEquals("$where stores one float", 1, stores.size)
        assertEquals("$where: the float stored is the one in v$height", height, (stores.single() as TwoRegisterInstruction).registerA)

        val context = PatchContexts.of(listOf(params))
        val method = context.mutableClassDefBy(POPOVER_PARAMS).methods.single { it.descriptor() == constructor.descriptor() }
        val original = method.implementation!!.instructions.toList()
        method.raiseSheet(height)
        val patched = method.implementation!!.instructions.toList()
        assertEquals("$where gains two instructions", original.size + 2, patched.size)
        assertEquals("$where: the extension is asked first", Opcode.INVOKE_STATIC_RANGE, patched[0].opcode)
        assertEquals("$where: the extension is asked first", SHEET_HEIGHT, patched[0].reference())
        val call = patched[0] as RegisterRangeInstruction
        assertEquals("$where: with the height", height, call.startRegister)
        assertEquals("$where: and nothing else", 1, call.registerCount)
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[1].opcode)
        assertEquals("$where: in the height's register", height, (patched[1] as OneRegisterInstruction).registerA)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(2).map { it.opcode })
    }

    private fun launchers(bundle: File) {
        val name = bundle.name
        val launchers = splitScreenLaunchers(
            (holders(bundle, REEL_FOOTER) + holders(bundle, UNIT_NOT_MEASURED)).distinctBy { it.type },
            holders(bundle, NOT_ENTERED),
        )
        assertEquals("$name: five split-screen launchers", 5, launchers.size)
        assertEquals("$name: three build the split screen's Intent", 3, launchers.count(::buildsSplitScreen))

        for (launcher in launchers) {
            val where = "$name: ${launcher.descriptor()}"
            assertEquals("$where answers with a boolean", "Z", launcher.returnType)
            val owner = FixtureDex.classes(bundle, setOf(launcher.definingClass)).values.single()
            val context = PatchContexts.of(listOf(owner))
            val method = context.mutableClassDefBy(owner.type).methods.single { it.descriptor() == launcher.descriptor() }
            val original = method.implementation!!.instructions.toList()
            method.skipSplitScreen()
            val patched = method.implementation!!.instructions.toList()
            assertEquals("$where gains five instructions", original.size + 5, patched.size)
            assertEquals("$where: the extension is asked first", Opcode.INVOKE_STATIC, patched[0].opcode)
            assertEquals("$where: the extension is asked first", SKIP_SPLIT_SCREEN, patched[0].reference())
            assertEquals("$where: with nothing handed over", 0, (patched[0] as FiveRegisterInstruction).registerCount)
            assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[1].opcode)
            val register = (patched[1] as OneRegisterInstruction).registerA
            assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
            assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
            assertEquals("$where: a yes answers false", Opcode.CONST_4, patched[3].opcode)
            assertEquals("$where: a yes answers false", 0, (patched[3] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals("$where: a yes answers false", Opcode.RETURN, patched[4].opcode)
            assertEquals("$where: the answer returned is the false", register, (patched[4] as OneRegisterInstruction).registerA)
            assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
            assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
        }
    }

    @Test
    fun `each declared build hands the comment sheet's height to the extension where its parameters are built`() = bundles { bundle ->
        sheet(bundle)
    }

    @Test
    fun `each declared build asks before each split-screen launcher opens the video above the comments`() = bundles { bundle ->
        launchers(bundle)
    }
}
