package com.d4viddf.hyperbridge.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the clone detection used by the notification service and the island
 * overlay with package ids and labels produced by real dual-app, second-space,
 * parallel and work-profile implementations.
 */
class NotificationCloneDetectorTest {

    // ---------------------------------------------------------------------
    //  Clone packages as shipped by common cloners / ROMs
    // ---------------------------------------------------------------------

    @Test
    fun `clone packages with a suffix segment are detected`() {
        val clones = listOf(
            "com.whatsapp.clone",
            "com.whatsapp.clon",
            "com.instagram.android.parallel",
            "com.tencent.ig.dualspace",
            "com.samsung.android.messaging.securefolder",
            "com.android.chrome.secondspace",
            "com.spotify.music.dualapp",
            "com.discord.workprofile"
        )
        clones.forEach { packageName ->
            assertTrue("$packageName should be a clone", NotificationCloneDetector.isCloneApp(packageName))
        }
    }

    @Test
    fun `clone packages glued with underscores or without separators are detected`() {
        val clones = listOf(
            "com.whatsapp_clone",
            "com.whatsapp_dual",
            "com.facebook.katana_parallel",
            "com.whatsappclone",
            "com.instagramparallel",
            "com.telegramdualapp"
        )
        clones.forEach { packageName ->
            assertTrue("$packageName should be a clone", NotificationCloneDetector.isCloneApp(packageName))
        }
    }

    @Test
    fun `regular apps are not flagged as clones`() {
        val originals = listOf(
            "com.whatsapp",
            "com.instagram.android",
            "org.telegram.messenger",
            "com.google.android.gm",
            "com.microsoft.office.outlook",
            "com.zhiliaoapp.musically",
            "com.spotify.music",
            "com.netflix.mediaclient",
            "com.sec.android.app.sbrowser",
            "com.duolingo",
            "com.multiplayer.game",
            "com.discord",
            "com.whatsapp.w4b"
        )
        originals.forEach { packageName ->
            assertFalse("$packageName should not be a clone", NotificationCloneDetector.isCloneApp(packageName))
        }
    }

    // ---------------------------------------------------------------------
    //  Visible labels added by cloners
    // ---------------------------------------------------------------------

    @Test
    fun `spanish and english clone labels are detected`() {
        val labels = listOf(
            "WhatsApp clon",
            "Clon de WhatsApp",
            "WhatsApp Clone",
            "WhatsApp (Dual)",
            "Instagram Parallel",
            "Telegram dual space",
            "WhatsApp Trabajo",
            "WhatsApp perfil de trabajo",
            "Segundo espacio: WhatsApp",
            "WhatsApp espejo",
            "WhatsApp gemelo",
            "WhatsApp clonado",
            "Facebook (Secure Folder)",
            "WhatsApp · Clon"
        )
        labels.forEach { label ->
            assertTrue("$label should be a clone label", NotificationCloneDetector.isCloneLabel(label))
        }
    }

    @Test
    fun `normal app names are not clone labels`() {
        val labels = listOf(
            "WhatsApp",
            "WhatsApp Business",
            "Instagram",
            "Telegram",
            "Gmail",
            "Outlook",
            "Spotify",
            "Netflix",
            "Redes sociales",
            "Google",
            "Discord",
            "Segunda mano",
            "Workout"
        )
        labels.forEach { label ->
            assertFalse("$label should not be a clone label", NotificationCloneDetector.isCloneLabel(label))
        }
    }

    @Test
    fun `clone detection combines package and label`() {
        // Same package id as the original, only the visible label is renamed.
        assertTrue(NotificationCloneDetector.isClone("com.whatsapp", "WhatsApp clon"))
        assertTrue(NotificationCloneDetector.isClone("com.whatsapp", "Clon de WhatsApp"))
        // Derived package id, regular label.
        assertTrue(NotificationCloneDetector.isClone("com.whatsapp.clone", "WhatsApp"))
        // Neither clue.
        assertFalse(NotificationCloneDetector.isClone("com.whatsapp", "WhatsApp"))
        assertFalse(NotificationCloneDetector.isClone("com.whatsapp", null))
        assertFalse(NotificationCloneDetector.isClone("com.whatsapp", "  "))
    }

    @Test
    fun `component names are checked too`() {
        assertTrue(
            NotificationCloneDetector.isClone(
                packageName = "com.example.messaging",
                label = "Mensajes",
                componentName = "com.example.messaging.Clon"
            )
        )
    }

    // ---------------------------------------------------------------------
    //  Recovering the original app
    // ---------------------------------------------------------------------

    @Test
    fun `base package strips clone suffixes`() {
        assertEquals("com.whatsapp", NotificationCloneDetector.basePackage("com.whatsapp.clone"))
        assertEquals("com.whatsapp", NotificationCloneDetector.basePackage("com.whatsapp.clon"))
        assertEquals("com.whatsapp", NotificationCloneDetector.basePackage("com.whatsapp.dualspace"))
        assertEquals("com.whatsapp", NotificationCloneDetector.basePackage("com.whatsapp_clone"))
        assertEquals("com.whatsapp", NotificationCloneDetector.basePackage("com.whatsapp.Clone"))
        // Chained suffixes collapse to the original package.
        assertEquals("com.whatsapp", NotificationCloneDetector.basePackage("com.whatsapp.dual.clone"))
        // Regular packages are left untouched.
        assertEquals("com.whatsapp", NotificationCloneDetector.basePackage("com.whatsapp"))
        assertEquals("org.telegram.messenger", NotificationCloneDetector.basePackage("org.telegram.messenger"))
    }

