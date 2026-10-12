/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.progressbar

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.feed.aidetected.EXTENSION_CLASSES
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Tap the bar to seek on every Facebook build the bundle declares (issue #125): the scrubber's own
 * SeekBar class, found from the base's check of the bar it reads, has one press check on its
 * thumb-only path, whose other way is Facebook's call to Android's SeekBar. The hook goes on the
 * press way, hands the bar to the extension, and a yes lands on that call while a no goes on to
 * the thumb's bounds. Nothing of Facebook's code moves. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TapToSeekFixtureTest {
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

    private fun Method.code(): List<Instruction> = implementation!!.instructions.toList()

    private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference?.toString()

    @Test
    fun `each declared build hands a press off the bar's thumb to the SeekBar when asked`() = bundles { bundle ->
        val name = bundle.name
        val looks = vddScrubber(FixtureDex.classesHolding(bundle, SCRUBBER_PASSIVE).filterNot { it.type.startsWith(EXTENSION_CLASSES) })
        val progress = progressUpdate(
            FixtureDex.classesHolding(bundle, SCRUBBER_UPDATE).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        val barType = scrubberSeekBar(progress.base, looks.barField)
        val bar = FixtureDex.classes(bundle, setOf(barType)).values.single()
        assertTrue("$name: $barType isn't a SeekBar of Facebook's", bar.superclass == SEEK_BAR)
        val press = thumbOnlyPress(bar)
        val where = "$name: $barType->onTouchEvent"
        val original = press.method.code()
        assertEquals("$where: the press way reads the thumb", GET_THUMB, original[press.at].reference())
        assertEquals("$where: the other way is Android's SeekBar", SEEK_BAR_TOUCH, original[press.toSeekBar].reference())

        val context = PatchContexts.of(listOf(bar))
        val method = context.mutableClassDefBy(barType).methods.single { it.sameAs(press.method) }
        method.seeksFromAnywhere(press)
        val patched = method.code()
        val at = press.at
        assertEquals("$where gains three instructions", original.size + 3, patched.size)
        assertEquals("$where: Facebook's code up to the press way stays", original.take(at).map { it.opcode },
            patched.take(at).map { it.opcode })
        assertEquals("$where: the extension is asked", Opcode.INVOKE_STATIC_RANGE, patched[at].opcode)
        assertEquals("$where: the extension is asked", SEEKS_FROM_ANYWHERE, patched[at].reference())
        val self = method.implementation!!.registerCount - 2
        assertEquals("$where: with the bar itself", self, (patched[at] as RegisterRangeInstruction).startRegister)
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[at + 1].opcode)
        val register = (patched[at + 1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
        assertEquals("$where: a yes jumps", Opcode.IF_NEZ, patched[at + 2].opcode)
        assertEquals("$where: from the same register", register, (patched[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$where: Facebook's code after the hook stays", original.drop(at).map { it.opcode },
            patched.drop(at + 3).map { it.opcode })

        val toSeekBar = press.toSeekBar + 3
        assertEquals("$where: a yes lands on Android's SeekBar", SEEK_BAR_TOUCH, patched[toSeekBar].reference())
        assertEquals("$where: a no goes on to the thumb's bounds", setOf(at + 3, toSeekBar),
            ControlFlow.of(method).normal[at + 2].toSet())
    }
}
