package com.d4viddf.hyperbridge.models

data class IslandConfig(
    val isFloat: Boolean? = null,
    val isShowShade: Boolean? = null,
    val timeout: Int? = null,
    val floatTimeout: Int? = null,
    val removeOriginalNotification: Boolean? = null,
    val dismissWithOriginal: Boolean? = null,
    val enableInlineReply: Boolean? = null,
) {
    // Merges this config (App) with a default config (Global)
    fun mergeWith(global: IslandConfig): IslandConfig {
        return IslandConfig(
            isFloat = this.isFloat ?: global.isFloat ?: false,
            isShowShade = this.isShowShade ?: global.isShowShade ?: false,
            timeout = this.timeout ?: global.timeout ?: 10,
            floatTimeout = this.floatTimeout ?: global.floatTimeout ?: 5,
            // HyperOS replaces the notification with the island, so by default the
            // original is removed to avoid a duplicate in the shade.
            removeOriginalNotification = this.removeOriginalNotification ?: global.removeOriginalNotification ?: true,
            dismissWithOriginal = this.dismissWithOriginal ?: global.dismissWithOriginal ?: true,
            enableInlineReply = this.enableInlineReply ?: global.enableInlineReply ?: true,
        )
    }
}