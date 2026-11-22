package com.friend.ios.amuless

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Whether Tailscale is connected on this phone. Tailscale addresses its devices from 100.64.0.0/10, so a
 * VPN network that holds an address in that range is Tailscale up; the app's on/off switch alone does not
 * say whether the connection exists.
 */
object TailscaleState {
    fun isUp(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        cm.allNetworks.any { net ->
            val caps = cm.getNetworkCapabilities(net)
            caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                cm.getLinkProperties(net)?.linkAddresses.orEmpty().any { la ->
                    val b = la.address.address
                    b.size == 4 && (b[0].toInt() and 0xFF) == 100 && (b[1].toInt() and 0xC0) == 0x40
                }
        }
    } catch (_: Exception) {
        false
    }
}
