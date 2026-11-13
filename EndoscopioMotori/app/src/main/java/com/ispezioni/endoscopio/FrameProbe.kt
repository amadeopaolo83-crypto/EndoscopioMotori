package com.ispezioni.endoscopio

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.ByteBuffer

/** Riceve i fotogrammi grezzi della sonda (per riflessione) per capire cosa arriva davvero. */
object FrameProbe {
    fun attach(helper: Any, onFrame: (ByteBuffer) -> Unit): String {
        return try {
            val cbClass = Class.forName("com.serenegiant.usb.IFrameCallback")
            val handler = InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "onFrame" -> {
                        (args?.getOrNull(0) as? ByteBuffer)?.let { onFrame(it) }
                        null
                    }
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "FrameProbe"
                    else -> null
                }
            }
            val proxy = Proxy.newProxyInstance(cbClass.classLoader, arrayOf(cbClass), handler)
            val m = helper.javaClass.methods.firstOrNull {
                it.name == "setFrameCallback" && it.parameterTypes.size == 2
            } ?: return "nessun setFrameCallback"
            m.invoke(helper, proxy, 0) // 0 = dati grezzi così come arrivano
            "ok"
        } catch (t: Throwable) {
            "errore ${t.javaClass.simpleName}"
        }
    }

    /** YUYV (YUY2) -> pixel ARGB. */
    fun yuyvToArgb(src: ByteArray, w: Int, h: Int): IntArray {
        val out = IntArray(w * h)
        var si = 0
        var oi = 0
        val pairs = w * h / 2
        for (i in 0 until pairs) {
            val y0 = (src[si].toInt() and 0xFF) - 16
            val u = (src[si + 1].toInt() and 0xFF) - 128
            val y1 = (src[si + 2].toInt() and 0xFF) - 16
            val v = (src[si + 3].toInt() and 0xFF) - 128
            si += 4
            val rAdd = 409 * v + 128
            val gAdd = -100 * u - 208 * v + 128
            val bAdd = 516 * u + 128
            out[oi++] = pack(298 * y0, rAdd, gAdd, bAdd)
            out[oi++] = pack(298 * y1, rAdd, gAdd, bAdd)
        }
        return out
    }

    private fun pack(y: Int, r: Int, g: Int, b: Int): Int {
        val rr = ((y + r) shr 8).coerceIn(0, 255)
        val gg = ((y + g) shr 8).coerceIn(0, 255)
        val bb = ((y + b) shr 8).coerceIn(0, 255)
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}
