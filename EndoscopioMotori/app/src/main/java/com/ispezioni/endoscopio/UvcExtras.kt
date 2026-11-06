package com.ispezioni.endoscopio

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy

/**
 * Funzioni opzionali della libreria UVC, chiamate per riflessione: se la versione della
 * libreria o la sonda non le espone, l'app continua a funzionare senza (nessun crash).
 */
class BrightnessControl(private val ctl: Any, val min: Int, val max: Int) {
    private fun m(name: String) = ctl.javaClass.methods.firstOrNull { it.name == name }

    fun get(): Int = (m("getBrightness")?.invoke(ctl) as? Number)?.toInt() ?: min

    fun set(v: Int) {
        try {
            m("setBrightness")?.invoke(ctl, v.coerceIn(min, max))
        } catch (_: Throwable) {
        }
    }
}

object UvcExtras {

    /**
     * Registra il callback del tasto fisico della sonda (evento UVC "button").
     * Ritorna true se la libreria ha accettato il callback.
     */
    fun registerButton(helper: Any, onButton: (button: Int, state: Int) -> Unit): Boolean {
        return try {
            val cbClass = Class.forName("com.serenegiant.usb.IButtonCallback")
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "onButton" -> {
                        if (args != null && args.size >= 2) {
                            val b = (args[0] as? Number)?.toInt() ?: 0
                            val s = (args[1] as? Number)?.toInt() ?: 0
                            onButton(b, s)
                        }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "ProbeButtonCallback"
                    else -> null
                }
            }
            val proxy = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass), handler)
            val setter = helper.javaClass.methods.firstOrNull {
                it.name == "setButtonCallback" && it.parameterTypes.size == 1
            } ?: return false
            setter.invoke(helper, proxy)
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Controllo UVC "Brightness" (luminosità immagine), se la sonda lo espone. */
    fun brightness(helper: Any): BrightnessControl? {
        try {
            val ctl = call(helper, "getUVCControl") ?: return null
            if (call(ctl, "isBrightnessEnable") != true) return null
            var min: Int? = null
            var max: Int? = null
            val lim = call(ctl, "updateBrightnessLimit")
            if (lim is IntArray && lim.size >= 2) {
                min = lim[0]
                max = lim[1]
            }
            if (min == null || max == null) {
                min = (call(ctl, "getBrightnessMin") as? Number)?.toInt()
                max = (call(ctl, "getBrightnessMax") as? Number)?.toInt()
            }
            if (min == null || max == null || max <= min) return null
            return BrightnessControl(ctl, min, max)
        } catch (t: Throwable) {
            return null
        }
    }

    private fun call(o: Any, name: String): Any? =
        o.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }?.invoke(o)
}
