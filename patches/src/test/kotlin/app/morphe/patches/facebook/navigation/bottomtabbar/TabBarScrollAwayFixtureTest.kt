/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The bars' scroll-away on every Facebook build the bundle declares. First the one check the main
 * screen's start asks before it builds the bottom bar's scroll-away entry, the bar's position check
 * inside it (the class's own Activity check that reads the TriState override), and room for the
 * hook's one register. Then the patch on that class: a kept bar's no returned right after the
 * position check passes, then a sliding bar's yes, a no going on to Facebook's next instruction, and
 * nothing else in the method moved. Second the top bar: the method that adds it to the scroll-away
 * views, the name it reads off the entry it makes, and each move of that entry handing its distance
 * through the extension first. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips
 * without it.
 */
class TabBarScrollAwayFixtureTest {
    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun declaredBundles(): Map<String, List<File>> {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        return versions.associateWith { version -> Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") } }
    }

    private fun Method.sameAs(other: MethodReference) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun Method.sameAs(other: Method) = definingClass == other.definingClass && name == other.name &&
        returnType == other.returnType && parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun Instruction.calls() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun Instruction.loads() = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

    private fun nonExtension(bundle: File, string: String) =
        FixtureDex.classesHolding(bundle, string).filterNot { it.type.startsWith(EXTENSION_CLASSES) }

    /** Everything the patch reads on [bundle]: the gate's holders and class, the top views' class and the entry. */
    private class Pieces(val gateHolders: List<ClassDef>, val gateClass: ClassDef, val topHolders: List<ClassDef>, val entry: ClassDef) {
        fun context() = PatchContexts.of((gateHolders + gateClass + topHolders + entry).distinctBy { it.type })
    }

    private fun pieces(bundle: File): Pieces {
        val name = bundle.name
        val holders = nonExtension(bundle, BOTTOM_TABS_CONTAINER)
        assertTrue("$name: nothing loads \"$BOTTOM_TABS_CONTAINER\"", holders.isNotEmpty())
        val gateClass = FixtureDex.classes(bundle, setOf(scrollAwayGate(holders).definingClass)).values.single()
        val topHolders = nonExtension(bundle, SET_TOP_VIEWS)
        assertTrue("$name: nothing is traced as \"$SET_TOP_VIEWS\"", topHolders.isNotEmpty())
        val entryType = scrollAwayEntryName(topScrollAwayViews(topHolders)).definingClass
        return Pieces(holders, gateClass, topHolders, FixtureDex.classes(bundle, setOf(entryType)).values.single())
    }

    @Test
    fun `each declared build gates the bar's scroll-away once, and the extension goes after the position check`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val pieces = pieces(bundle)
                val gateReference = scrollAwayGate(pieces.gateHolders)
                val gateClass = pieces.gateClass
                val gate = gateClass.methods.single { it.sameAs(gateReference) }
                assertEquals("$name: the check takes the Activity and answers a boolean", listOf(ACTIVITY),
                    gate.parameterTypes.map(CharSequence::toString))

                val at = barAtBottomCheck(gate, gateClass)
                val original = gate.code()
                val position = original[at - 3]
                val positionCheck = gateClass.methods.single {
                    it.sameAs((position as ReferenceInstruction).reference as MethodReference)
                }
                assertTrue("$name: the position check doesn't read Facebook's override",
                    positionCheck.code().any { it.calls()?.startsWith(FB_SHARED_PREFERENCES) == true && it.calls()!!.endsWith(TRI_STATE) })
                assertEquals("$name: the branch that leaves when the bar isn't at the bottom", Opcode.IF_EQZ, original[at - 1].opcode)
                // The hook borrows one register, a local: this and the Activity are the two parameter registers.
                assertTrue("$name: ${gate.definingClass}->${gate.name} has no local register for the hook",
                    gate.implementation!!.registerCount - 2 >= 1)

                val context = pieces.context()
                tabBarScrollAwayPatch.execute(context)
                val patchedMethod = context.mutableClassDefBy(gateClass.type).methods.single { it.sameAs(gateReference) }
                val patched = patchedMethod.code()
                val where = "$name: ${gate.definingClass}->${gate.name}"
                assertEquals("$where gains nine instructions", original.size + 9, patched.size)
                assertEquals("$where: Facebook's code up to the position check stays", original.take(at).map { it.opcode },
                    patched.take(at).map { it.opcode })
                assertEquals("$where: the extension is asked about a kept bar first", STAYS_PUT, patched[at].calls())
                assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[at + 1].opcode)
                val register = (patched[at + 1] as OneRegisterInstruction).registerA
                assertTrue("$where: the hook writes v$register, which isn't a local", register < gate.localRegisterCount())
                for (offset in listOf(2, 3, 4, 6, 7, 8)) {
                    assertEquals("$where: the hook keeps to v$register", register, (patched[at + offset] as OneRegisterInstruction).registerA)
                }
                assertEquals("$where: a bar that isn't kept goes on to the scroll-away switch", Opcode.IF_EQZ, patched[at + 2].opcode)
                assertEquals("$where: a kept bar answers no", Opcode.CONST_16, patched[at + 3].opcode)
                assertEquals("$where: a kept bar's no is returned", Opcode.RETURN, patched[at + 4].opcode)
                assertEquals("$where: then the extension is asked about sliding", SLIDES_AWAY, patched[at + 5].calls())
                assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[at + 6].opcode)
                assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[at + 7].opcode)
                assertEquals("$where: a yes is returned", Opcode.RETURN, patched[at + 8].opcode)
                assertEquals("$where: Facebook's code after the hook stays", original.drop(at).map { it.opcode },
                    patched.drop(at + 9).map { it.opcode })

                val flow = ControlFlow.of(patchedMethod)
                assertEquals("$where: a bar that isn't kept skips the kept bar's return", setOf(at + 3, at + 5),
                    flow.normal[at + 2].toSet())
                assertEquals("$where: a no lands on Facebook's next instruction", setOf(at + 8, at + 9),
                    flow.normal[at + 7].toSet())
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }

    @Test
    fun `each declared build hands the top bar's moves through the extension`() {
        val checked = mutableSetOf<String>()
        for ((version, bundles) in declaredBundles()) {
            for (bundle in bundles) {
                val name = bundle.name
                val pieces = pieces(bundle)
                val topViews = topScrollAwayViews(pieces.topHolders)
                assertTrue("$name: ${topViews.definingClass}->${topViews.name} doesn't add the top bar",
                    topViews.code().any { it.loads() == "app_header_container" })
                assertTrue("$name: nothing names the bar of tabs at the top",
                    nonExtension(bundle, "title_bar_tab_bar_container").isNotEmpty())
                val entryName = scrollAwayEntryName(topViews)
                assertEquals("$name: the entry's name is a String", "Ljava/lang/String;", entryName.type)

                val moves = entryMoves(pieces.entry)
                assertTrue("$name: ${pieces.entry.type} has no moves", moves.isNotEmpty())
                val context = pieces.context()
                tabBarScrollAwayPatch.execute(context)
                val patchedEntry = context.mutableClassDefBy(pieces.entry.type)
                for ((move, distance) in moves) {
                    val where = "$name: ${move.definingClass}->${move.name}"
                    val original = move.code()
                    val patched = patchedEntry.methods.single { it.sameAs(move) }.code()
                    assertEquals("$where gains three instructions", original.size + 3, patched.size)
                    val read = patched[0]
                    assertEquals("$where: the entry's name is read first", Opcode.IGET_OBJECT, read.opcode)
                    assertEquals("$where: the entry's name is read first", entryName.toString(),
                        ((read as ReferenceInstruction).reference as FieldReference).toString())
                    val local = (read as TwoRegisterInstruction).registerA
                    assertTrue("$where: the name goes in v$local, which isn't a local", local < move.localRegisterCount())
                    assertTrue("$where: the name goes in v$local, the distance's register", local != distance)
                    assertEquals("$where: then handed to the extension", TOP_BAR_OFFSET, patched[1].calls())
                    assertEquals("$where: the distance comes back", Opcode.MOVE_RESULT, patched[2].opcode)
                    assertEquals("$where: the distance comes back", distance, (patched[2] as OneRegisterInstruction).registerA)
                    assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(3).map { it.opcode })
                }
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", declaredBundles().keys, checked)
    }
}
