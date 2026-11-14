package com.ispezioni.endoscopio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Android 14 richiede di dichiarare come registrare i ricevitori: la libreria USB non lo fa,
 * quindi lo aggiungiamo qui (serve per il permesso USB e il collegamento della sonda).
 */
class SafeContext(base: Context) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? =
        if (Build.VERSION.SDK_INT >= 33) super.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else super.registerReceiver(receiver, filter)
}
