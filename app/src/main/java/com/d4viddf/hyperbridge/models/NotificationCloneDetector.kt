package com.d4viddf.hyperbridge.models

/**
 * Heuristic clone-app detection shared by the notification service and the
 * island overlay. It recognises package-name tokens and visible-name suffixes
 * used by dual-app / second-space features across ROMs.
 */
object NotificationCloneDetector {

    /** Broad patterns seen in clone/second-space packages across ROMs. */
    private val PACKAGE_MARKERS = listOf(
        ".clone", ".clon", ".dual", ".dualspace", ".parallel",
        ".second", ".secondspace", ".work", ".multi", ".appclone",
        "_clone", "_dual", "_parallel", ".cloneapp", ".dualapp"
    )

    /** Visible name markers added by clone apps. */
    private val LABEL_MARKERS = listOf(
        " clone", " clon", " dual", " paralelo", " second", "second ",
        " work", " multi", " app clone", " dual space", " espacio dual"
    )

    /** True when the package name contains a known clone/dual-space token. */
    fun isCloneApp(packageName: String): Boolean {
        val lower = packageName.lowercase()
        return PACKAGE_MARKERS.any { marker -> lower.contains(marker) }
    }

    /** True when the visible app or component name looks like a clone label. */
    fun isCloneLabel(label: String): Boolean {
        val lower = label.lowercase()
        return LABEL_MARKERS.any { marker -> lower.contains(marker) }
    }
}
