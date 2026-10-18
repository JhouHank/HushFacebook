/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.patches.facebook.navigation.bottomtabbar

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.facebook.feed.FixtureDex
import app.morphe.patches.facebook.misc.settings.CREATE_WORDMARK_VIEW
import app.morphe.patches.facebook.misc.settings.LOGO_TOUCH_STAND_IN
import app.morphe.patches.facebook.misc.settings.WORDMARK_NAVIGATION_BAR
import app.morphe.patches.facebook.misc.settings.logoTouchListenerIndex
import app.morphe.patches.facebook.misc.settings.sendToStandIn
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The top bar's three hides (#181) on every Facebook build the bundle declares (arm64 and armv7,
 * whose Redex names differ): the bar's builder holding setupMessengerGlyphView, the Messenger button
 * class it makes and adds, that class's two count setters, and the logo site. Then the hooks go in
 * on the real classes, which also assembles their smali. Reads the fixture bundles from
 * HUSHFACEBOOK_FIXTURE_DIR and skips without it.
 */
class TopBarButtonsFixtureTest {
    private fun Method.code() = implementation!!.instructions.toList()

    private fun Instruction.call(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.let {
        "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}"
    }

    @Test
    fun `each declared build resolves the button, its count and the logo and takes the hooks`() {
        val versions = AppCompatibilities.facebook().single().targets.mapNotNull { it.version }.toSet()
        assertTrue("the bundle declares no Facebook build", versions.isNotEmpty())
        val checked = mutableListOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apkm" && it.name.contains("-$version-") }) {
                val name = bundle.name
                val bar = FixtureDex.classes(bundle, setOf(WORDMARK_NAVIGATION_BAR))[WORDMARK_NAVIGATION_BAR]
                    ?: throw AssertionError("$name: no $WORDMARK_NAVIGATION_BAR")

                val glyphBuilders = buildersHolding(bar, SETUP_MESSENGER_GLYPH_VIEW)
                assertEquals("$name: builders holding $SETUP_MESSENGER_GLYPH_VIEW", 1, glyphBuilders.size)
                val glyphBuilder = glyphBuilders.single()
                val logoBuilders = buildersHolding(bar, CREATE_WORDMARK_VIEW)
                assertEquals("$name: builders holding $CREATE_WORDMARK_VIEW", 1, logoBuilders.size)
                val logoBuilder = logoBuilders.single()

                // Messenger button: the class the builder makes holds the kept badgableGlyphView string
                // (positive control), and the bar's addView of it is found once, after the string.
                val site = messengerButtonSite(glyphBuilder)
                val originalCode = glyphBuilder.code()
                assertTrue("$name: the addView follows the glyph string", site.addView > originalCode.indexOfFirst {
                    (it as? ReferenceInstruction)?.reference?.toString()?.contains(SETUP_MESSENGER_GLYPH_VIEW) == true
                })
                val holders = FixtureDex.classesHolding(bundle, BADGABLE_GLYPH_VIEW).map { it.type }
                assertTrue("$name: ${site.buttonType} doesn't hold $BADGABLE_GLYPH_VIEW", site.buttonType in holders)
                val button = FixtureDex.classes(bundle, setOf(site.buttonType)).getValue(site.buttonType)
                val counts = countMethods(button)
                assertEquals("$name: the count setters", 2, counts.size)
                assertEquals("$name: the (I)V setter's int", 1, counts[0].intRegister)
                assertEquals("$name: the session setter's int", 2, counts[1].intRegister)
                // The setters really are the ones that set a count: the (I)V one is what the bar's own
                // EzX calls, so the bar holds a call to it.
                val countName = counts[0].name
                assertTrue("$name: nothing in the bar calls ${button.type}->$countName(I)V",
                    bar.methods.any { m -> m.implementation?.instructions?.any { it.call() == "${button.type}->$countName(I)V" } == true })

                // Apply the whole hook set the way the patch does: settings has already sent the logo's
                // touch listener through its stand-in when this patch runs.
                val context = PatchContexts.of(listOf(bar, button))
                val mutableBar = context.mutableClassDefBy(WORDMARK_NAVIGATION_BAR)
                val mutableLogo = mutableBar.findMutableMethodOf(logoBuilder)
                mutableLogo.sendToStandIn(logoTouchListenerIndex(logoBuilder), LOGO_TOUCH_STAND_IN)
                val logoBefore = mutableLogo.code().size
                val glyphBefore = mutableBar.findMutableMethodOf(glyphBuilder).code().size
                val sameMethod = glyphBuilder.name == logoBuilder.name &&
                    glyphBuilder.parameterTypes.map(CharSequence::toString) == logoBuilder.parameterTypes.map(CharSequence::toString)
                val countBefore = counts.map { c ->
                    context.mutableClassDefBy(button.type).methods.single {
                        it.name == c.name && it.parameterTypes.map(CharSequence::toString) == c.parameters
                    }.code().size
                }
                context.hideTopBarButtons()

                val patchedBar = context.mutableClassDefBy(WORDMARK_NAVIGATION_BAR)
                val builderAfter = patchedBar.findMutableMethodOf(glyphBuilder).code()
                val buttonHooks = builderAfter.withIndex().filter { it.value.call() == HIDE_MESSENGER_BUTTON_HOOK }
                assertEquals("$name: button hooks", 1, buttonHooks.size)
                val hookAt = buttonHooks.single().index
                assertEquals("$name: the button hook is range invoke-static", Opcode.INVOKE_STATIC_RANGE, builderAfter[hookAt].opcode)
                assertEquals("$name: the button hook hands over the register addView took",
                    site.register, (builderAfter[hookAt] as RegisterRangeInstruction).startRegister)
                assertEquals("$name: the hook sits right after the addView", ADD_VIEW_CALL, builderAfter[hookAt - 1].call())

                val logoAfter = patchedBar.findMutableMethodOf(logoBuilder).code()
                val logoHooks = logoAfter.withIndex().filter { it.value.call() == HIDE_FACEBOOK_LOGO_HOOK }
                assertEquals("$name: logo hooks", 1, logoHooks.size)
                assertEquals("$name: the logo hook is range invoke-static", Opcode.INVOKE_STATIC_RANGE, logoAfter[logoHooks.single().index].opcode)
                val expected = (if (sameMethod) glyphBefore else logoBefore) + (if (sameMethod) 2 else 1)
                assertEquals("$name: the builder grew by its hooks only", expected, logoAfter.size)

                counts.forEachIndexed { i, c ->
                    val after = context.mutableClassDefBy(button.type).methods.single {
                        it.name == c.name && it.parameterTypes.map(CharSequence::toString) == c.parameters
                    }.code()
                    assertEquals("$name: ${c.name} grew by two", countBefore[i] + 2, after.size)
                    assertEquals("$name: ${c.name} asks first", HIDE_MESSENGER_COUNT_HOOK, after[0].call())
                    assertEquals("$name: ${c.name} keeps the answer in the int", Opcode.MOVE_RESULT, after[1].opcode)
                    assertEquals("$name: ${c.name} hands over the int", registerOf(context.mutableClassDefBy(button.type).methods.single {
                        it.name == c.name && it.parameterTypes.map(CharSequence::toString) == c.parameters
                    }, c.intRegister),
                        (after[0] as RegisterRangeInstruction).startRegister)
                }
                assertNotEquals("$name: the button class and the bar differ", site.buttonType, WORDMARK_NAVIGATION_BAR)
                checked += "$name"
            }
        }
        assertTrue("a declared build has no fixture", checked.size >= versions.size)
        assertTrue("both ABIs should be checked, got $checked", checked.size >= 2)
    }

    /** The register smali calls p[parameter]: the method's registers less its parameter registers (this included), plus parameter. */
    private fun registerOf(method: com.android.tools.smali.dexlib2.iface.Method, parameter: Int): Int =
        method.implementation!!.registerCount - (method.parameterTypes.size + 1) + parameter

    @Test
    fun `the extension has the three hooks as public statics`() {
        val calls = ExtensionDex.classDef(TOP_BAR_BUTTONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(HIDE_MESSENGER_BUTTON_HOOK, HIDE_MESSENGER_COUNT_HOOK, HIDE_FACEBOOK_LOGO_HOOK)) {
            assertTrue("the extension declares no public static $hook: $calls", hook in calls)
        }
    }

    private companion object {
        const val ADD_VIEW_CALL = "Landroid/view/ViewGroup;->addView(Landroid/view/View;)V"
    }
}
