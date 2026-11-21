package com.friend.ios

import android.content.Intent
import androidx.annotation.NonNull
import com.friend.ios.ble.BleHostApiImpl
import com.friend.ios.ble.OmiBleForegroundService
import com.friend.ios.ble.OmiBleManager
import com.friend.ios.limitless.DrainSchedule
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

/** Amuless host activity: only the native BLE bridge (Pigeon) is wired up. */
class MainActivity : FlutterActivity() {
    private var bleHostApiImpl: BleHostApiImpl? = null

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        OmiBleManager.initialize(application)
        getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
            .edit()
            .putBoolean("flutter.nativeBleForegroundReady", false)
            .apply()
        OmiBleManager.isFlutterAlive = true
        OmiBleManager.instance.flutterApi = BleFlutterApi(flutterEngine.dartExecutor.binaryMessenger)
        val hostApi = BleHostApiImpl { this }
        hostApi.initCompanionManager(this)
        bleHostApiImpl = hostApi
        BleHostApi.setUp(flutterEngine.dartExecutor.binaryMessenger, hostApi)

        // Amuless: drain schedule (daily time, drain now, status)
        DrainSchedule.arm(applicationContext)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "com.amuless/drain").setMethodCallHandler { call, result ->
            when (call.method) {
                "setTime" -> {
                    val h = call.argument<Int>("hour")
                    val m = call.argument<Int>("minute")
                    if (h == null || m == null) {
                        result.error("bad_args", "hour and minute are required", null)
                    } else {
                        DrainSchedule.setTime(applicationContext, h, m)
                        result.success(null)
                    }
                }
                "drainNow" -> {
                    DrainSchedule.requestNow(applicationContext)
                    OmiBleForegroundService.instance?.drainTick()
                    result.success(OmiBleForegroundService.instance != null)
                }
                "status" -> {
                    val ctx = applicationContext
                    val flutterPrefs = ctx.getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
                    result.success(
                        mapOf(
                            "hour" to DrainSchedule.hour(ctx),
                            "minute" to DrainSchedule.minute(ctx),
                            "lastDoneAt" to DrainSchedule.lastDoneAt(ctx),
                            "lastResult" to DrainSchedule.lastResult(ctx),
                            "due" to DrainSchedule.isDue(ctx),
                            "draining" to flutterPrefs.getBoolean("flutter.pendantDraining", false),
                            "pagesStored" to (flutterPrefs.all["flutter.pendantPagesStored"] as? Number)?.toLong(),
                            "serviceRunning" to (OmiBleForegroundService.instance != null),
                        ),
                    )
                }
                else -> result.notImplemented()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // CompanionDeviceManager chooser result
        val address = bleHostApiImpl?.onActivityResult(requestCode, resultCode, data)
        if (address != null) {
            OmiBleForegroundService.startService(this, address, caller = "MainActivity.onActivityResult")
        }
    }

    override fun onResume() {
        super.onResume()
        OmiBleManager.isAppForeground = true
        OmiBleForegroundService.instance?.recordPermissionStates()
    }

    override fun onPause() {
        OmiBleManager.isAppForeground = false
        super.onPause()
    }

    override fun onDestroy() {
        OmiBleManager.isFlutterAlive = false
        getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
            .edit()
            .putBoolean("flutter.nativeBleForegroundReady", false)
            .apply()
        if (isFinishing && !OmiBleForegroundService.isPersistentModeEnabled(this)) {
            OmiBleForegroundService.stopService(this)
        }
        super.onDestroy()
    }
}
