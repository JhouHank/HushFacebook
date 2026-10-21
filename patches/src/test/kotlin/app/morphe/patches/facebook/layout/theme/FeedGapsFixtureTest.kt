/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.layout.theme

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #206: on AMOLED a post with no photo ran into the next one, since the gap between feed rows
 * shows the page and the page is black. Each row is a LithoView, which draws through Litho's
 * ComponentHost.dispatchDraw, so on each declared build that method has to hand the host and its
 * canvas to FeedGaps.drawRow before it draws anything.
 *
 * Read from 583 (2026-10-21): LithoView extends BaseMountingView, which extends ComponentHost, whose
 * final dispatchDraw (8 registers) is the only one on the way down to ViewGroup's.
 */
class FeedGapsFixtureTest {
    private val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()

    private fun bundles(version: String) = Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }

    private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    @Test
    fun `each Litho row hands its canvas to FeedGaps before it draws, on each declared build`() {
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in bundles(version)) {
                val name = bundle.name
                val classes = FixtureDex.classes(bundle, setOf(COMPONENT_HOST, MOUNTING_VIEW, LITHO_VIEW))
                val host = classes.getValue(COMPONENT_HOST)

                // A row is a LithoView, and nothing between it and the host draws its own way.
                assertEquals("$name: LithoView extends BaseMountingView", MOUNTING_VIEW, classes.getValue(LITHO_VIEW).superclass)
                assertEquals("$name: which extends ComponentHost", COMPONENT_HOST, classes.getValue(MOUNTING_VIEW).superclass)
                for (between in listOf(LITHO_VIEW, MOUNTING_VIEW)) {
                    assertTrue("$name: $between draws through the host's dispatchDraw",
                        classes.getValue(between).methods.none { it.name == "dispatchDraw" })
                }
                assertTrue("$name: the host is a ViewGroup", isViewGroup(bundle, host))

                val draw = host.methods.single(::isHostDraw)
                val registers = draw.implementation!!.registerCount
                val context = PatchContexts.of(listOf(host))
                with(context) { hookFeedGaps() }
                val after = context.mutableClassDefBy(COMPONENT_HOST).methods.single(::isHostDraw).body()

                val hook = after.first()
                assertEquals("$name: drawRow runs first", Opcode.INVOKE_STATIC_RANGE, hook.opcode)
                assertEquals("$name: and it's FeedGaps.drawRow", FEED_GAP_ROW,
                    ((hook as ReferenceInstruction).reference as MethodReference).toString())
                assertEquals("$name: from p0, the host", registers - 2, (hook as RegisterRangeInstruction).startRegister)
                assertEquals("$name: and p1, its canvas", 2, hook.registerCount)
                assertEquals("$name: the rest of the method is as it was", draw.body().size + 1, after.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** Whether [host]'s superclasses reach ViewGroup, so the verifier takes it for drawRow's first parameter. */
    private fun isViewGroup(bundle: File, host: ClassDef): Boolean {
        var type = host.superclass
        repeat(4) {
            if (type == VIEW_GROUP) return true
            type = FixtureDex.classes(bundle, setOfNotNull(type))[type]?.superclass ?: return false
        }
        return false
    }

    private companion object {
        const val MOUNTING_VIEW = "Lcom/facebook/litho/BaseMountingView;"
        const val LITHO_VIEW = "Lcom/facebook/litho/LithoView;"
        const val VIEW_GROUP = "Landroid/view/ViewGroup;"
    }
}
