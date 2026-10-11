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
import app.morphe.util.RegisterLiveness
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Keep the progress bar's Reels hooks on every Facebook build the bundle declares: the scrubber's
 * minimum length check answering yes while kept, so a short reel gets a bar, its progress update
 * having Facebook's own time writer refresh the time label once the update has moved the bar (never
 * before its position checks, issue #120), the wait before its next update
 * cut to a frame, and the Reels footer's bar check reading a short reel's length as long enough, so
 * the caption leaves room for the bar. Each hook asks the extension first or works in Facebook's own
 * register, a no lands on Facebook's code, and nothing of Facebook's code moves. Reads the fixture
 * bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class KeepProgressBarReelsFixtureTest {
    private companion object {
        const val SET_PROGRESS = "Landroid/widget/ProgressBar;->setProgress(I)V"
        const val GET_MAX = "Landroid/widget/ProgressBar;->getMax()I"
    }

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

    private class Scrubber(val looks: ScrubberLooks, val scrubber: ClassDef, val progress: ProgressUpdate)

    private fun scrubber(bundle: File): Scrubber {
        val holders = FixtureDex.classesHolding(bundle, SCRUBBER_PASSIVE).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        val looks = vddScrubber(holders)
        val scrubber = holders.single { it.type == looks.type }
        val progress = progressUpdate(
            FixtureDex.classesHolding(bundle, SCRUBBER_UPDATE).filterNot { it.type.startsWith(EXTENSION_CLASSES) },
        )
        assertEquals("${bundle.name}: the progress update isn't on the scrubber's base", scrubber.superclass, progress.base.type)
        return Scrubber(looks, scrubber, progress)
    }

    @Test
    fun `each declared build gives a short reel a bar while kept`() = bundles { bundle ->
        val name = bundle.name
        val scrubber = scrubber(bundle).scrubber
        val check = minimumLengthCheck(scrubber)
        val where = "$name: ${scrubber.type}->${check.name}"
        assertTrue("$where doesn't load \"$LENGTH_CHECK_SURFACE\"", check.code().any { it.reference() == LENGTH_CHECK_SURFACE })
        // The hook borrows v0: this, the length and the boolean are the parameter registers.
        assertTrue("$where has no local register for the hook", check.implementation!!.registerCount - 3 >= 1)

        val context = PatchContexts.of(listOf(scrubber))
        val method = context.mutableClassDefBy(scrubber.type).methods.single { it.sameAs(check) }
        val original = method.code()
        method.longEnoughWhileKept()
        val patched = method.code()
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the extension is asked first", BARS_EVERY_REEL, patched[0].reference())
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a local", register < method.localRegisterCount())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: a yes answers true", 1, (patched[3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("$where: and returns it", Opcode.RETURN, patched[4].opcode)
        assertEquals("$where: from the same register", register, (patched[4] as OneRegisterInstruction).registerA)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
    }

    @Test
    fun `each declared build has Facebook's writer refresh the shown time once the bar has moved`() = bundles { bundle ->
        val name = bundle.name
        val found = scrubber(bundle)
        val looks = found.looks
        val progress = found.progress
        val base = progress.base
        val where = "$name: ${base.type}->${progress.update.name}"
        assertTrue("$where doesn't trace \"$SCRUBBER_UPDATE\"", progress.update.code().any { it.reference() == SCRUBBER_UPDATE })
        assertTrue("$name: ${base.type}->${progress.writeTime.name} sets no text",
            progress.writeTime.code().any { it.reference() == TEXT_VIEW_SET_TEXT })
        assertTrue("$name: the writer doesn't reach the views holder the time label is in",
            progress.writeTime.code().any { it.reference()?.startsWith(looks.labelField.substringBefore("->")) == true })
        // Issue #120: the writer moves the bar with no check of its own, so it may run only where Facebook's update
        // has found a usable position and length. The update reads the bar's maximum only past both checks.
        assertTrue("$name: the writer no longer moves the bar", progress.writeTime.code().any { it.reference() == SET_PROGRESS })
        val at = dragCheck(progress.update, progress)
        val inflow = ControlFlow.of(progress.update)
        val update = progress.update.code()
        val maxReads = update.indices.filter { update[it].reference() == GET_MAX }.toSet()
        assertTrue("$where reads no bar maximum", maxReads.isNotEmpty())
        val reached = mutableSetOf(0)
        val queue = ArrayDeque(listOf(0))
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next in maxReads) continue
            (inflow.normal[next] + inflow.exceptional[next]).forEach { if (reached.add(it)) queue += it }
        }
        assertTrue("$where reaches its drag check without reading the bar's maximum, before its position checks", at !in reached)

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(progress.update) }
        val original = method.code()
        method.refreshesTimeWithBar(progress, looks)
        val patched = method.code()
        val local = method.localRegisterCount()
        assertEquals("$where gains eight instructions", original.size + 8, patched.size)
        assertEquals("$where: Facebook's code up to the drag check stays", original.take(at).map { it.opcode },
            patched.take(at).map { it.opcode })
        assertEquals("$where: the scrubber moves into a local through the wide form", Opcode.MOVE_OBJECT_FROM16, patched[at].opcode)
        val register = (patched[at] as TwoRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register, which isn't a low local", register < minOf(local, 16))
        assertTrue("$where: the hook borrows v$register, which Facebook reads on",
            register !in RegisterLiveness.of(progress.update).liveInto(at))
        assertEquals("$where: from p0", local, (patched[at] as TwoRegisterInstruction).registerB)
        assertEquals("$where: the views holder is read", looks.viewsField, patched[at + 1].reference())
        assertEquals("$where: a missing holder goes on to Facebook", Opcode.IF_EQZ, patched[at + 2].opcode)
        assertEquals("$where: then the time label", looks.labelField, patched[at + 3].reference())
        assertEquals("$where: the extension is asked about the label", REFRESHES_TIME, patched[at + 4].reference())
        assertEquals("$where: its answer is kept", Opcode.MOVE_RESULT, patched[at + 5].opcode)
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[at + 6].opcode)
        assertEquals("$where: a yes runs Facebook's writer", "${base.type}->${progress.writeTime.name}(${base.type})V",
            patched[at + 7].reference())
        val call = patched[at + 7] as RegisterRangeInstruction
        assertEquals("$where: on this scrubber", listOf(local, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$where: Facebook's drag check and the rest stay", original.drop(at).map { it.opcode },
            patched.drop(at + 8).map { it.opcode })
        assertEquals("$where: the drag check comes right after the hook", Opcode.SGET_OBJECT, patched[at + 8].opcode)
        val flow = ControlFlow.of(method)
        assertEquals("$where: a missing holder lands on the drag check", setOf(at + 3, at + 8), flow.normal[at + 2].toSet())
        assertEquals("$where: a no lands on the drag check", setOf(at + 7, at + 8), flow.normal[at + 6].toSet())
        val into = patched.indices.filter { at + 8 in flow.normal[it] }.toSet()
        assertEquals("$where: something jumps to the drag check past the hook", setOf(at + 2, at + 6, at + 7), into)
    }

    @Test
    fun `each declared build moves the kept bar about once a frame`() = bundles { bundle ->
        val name = bundle.name
        val progress = scrubber(bundle).progress
        val base = progress.base
        val delay = frameDelay(progress)
        val where = "$name: ${base.type}->${delay.name}"
        assertTrue("$where isn't static", AccessFlags.STATIC.isSet(delay.accessFlags))
        assertTrue("$where isn't asked by the progress update",
            progress.update.code().any { it.reference() == "${base.type}->${delay.name}(${base.type})J" })
        // The hook borrows v0 and v1 for the wide answer: the scrubber is the one parameter register.
        assertTrue("$where has no two local registers for the hook", delay.implementation!!.registerCount - 1 >= 2)

        val context = PatchContexts.of(listOf(base))
        val method = context.mutableClassDefBy(base.type).methods.single { it.sameAs(delay) }
        val original = method.code()
        method.smoothWhileKept()
        val patched = method.code()
        assertEquals("$where gains five instructions", original.size + 5, patched.size)
        assertEquals("$where: the extension is asked first", SMOOTHS_BAR, patched[0].reference())
        val register = (patched[1] as OneRegisterInstruction).registerA
        assertTrue("$where: the hook writes v$register and the next, which aren't locals", register + 1 < method.localRegisterCount())
        assertEquals("$where: a no goes on to Facebook", Opcode.IF_EQZ, patched[2].opcode)
        assertEquals("$where: a yes waits a frame", SMOOTH_FRAME_DELAY_MS.toLong(), (patched[3] as WideLiteralInstruction).wideLiteral)
        assertEquals("$where: as a long", Opcode.CONST_WIDE_16, patched[3].opcode)
        assertEquals("$where: and returns it", Opcode.RETURN_WIDE, patched[4].opcode)
        assertEquals("$where: Facebook's code stays", original.map { it.opcode }, patched.drop(5).map { it.opcode })
        assertEquals("$where: a no lands on Facebook's first instruction", setOf(3, 5), ControlFlow.of(method).normal[2].toSet())
    }

    @Test
    fun `each declared build leaves a short reel's caption room for the bar while kept`() = bundles { bundle ->
        val name = bundle.name
        fun classOf(type: String) = FixtureDex.classes(bundle, setOf(type)).values.singleOrNull()
        val holders = FixtureDex.classesHolding(bundle, REEL_FOOTER).filterNot { it.type.startsWith(EXTENSION_CLASSES) }
        assertTrue("$name: nothing loads \"$REEL_FOOTER\"", holders.isNotEmpty())
        val (owner, check) = footerBarCheck(holders, ::classOf)
        val where = "$name: ${owner.type}->${check.name}"
        val read = lengthRead(check)!!
        val register = (check.code()[read] as OneRegisterInstruction).registerA
        val compare = check.code().drop(read + 1).first { it.opcode == Opcode.IF_LT } as TwoRegisterInstruction
        assertEquals("$where compares something else first", register, compare.registerA)

        val context = PatchContexts.of(listOf(owner))
        val method = context.mutableClassDefBy(owner.type).methods.single { it.sameAs(check) }
        val original = method.code()
        method.roomForEveryReel()
        val patched = method.code()
        assertEquals("$where gains two instructions", original.size + 2, patched.size)
        assertEquals("$where: Facebook's code up to the length stays", original.take(read + 1).map { it.opcode },
            patched.take(read + 1).map { it.opcode })
        assertEquals("$where: the length goes to the extension", REEL_LENGTH, patched[read + 1].reference())
        val call = patched[read + 1] as RegisterRangeInstruction
        assertEquals("$where: in its own register", listOf(register, 1), listOf(call.startRegister, call.registerCount))
        assertEquals("$where: and its answer replaces it", Opcode.MOVE_RESULT, patched[read + 2].opcode)
        assertEquals("$where: in the same register", register, (patched[read + 2] as OneRegisterInstruction).registerA)
        assertEquals("$where: Facebook's code after it stays", original.drop(read + 1).map { it.opcode },
            patched.drop(read + 3).map { it.opcode })
    }
}
