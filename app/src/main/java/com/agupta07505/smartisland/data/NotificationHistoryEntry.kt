/*
 * Smart Island (2026)
 * © Animesh Gupta — github.com/agupta07505
 * Licensed under the GNU GPL v3 License
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.agupta07505.smartisland.data

import org.json.JSONArray
import org.json.JSONObject

data class NotificationHistoryEntry(
    val id: Long = 0L,
    val notificationKey: String,
    val packageName: String,
    val appName: String,
    val title: String,
    val text: String,
    val subText: String? = null,
    val postTimeMillis: Long,
    val category: String? = null,
    val channelId: String? = null,
    val mode: String = "Notification",
    val actionTitles: List<String> = emptyList(),
    val wasOpened: Boolean = false,
    val wasDismissed: Boolean = false
) {
    fun toBackupJson(): JSONObject = JSONObject().apply {
        put("notificationKey", notificationKey)
        put("packageName", packageName)
        put("appName", appName)
        put("title", title)
        put("text", text)
        subText?.let { put("subText", it) }
        put("postTimeMillis", postTimeMillis)
        category?.let { put("category", it) }
        channelId?.let { put("channelId", it) }
        put("mode", mode)
        put("actionTitles", JSONArray(actionTitles))
        put("wasOpened", wasOpened)
        put("wasDismissed", wasDismissed)
    }

    companion object {
        fun fromBackupJson(json: JSONObject): NotificationHistoryEntry? {
            val key = json.optString("notificationKey").takeIf { it.isNotBlank() } ?: return null
            val packageName = json.optString("packageName").takeIf { it.isNotBlank() } ?: return null
            val timestamp = json.optLong("postTimeMillis", 0L).takeIf { it > 0L } ?: return null
            val actions = json.optJSONArray("actionTitles")?.let { array ->
                List(minOf(array.length(), 20)) { index -> array.optString(index) }
                    .filter { it.isNotBlank() }
            }.orEmpty()
            return NotificationHistoryEntry(
                notificationKey = key.take(512),
                packageName = packageName.take(255),
                appName = json.optString("appName", packageName).take(512),
                title = json.optString("title").take(8_192),
                text = json.optString("text").take(32_768),
                subText = json.optString("subText").takeIf { it.isNotBlank() }?.take(8_192),
                postTimeMillis = timestamp,
                category = json.optString("category").takeIf { it.isNotBlank() }?.take(256),
                channelId = json.optString("channelId").takeIf { it.isNotBlank() }?.take(512),
                mode = json.optString("mode", "Notification").take(128),
                actionTitles = actions,
                wasOpened = json.optBoolean("wasOpened", false),
                wasDismissed = json.optBoolean("wasDismissed", false)
            )
        }
    }
}
