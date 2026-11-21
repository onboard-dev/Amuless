package com.friend.ios

import android.content.Intent
import androidx.annotation.NonNull
import com.friend.ios.ble.BleHostApiImpl
import com.friend.ios.ble.OmiBleForegroundService
import com.friend.ios.ble.OmiBleManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

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
