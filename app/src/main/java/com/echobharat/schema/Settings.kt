package com.echobharat.schema

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * User and app settings persisted on device.
 */
@Parcelize
data class Settings(
    val myLanguage: String = "hi",
    val autoTranslate: Boolean = false,
    val defaultTgtLang: String? = null,
    val hdVoice: Boolean = false,
    val alertVolumeMax: Boolean = true,
    val bypassMode: Boolean = false
) : Parcelable
