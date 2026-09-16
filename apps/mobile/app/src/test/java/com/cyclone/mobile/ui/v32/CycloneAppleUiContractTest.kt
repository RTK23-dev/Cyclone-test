package com.cyclone.mobile.ui.v32

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the 4.4.8 Apple-like visual cleanup: one shell, one theme, one page inset. */
class CycloneAppleUiContractTest {
    @Test fun retiredVersionedShellsAreGone() {
        val names = uiDir().list()?.toSet().orEmpty()
        listOf(
            "CycloneMobileV23App.kt",
            "CycloneMobileV24App.kt",
            "CycloneMobileV25App.kt",
            "CycloneMobileV26App.kt",
            "CycloneMobileV27App.kt",
            "CycloneMobileV291App.kt",
            "CycloneMobileV292App.kt",
            "SetupExperience.kt",
        ).forEach { name ->
            assertFalse("retired UI shell still present: $name", names.contains(name))
        }
        assertTrue(File(uiDir(), "CycloneMobileApp.kt").isFile)
        val theme = File(uiDir(), "CycloneMobileApp.kt").readText()
        assertTrue(theme.contains("CycloneIdentityTheme"))
        assertFalse(theme.contains("dynamicLightColorScheme"))
        assertFalse(theme.contains("fun CycloneMobileApp()"))
    }

    @Test fun homeAndAskShareOneAttachmentMenu() {
        val home = source("CycloneHomeComposer.kt")
        val ask = source("CycloneV39AiChatPage.kt")
        assertTrue(home.contains("internal fun CycloneAttachmentTools"))
        assertTrue(home.contains("\"Camera\""))
        assertTrue(home.contains("\"Files & photos\""))
        assertTrue(home.contains("\"Share screen\""))
        assertTrue(ask.contains("CycloneAttachmentTools("))
        assertFalse(ask.contains("Text(\"File\")"))
        assertFalse(ask.contains("Text(\"Photo\")"))
    }

    @Test fun pagesDoNotDoublePadTheTabBar() {
        listOf(
            "CycloneV32App.kt",
            "CycloneProfilesPage.kt",
            "CycloneRoutinesPage.kt",
            "CycloneV39BrainPage.kt",
            "CycloneFollowMePage.kt",
        ).forEach { name ->
            val text = source(name)
            assertTrue("$name should use cyclonePageInsets", text.contains("cyclonePageInsets("))
            assertFalse("$name still hard-codes 96.dp page padding", text.contains("bottom = 96.dp"))
        }
    }

    @Test fun activityDoesNotStackAnUnthemedRescueBarAboveScaffold() {
        val main = sequenceOf(
            File("src/main/java/com/cyclone/mobile/MainActivity.kt"),
            File("apps/mobile/app/src/main/java/com/cyclone/mobile/MainActivity.kt"),
        ).first { it.isFile }.readText()
        assertFalse(main.contains("ProfileRescueBar()"))
        assertTrue(main.contains("CycloneMobileV32App()"))
    }

    private fun uiDir(): File = sequenceOf(
        File("src/main/java/com/cyclone/mobile/ui"),
        File("apps/mobile/app/src/main/java/com/cyclone/mobile/ui"),
    ).first { it.isDirectory }

    private fun source(name: String): String {
        val relative = "src/main/java/com/cyclone/mobile/ui/v32/$name"
        return sequenceOf(File(relative), File("apps/mobile/app/$relative"))
            .first { it.isFile }.readText()
    }
}
