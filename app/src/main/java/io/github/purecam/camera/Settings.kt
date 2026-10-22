package io.github.purecam.camera

import android.content.Context

enum class Flash { OFF, AUTO, ON, TORCH }

enum class Mode(val label: String) {
    VIDEO("VIDEO"), PHOTO("PHOTO"), PORTRAIT("PORTRAIT"), PRO("PRO");

    val video: Boolean get() = this == VIDEO
}

enum class VideoProfile(val short: String, val title: String, val detail: String) {
    STANDARD("STD", "Standard", "Ready to upload to YouTube or share: stabilised, clean, natural contrast."),
    NATURAL("NAT", "Natural", "Processing off: no noise reduction, no sharpening, no stabilisation crop."),
    LOG("LOG", "PureLog", "Flat, low-contrast log curve that keeps highlight and shadow detail for colour grading. Apply the PureLog LUT in your editor."),
}

enum class Codec(val title: String, val detail: String) {
    H264("H.264", "Plays everywhere"),
    HEVC("HEVC (H.265)", "About 40% smaller files; YouTube accepts it"),
}

class Settings(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private fun bool(k: String, d: Boolean) = sp.getBoolean(k, d)
    private fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()
    private fun int(k: String, d: Int) = sp.getInt(k, d)
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun str(k: String) = sp.getString(k, null)
    private fun put(k: String, v: String?) = sp.edit().putString(k, v).apply()

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
    var stabilization: Boolean
        get() = bool("stabilization", true)
        set(v) = put("stabilization", v)
    var timer: Int
        get() = int("timer", 0)
        set(v) = put("timer", v)
    var videoRes: Int
        get() = int("videoRes", 2160)
        set(v) = put("videoRes", v)
    var videoFps: Int
        get() = int("videoFps", 30)
        set(v) = put("videoFps", v)
    var lastLens: String?
        get() = str("lastLens")
        set(v) = put("lastLens", v)
    var aperture: Float
        get() = sp.getFloat("aperture", 2.8f)
        set(v) = sp.edit().putFloat("aperture", v).apply()
    var flash: Flash
        get() = runCatching { Flash.valueOf(str("flash")!!) }.getOrDefault(Flash.OFF)
        set(v) = put("flash", v.name)
    var look: Look
        get() = runCatching { Look.valueOf(str("look")!!) }.getOrDefault(Look.NATURAL)
        set(v) = put("look", v.name)
    var videoProfile: VideoProfile
        get() = runCatching { VideoProfile.valueOf(str("videoProfile")!!) }.getOrDefault(VideoProfile.STANDARD)
        set(v) = put("videoProfile", v.name)
    var codec: Codec
        get() = runCatching { Codec.valueOf(str("codec")!!) }.getOrDefault(Codec.H264)
        set(v) = put("codec", v.name)
}
