package com.friend.ios.amuless

import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.friend.ios.ble.OmiBleManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Finds out whether the pendant's button reaches the phone. While enabled, it subscribes to every
 * notifiable characteristic of the pendant and records each value it sends, with the time, so a
 * button press shows up as a line in the app. Off by default (extra notifications cost battery).
 */
object ButtonProbe {
    private const val TAG = "Amuless.ButtonProbe"
    private const val PREFS = "amuless_probe"
    private const val MAX_LINES = 80
    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean("enabled", on).apply()
        if (on) add(c, "ボタンテストを開始(次回の接続時に購読します。接続中なら、いったんペンダントを切断・再接続してください)")
    }

    fun lines(c: Context): List<String> = synchronized(lock) {
        prefs(c).getString("log", "").orEmpty().split('\n').filter { it.isNotEmpty() }
    }

    fun clear(c: Context) = synchronized(lock) { prefs(c).edit().putString("log", "").apply() }

    private fun add(c: Context, text: String) {
        val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + text
        Log.i(TAG, line)
        synchronized(lock) {
            val all = (prefs(c).getString("log", "").orEmpty().split('\n').filter { it.isNotEmpty() } + line).takeLast(MAX_LINES)
            prefs(c).edit().putString("log", all.joinToString("\n")).apply()
        }
    }

    private fun hex(b: ByteArray) = b.joinToString(" ") { "%02x".format(it) }

    private fun props(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 || p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
    }.joinToString("/")

    /** Called when the pendant is connected and its services are known. */
    fun onReady(c: Context, manager: OmiBleManager, address: String, audioChar: String?) {
        if (!enabled(c)) return
        val gatt = manager.connectedGatts[address.uppercase()] ?: return
        for (service in gatt.services.orEmpty()) {
            for (ch in service.characteristics.orEmpty()) {
                val canNotify = ch.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
                add(c, "特性 ${ch.uuid.toString().take(8)} (サービス ${service.uuid.toString().take(8)}) ${props(ch.properties)}")
                if (canNotify && !ch.uuid.toString().equals(audioChar, ignoreCase = true)) {
                    manager.subscribeCharacteristic(address, service.uuid.toString(), ch.uuid.toString())
                }
            }
        }
    }

    /**
     * A complete message from the pendant's data characteristic. While draining, storage buffers
     * (field 2) are the recording and are skipped; anything else, or any message outside a drain, is logged.
     */
    fun onMessage(c: Context, message: ByteArray, draining: Boolean) {
        if (!enabled(c)) return
        val fields = mutableListOf<String>()
        try {
            var pos = 0
            while (pos < message.size) {
                val tag = message[pos].toInt() and 0xFF
                val num = tag shr 3
                val wire = tag and 7
                pos++
                when (wire) {
                    0 -> { val (v, n) = com.friend.ios.limitless.LimitlessProtocol.decodeVarint(message, pos); pos = n; fields.add("$num=$v") }
                    2 -> { val (len, n) = com.friend.ios.limitless.LimitlessProtocol.decodeVarint(message, pos); pos = n + len.toInt(); fields.add("$num:bytes(${len})") }
                    else -> { fields.add("$num:wire$wire"); break }
                }
            }
        } catch (_: Exception) {
            fields.add("解析途中で終了")
        }
        if (draining && fields.all { it.startsWith("2:") }) return
        add(c, "メッセージ ${message.size}B [${fields.joinToString(" ")}] ${hex(message.copyOf(minOf(message.size, 24)))}")
    }

    /** Every notification value; the audio characteristic is skipped (it is the recording itself). */
    fun onValue(c: Context, characteristicUuid: String, audioChar: String?, value: ByteArray) {
        if (!enabled(c)) return
        if (characteristicUuid.equals(audioChar, ignoreCase = true)) return
        add(c, "通知 ${characteristicUuid.take(8)}: ${hex(value.copyOf(minOf(value.size, 32)))}${if (value.size > 32) " …(${value.size}B)" else ""}")
    }
}