    @Test
    fun `base label removes clone markers and connectors`() {
        assertEquals("whatsapp", NotificationCloneDetector.baseLabel("WhatsApp clon"))
        assertEquals("whatsapp", NotificationCloneDetector.baseLabel("Clon de WhatsApp"))
        assertEquals("whatsapp", NotificationCloneDetector.baseLabel("WhatsApp (Dual)"))
        assertEquals("whatsapp", NotificationCloneDetector.baseLabel("WhatsApp · Clon"))
        assertEquals("instagram", NotificationCloneDetector.baseLabel("Instagram Parallel"))
        assertEquals("whatsapp", NotificationCloneDetector.baseLabel("WhatsApp"))
    }

    @Test
    fun `same base app matches clones against their original`() {
        assertTrue(NotificationCloneDetector.isSameBaseApp("WhatsApp clon", "WhatsApp"))
        assertTrue(NotificationCloneDetector.isSameBaseApp("Clon de WhatsApp", "WhatsApp"))
        assertTrue(NotificationCloneDetector.isSameBaseApp("WhatsApp (Dual)", "WhatsApp"))
        assertTrue(NotificationCloneDetector.isSameBaseApp("Instagram Parallel", "Instagram"))
        // Repackaged clients keep the brand inside the name.
        assertTrue(NotificationCloneDetector.isSameBaseApp("GBWhatsApp", "WhatsApp"))

        assertFalse(NotificationCloneDetector.isSameBaseApp("WhatsApp", "Instagram"))
        assertFalse(NotificationCloneDetector.isSameBaseApp("Clon de WhatsApp", "Instagram"))
    }

    @Test
    fun `blank labels never match another app`() {
        assertFalse(NotificationCloneDetector.isSameBaseApp("clon", "WhatsApp"))
        assertFalse(NotificationCloneDetector.isSameBaseApp("WhatsApp clon", ""))
    }

    // ---------------------------------------------------------------------
    //  Second space / dual-app labels across ROMs
    // ---------------------------------------------------------------------

    @Test
    fun `second space labels from other ROMs are detected`() {
        val labels = listOf(
            // MIUI / HyperOS
            "Second space",
            "Segundo espacio",
            "Espacio dual",
            "Apps duales",
            "Mensajería dual",
            // Huawei / Honor (App Twin)
            "App Twin",
            "Twin apps",
            "App gemela",
            // OPPO / Realme / OnePlus (ColorOS)
            "Dual Space",
            "Parallel Space",
            "App paralela",
            // Samsung Secure Folder
            "Secure Folder",
            "Carpeta segura",
            // Generic cloners
            "Clone App",
            "WhatsApp (Segundo espacio)"
        )
        labels.forEach { label ->
            assertTrue("$label should be a clone label", NotificationCloneDetector.isCloneLabel(label))
        }
    }

    @Test
    fun `second space packages from other ROMs are detected`() {
        val clones = listOf(
            "com.whatsapp.secondspace",
            "com.whatsapp.dualspace",
            "com.whatsapp.parallel",
            "com.whatsapp.miui.secondspace",
            "com.android.messaging.dualspace"
        )
        clones.forEach { packageName ->
            assertTrue("$packageName should be a clone", NotificationCloneDetector.isCloneApp(packageName))
        }
    }

    // ---------------------------------------------------------------------
    //  Work profile labels
    // ---------------------------------------------------------------------

    @Test
    fun `work profile labels are detected`() {
        val labels = listOf(
            "WhatsApp (Work)",
            "WhatsApp (Trabajo)",
            "Work profile",
            "Perfil de trabajo",
            "WhatsApp · Trabajo",
            "WhatsApp perfil de trabajo"
        )
        labels.forEach { label ->
            assertTrue("$label should be a clone label", NotificationCloneDetector.isCloneLabel(label))
        }
    }

    @Test
    fun `a work profile keeps the original package id`() {
        // Work profile / second space instances usually reuse the package of the
        // original app: the profile is identified by the Android user, so the
        // package must stay a valid stand-in for the original app.
        assertFalse(NotificationCloneDetector.isCloneApp("com.whatsapp"))
        assertEquals("com.whatsapp", NotificationCloneDetector.basePackage("com.whatsapp"))
        assertTrue(NotificationCloneDetector.isSameBaseApp("WhatsApp (Trabajo)", "WhatsApp"))
    }

    @Test
    fun `ordinary labels using the same words are not flagged`() {
        val labels = listOf(
            "Instagram",
            "Twitter",
            "Segunda mano",
            "Cámara profesional",
            "Workout",
            "Space shooter"
        )
        labels.forEach { label ->
            assertFalse("$label should not be a clone label", NotificationCloneDetector.isCloneLabel(label))
        }
    }
}
