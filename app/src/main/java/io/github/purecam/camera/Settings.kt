package io.github.purecam.camera

import android.content.Context

enum class Flash { OFF, AUTO, ON, TORCH }

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var natural: Boolean
        get() = bool("natural", true)
        set(v) = put("natural", v)
    var raw: Boolean
        get() = bool("raw", false)
        set(v) = put("raw", v)
    var grid: Boolean
        get() = bool("grid", true)
        set(v) = put("grid", v)
    var sound: Boolean
        get() = bool("sound", true)
        set(v) = put("sound", v)
    var volumeShutter: Boolean
        get() = bool("volumeShutter", true)
        set(v) = put("volumeShutter", v)
    var uwCorrection: Boolean
        get() = bool("uwCorrection", false)
        set(v) = put("uwCorrection", v)
    var timer: Int
        get() = sp.getInt("timer", 0)
        set(v) = sp.edit().putInt("timer", v).apply()
    var lastLens: String? get() = sp.getString("lastLens", null); set(v) = sp.edit().putString("lastLens", v).apply()
    var flash: Flash
        get() = runCatching { Flash.valueOf(sp.getString("flash", Flash.OFF.name)!!) }.getOrDefault(Flash.OFF)
        set(v) = sp.edit().putString("flash", v.name).apply()
}
