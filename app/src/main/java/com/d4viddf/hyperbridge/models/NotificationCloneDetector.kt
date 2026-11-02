package com.d4viddf.hyperbridge.models

import java.text.Normalizer

/**
 * Heuristic clone-app detection shared by the notification service and the
 * island overlay. It recognises package-name tokens and visible-name markers
 * used by dual-app, second-space, parallel and work-profile features across
 * ROMs, including Spanish labels such as "WhatsApp clon" or "Clon de WhatsApp".
 */
object NotificationCloneDetector {

    /** Tokens that appear as a full segment in clone/dual package ids. */
    private val PACKAGE_TOKENS = setOf(
        "clone", "clon", "clonado", "clonada", "dual", "dualspace",
        "parallel", "paralelo", "second", "secondspace", "appclone",
        "dualapp", "workprofile", "securefolder", "multiapp", "appcloner"
    )

    /** Words added to the visible name by clone/dual-space features. */
    private val LABEL_TOKENS = setOf(
        "clone", "clon", "clonado", "clonada", "dual", "dualspace", "duales",
        "parallel", "paralelo", "paralela", "second", "secondspace", "appclone",
        "dualapp", "twin", "gemelo", "gemela", "espejo", "work", "profile",
        "perfil", "trabajo", "secure", "folder", "segura", "multiapp", "instancia"
    )

    /** Connectors that carry no identity when comparing app names. */
    private val CONNECTORS = setOf(
        "de", "del", "la", "el", "los", "las", "para", "and", "of", "the",
        "app", "apps", "cuenta", "espacio", "space", "account", "instance"
    )

    /** Markers that can be glued to the original package or name. */
    private val COMPACT_MARKERS = listOf(
        "clone", "clon", "parallel", "paralelo", "second",
        "secondspace", "appclone", "dualapp", "multiapp", "workprofile"
    )

    /** Package suffixes stripped to recover the original app package. */
    private val PACKAGE_SUFFIXES = listOf(
        ".clone", ".clon", ".dual", ".dualspace", ".parallel", ".second",
        ".secondspace", ".appclone", ".dualapp", ".workprofile",
        ".securefolder", ".multiapp", "_clone", "_dual", "_parallel"
    )

    /** True when the package name or the visible label looks like a clone. */
    fun isClone(packageName: String, label: String? = null, componentName: String? = null): Boolean {
        if (isCloneApp(packageName)) return true
        if (!label.isNullOrBlank() && isCloneLabel(label)) return true
        if (!componentName.isNullOrBlank() && isCloneLabel(componentName)) return true
        return false
    }

    /** True when the package name contains a known clone/dual-space token. */
    fun isCloneApp(packageName: String): Boolean {
        val tokens = tokens(packageName)
        if (tokens.any { it in PACKAGE_TOKENS }) return true
        // Compact ids such as com.whatsappclone or com.instagramparallel.
        return tokens.any { token ->
            COMPACT_MARKERS.any { marker ->
                token.length > marker.length + 2 && token.endsWith(marker)
            }
        }
    }

    /** True when the visible app or component name looks like a clone label. */
    fun isCloneLabel(label: String): Boolean {
        val tokens = tokens(label)
        if (tokens.any { it in LABEL_TOKENS }) return true
        // "Segundo espacio" (Spanish second space) needs both words: "segundo"
        // on its own also appears in ordinary app names such as "Segunda mano".
        if (tokens.contains("espacio") &&
            (tokens.contains("segundo") || tokens.contains("segunda") || tokens.contains("second"))
        ) {
            return true
        }
        // Compact labels such as "WhatsAppClone" or "InstagramParallel".
        return tokens.any { token ->
            COMPACT_MARKERS.any { marker ->
                token.length > marker.length + 2 && token.endsWith(marker)
            }
        }
    }

    /** Recovers the original package from common clone/dual suffixes. */
    fun basePackage(packageName: String): String {
        var base = packageName.trim()
        for (suffix in PACKAGE_SUFFIXES) {
            if (base.endsWith(suffix, ignoreCase = true)) {
                base = base.dropLast(suffix.length)
            }
        }
        return base
    }

    /**
     * Removes clone markers from a visible name so "WhatsApp clon",
     * "Clon de WhatsApp" and "WhatsApp (Dual)" all collapse to "whatsapp".
     */
    fun baseLabel(label: String): String {
        return tokens(label)
            .filter { it !in LABEL_TOKENS && it !in CONNECTORS }
            .joinToString(" ")
    }

    /** True when a clone label refers to the same app as the original label. */
    fun isSameBaseApp(cloneLabel: String, originalLabel: String): Boolean {
        val cloneBase = baseLabel(cloneLabel)
        val originalBase = baseLabel(originalLabel)
        if (cloneBase.isBlank() || originalBase.isBlank()) return false
        if (cloneBase == originalBase) return true
        return cloneBase.contains(originalBase) || originalBase.contains(cloneBase)
    }

    private fun tokens(value: String): List<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
        return normalized.split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
    }
}
