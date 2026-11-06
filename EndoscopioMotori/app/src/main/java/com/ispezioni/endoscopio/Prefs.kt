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

    var note: String
        get() = sp.getString("note", "") ?: ""
        set(v) = sp.edit().putString("note", v).apply()
}
