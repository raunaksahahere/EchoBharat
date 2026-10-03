package com.echobharat.models

/** Pure rules used by the download gate and file-generation comparison. */
object VoicePackUpgrade {
    data class Installed(val packVersion: Int, val files: Map<String, String>, val loadFailed: Boolean = false)

    fun needsUpdate(
        desiredPackVersion: Int,
        desiredFiles: Map<String, String>,
        installed: Installed?,
        hasReadableLegacyFiles: Boolean
    ): Boolean {
        if (installed == null) return hasReadableLegacyFiles
        if (installed.loadFailed || installed.packVersion < desiredPackVersion) return true
        return desiredFiles.any { (name, sha) -> installed.files[name] != sha }
    }

    /** Default is unmetered Wi-Fi, not merely any network reporting internet capability. */
    fun mayUseNetwork(
        activeNetwork: Boolean,
        metered: Boolean,
        allowMetered: Boolean,
        wifi: Boolean = true
    ): Boolean = activeNetwork && (allowMetered || (wifi && !metered))
}
