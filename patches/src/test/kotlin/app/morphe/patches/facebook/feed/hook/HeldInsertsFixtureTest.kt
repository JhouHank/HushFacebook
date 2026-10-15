/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.feed.hook

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The held rows hook on every Facebook build the bundle declares: exactly one class holding the
 * RecyclerBinder's applyReadyBatches trace answers the hook's rule, its row holder and Litho's
 * ComponentTree give the helper its reads, and the patch adds the helper to the holder and puts the
 * extension's answer in front of the if-nez on the holder's own, with the rest of applyReadyBatches
 * left as it was. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class HeldInsertsFixtureTest {
    private fun Method.body(): List<Instruction> = implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference)?.toString()

    @Test
    fun `each declared build lets a row in that only an accessibility change holds back`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val owners = FixtureDex.classesHolding(bundle, APPLY_READY_BATCHES)
                val sites = owners.mapNotNull(::heldInsertSite)
                assertEquals("$name: RecyclerBinders among ${owners.map { it.type }}", 1, sites.size)
                val site = sites.single()
                val holderType = site.layoutCheck.definingClass
                val classes = FixtureDex.classes(bundle, setOf(holderType, COMPONENT_TREE))
                val holder = classes.getValue(holderType)
                val parts = laidOutParts(holder, site.layoutCheck, classes.getValue(COMPONENT_TREE))
                assertNotNull("$name: the helper's reads in ${site.layoutCheck}", parts)
                parts!!
                assertEquals("$name: the layouts are the tree's", setOf(COMPONENT_TREE), parts.layouts.map { it.definingClass }.toSet())
                assertEquals("$name: the size test is the layout's", parts.layouts[0].type, parts.fits.definingClass)

                val before = site.method.body()
                val context = PatchContexts.of(owners.filter { it.type != holderType } + holder + classes.getValue(COMPONENT_TREE))
                heldInsertsPatch.execute(context)

                // The helper: static, takes the holder, and reads the tree, both specs, both layouts
                // and the layout's size test, which never compares the accessibility state.
                val helper = context.mutableClassDefBy(holderType).methods.single { it.name == LAID_OUT_HELPER }
                assertTrue("$name: the helper is static", AccessFlags.STATIC.isSet(helper.accessFlags))
                assertEquals("$name: the helper's parameters", listOf(holderType), helper.parameterTypes.map(CharSequence::toString))
                val reads = helper.body().mapNotNull { it.call() }
                for (field in listOf(parts.tree, parts.width, parts.height) + parts.layouts) {
                    assertTrue("$name: the helper reads $field", field.toString() in reads)
                }
                assertEquals("$name: the helper tests each layout", 2, reads.count { it == parts.fits.toString() })

                val patched = context.mutableClassDefBy(site.method.definingClass).methods.single {
                    it.name == site.method.name &&
                        it.parameterTypes.map(CharSequence::toString) == site.method.parameterTypes.map(CharSequence::toString)
                }
                val after = patched.body()
                val added = after.subList(site.index, site.index + 6)
                assertEquals(
                    "$name: the hook",
                    listOf(Opcode.IF_NEZ, Opcode.IGET_OBJECT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT),
                    added.map { it.opcode },
                )
                assertEquals("$name: the holder read again", site.holderField.toString(), added[1].call())
                assertEquals("$name: the helper", "$holderType->$LAID_OUT_HELPER($holderType)Z", added[2].call())
                assertEquals("$name: the extension", LET_IN, added[4].call())
                assertEquals("$name: one ask", 1, after.count { it.call() == LET_IN })
                assertEquals("$name: the holder's own if-nez after it", Opcode.IF_NEZ, after[site.index + 6].opcode)

                // A holder with its layout skips the hook and goes straight to its own if-nez.
                val skip = ControlFlow.of(patched).normal[site.index].single { it != site.index + 1 }
                assertEquals("$name: where a laid out holder goes", site.index + 6, skip)
                assertEquals("$name: the rest of applyReadyBatches", before.map { it.opcode },
                    after.filterIndexed { index, _ -> index !in site.index until site.index + 6 }.map { it.opcode })
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
