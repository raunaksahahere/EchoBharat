package com.echobharat.identity

import android.os.Build
import java.util.Locale

/**
 * Utility for extracting user-friendly device model string.
 * Example: "Xiaomi Poco F5", "Redmi A4 5G", "Samsung Galaxy S21"
 */
object DeviceUtils {

    fun getDeviceModel(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
        }
        val model = Build.MODEL.orEmpty()

        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model".trim().ifEmpty { "Android Device" }
        }
    }
}
