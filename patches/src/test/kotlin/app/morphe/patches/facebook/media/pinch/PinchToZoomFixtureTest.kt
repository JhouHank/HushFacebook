/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.media.pinch

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.media.taptoplay.FRAGMENT_ACTIVITY
import app.morphe.patches.facebook.media.taptoplay.touchDispatches
import app.morphe.patches.facebook.misc.extension.SETTINGS_STATUS
import app.morphe.patches.facebook.misc.extension.localRegisterCount
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pinch to zoom's two hooks on every Facebook build the bundle declares: FbFragmentActivity's one
 * touch dispatch and ProfileFragment's one onViewCreated(View, Bundle). Then the patch itself, run
 * on those classes, with each call first and reading the registers the method keeps its arguments
 * in. Reads the fixture bundles from HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class PinchToZoomFixtureTest {
    private val Instruction.call: MethodReference?
        get() = (this as? ReferenceInstruction)?.reference as? MethodReference

    private fun Instruction.registers(): List<Int> = when (this) {
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> emptyList()
    }

    @Test
    fun `each declared build has both hooks once, and the patch puts each call first`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (fixture in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = fixture.name
                val classes = FixtureDex.classes(fixture, setOf(FRAGMENT_ACTIVITY, PROFILE_FRAGMENT))
                assertEquals("$name: classes missing", emptySet<String>(),
                    setOf(FRAGMENT_ACTIVITY, PROFILE_FRAGMENT) - classes.keys)
                val activity = classes.getValue(FRAGMENT_ACTIVITY)
                val fragment = classes.getValue(PROFILE_FRAGMENT)
                assertEquals("$name: touch dispatches", 1, touchDispatches(activity).size)
                assertEquals("$name: profile onViewCreated", 1, profileViewCreated(fragment).size)

                val context = PatchContexts.of(listOf(activity, fragment, ExtensionDex.classDef(SETTINGS_STATUS)))
                pinchToZoomPatch.execute(context)
                fun patched(method: Method) = context.mutableClassDefBy(method.definingClass).methods.single {
                    it.name == method.name && it.returnType == method.returnType &&
                        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
                }

                // Every touch goes to the extension with the screen and the event, and on unchanged.
                val dispatch = patched(touchDispatches(activity).single())
                val touch = dispatch.implementation!!.instructions.toList()
                assertEquals("$name: the pinch hook", PINCH_TOUCH, touch[0].call.toString())
                assertEquals("$name: the pinch hook's registers",
                    listOf(dispatch.localRegisterCount(), dispatch.localRegisterCount() + 1), touch[0].registers())
                assertTrue("$name: dispatchTouchEvent still hands the event on",
                    touch.drop(1).any { it.call?.name == "dispatchTouchEvent" })

                // The profile's root view, p1, goes to the extension before the fragment builds on it.
                val created = patched(profileViewCreated(fragment).single())
                val setup = created.implementation!!.instructions.toList()
                assertEquals("$name: the profile hook", PINCH_PROFILE, setup[0].call.toString())
                assertEquals("$name: the profile hook's register", listOf(created.localRegisterCount() + 1), setup[0].registers())
                assertEquals("$name: onViewCreated keeps its own code",
                    profileViewCreated(fragment).single().implementation!!.instructions.count() + 1, setup.size)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }
}
