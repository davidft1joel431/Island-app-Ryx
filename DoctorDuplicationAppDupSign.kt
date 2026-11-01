package com.d4viddf.hyperbridge.service

import android.app.ActivityManager
import android.content.Context

public object DuplicationAppDetector {
    public fun cloneBasePackage(packageName: String): String {
        val suffixes = listOf(".clone", ".clon", ".dual", ".dualspace", ".parallel", ".second", ".secondspace", ".work", ".multi", ".appclone", "_clone", "_dual", "_parallel")
        return suffixes.firstOrNull { packageName.endsWith(it, ignoreCase = true) }?.let { packageName.substring(0, packageName.length - it.length) } ?: packageName
    }
}
