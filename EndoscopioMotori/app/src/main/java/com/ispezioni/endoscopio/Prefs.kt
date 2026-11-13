package com.ispezioni.endoscopio

import android.content.Context

/** Impostazioni salvate: rotazione, specchio, ultima nota. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var rotation: Int
        get() = sp.getInt("rotation", 0)
        set(v) = sp.edit().putInt("rotation", v).apply()

    var mirror: Boolean
        get() = sp.getBoolean("mirror", false)
        set(v) = sp.edit().putBoolean("mirror", v).apply()

    /** "" = predefinita della libreria, altrimenti "LxA" (es. 1280x720). */
    var resolution: String
        get() = sp.getString("resolution", "") ?: ""
        set(v) = sp.edit().putString("resolution", v).apply()

    // Interruttori di diagnosi (tutti spenti = comportamento identico all'app demo della libreria)
    var optButton: Boolean
        get() = sp.getBoolean("optButton", false)
        set(v) = sp.edit().putBoolean("optButton", v).apply()

    var optBrightness: Boolean
        get() = sp.getBoolean("optBrightness", false)
        set(v) = sp.edit().putBoolean("optBrightness", v).apply()

    var optConfig: Boolean
        get() = sp.getBoolean("optConfig", false)
        set(v) = sp.edit().putBoolean("optConfig", v).apply()

    var optFit: Boolean
        get() = sp.getBoolean("optFit", false)
        set(v) = sp.edit().putBoolean("optFit", v).apply()

    /** Prova: riduce la banda USB richiesta (quirk della libreria). */
    var optQuirk: Boolean
        get() = sp.getBoolean("optQuirk", false)
        set(v) = sp.edit().putBoolean("optQuirk", v).apply()

    /** Prova: frame rate più basso (meno dati al secondo). */
    var optLowFps: Boolean
        get() = sp.getBoolean("optLowFps", false)
        set(v) = sp.edit().putBoolean("optLowFps", v).apply()

    var note: String
        get() = sp.getString("note", "") ?: ""
        set(v) = sp.edit().putString("note", v).apply()
}
