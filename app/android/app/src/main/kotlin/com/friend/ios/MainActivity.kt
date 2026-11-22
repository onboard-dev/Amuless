package com.friend.ios

import android.content.Intent
import androidx.annotation.NonNull
import com.friend.ios.ble.BleHostApiImpl
import com.friend.ios.ble.OmiBleForegroundService
import com.friend.ios.ble.OmiBleManager
import android.os.Handler
import android.os.Looper
import com.friend.ios.amuless.AmulessSettings
import com.friend.ios.amuless.MemoriaClient
import com.friend.ios.amuless.Transcriber
import com.friend.ios.amuless.WhisperClient
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
        com.friend.ios.amuless.EventLog.recordPastExits(applicationContext)
        com.friend.ios.amuless.EventLog.add(applicationContext, "アプリ画面を開いた")
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
                "getSettings" -> {
                    val ctx = applicationContext
                    result.success(
                        mapOf(
                            "whisperUrl" to AmulessSettings.whisperUrl(ctx),
                            "whisperModel" to AmulessSettings.whisperModel(ctx),
                            "memoriaUrl" to AmulessSettings.memoriaUrl(ctx),
                            "hasToken" to AmulessSettings.hasToken(ctx),
                            "notebookId" to AmulessSettings.notebookId(ctx),
                            "notebookTitle" to AmulessSettings.notebookTitle(ctx),
                        ),
                    )
                }
                "saveSettings" -> {
                    AmulessSettings.save(
                        applicationContext,
                        call.argument<String>("whisperUrl"),
                        call.argument<String>("whisperModel"),
                        call.argument<String>("memoriaUrl"),
                        call.argument<String>("token"),
                        call.argument<String>("notebookId"),
                        call.argument<String>("notebookTitle"),
                    )
                    result.success(null)
                }
                "testConnections" -> {
                    val ctx = applicationContext
                    val main = Handler(Looper.getMainLooper())
                    Thread {
                        val msgs = ArrayList<String>()
                        val wUrl = AmulessSettings.whisperUrl(ctx)
                        msgs.add(
                            if (wUrl.isEmpty()) "Whisper: URL が未入力です"
                            else "Whisper: " + (WhisperClient(wUrl, AmulessSettings.whisperModel(ctx)).ping() ?: "OK"),
                        )
                        val mUrl = AmulessSettings.memoriaUrl(ctx)
                        val token = AmulessSettings.token(ctx)
                        msgs.add(
                            if (mUrl.isEmpty()) "memoria: URL が未入力です"
                            else if (token == null) "memoria: トークンが未入力です"
                            else "memoria: " + (MemoriaClient(mUrl, token, AmulessSettings.notebookId(ctx)).check() ?: "OK(ノートブック「${AmulessSettings.notebookTitle(ctx)}」とセクションを確認)"),
                        )
                        main.post { result.success(msgs) }
                    }.start()
                }
                "listNotebooks" -> {
                    val ctx = applicationContext
                    val main = Handler(Looper.getMainLooper())
                    Thread {
                        val url = AmulessSettings.memoriaUrl(ctx)
                        val token = AmulessSettings.token(ctx)
                        val reply: Map<String, Any?> = if (url.isEmpty() || token == null) {
                            mapOf("error" to "先に memoria の URL とトークンを入力して保存してください")
                        } else try {
                            mapOf("notebooks" to MemoriaClient(url, token).notebooks().map { mapOf("id" to it.first, "title" to it.second) })
                        } catch (e: com.friend.ios.amuless.MemoriaException) {
                            mapOf("error" to e.message)
                        }
                        main.post { result.success(reply) }
                    }.start()
                }
                "listNotes", "readNote" -> {
                    val ctx = applicationContext
                    val main = Handler(Looper.getMainLooper())
                    val noteId = call.argument<String>("id")
                    val isList = call.method == "listNotes"
                    Thread {
                        val url = AmulessSettings.memoriaUrl(ctx)
                        val token = AmulessSettings.token(ctx)
                        val reply: Map<String, Any?> = if (url.isEmpty() || token == null) {
                            mapOf("error" to "memoria の URL とトークンを設定してください")
                        } else try {
                            val client = MemoriaClient(url, token, AmulessSettings.notebookId(ctx))
                            if (isList) mapOf("notes" to client.recentNotes().map { mapOf("id" to it.id, "title" to it.title, "createdAt" to it.createdAt) })
                            else mapOf("html" to client.noteHtml(noteId.orEmpty()))
                        } catch (e: com.friend.ios.amuless.MemoriaException) {
                            mapOf("error" to e.message)
                        }
                        main.post { result.success(reply) }
                    }.start()
                }
                "probe" -> {
                    val ctx = applicationContext
                    when (call.argument<String>("op")) {
                        "on" -> com.friend.ios.amuless.ButtonProbe.setEnabled(ctx, true)
                        "off" -> com.friend.ios.amuless.ButtonProbe.setEnabled(ctx, false)
                        "clear" -> com.friend.ios.amuless.ButtonProbe.clear(ctx)
                    }
                    result.success(mapOf("enabled" to com.friend.ios.amuless.ButtonProbe.enabled(ctx), "lines" to com.friend.ios.amuless.ButtonProbe.lines(ctx)))
                }
                "setPauseReminder" -> {
                    com.friend.ios.amuless.RecordingMonitor.setReminderMinutes(applicationContext, call.argument<Int>("minutes") ?: 60)
                    result.success(null)
                }
                "events" -> {
                    val ctx = applicationContext
                    if (call.argument<String>("op") == "clear") com.friend.ios.amuless.EventLog.clear(ctx)
                    result.success(com.friend.ios.amuless.EventLog.lines(ctx))
                }
                "transcribeNow" -> {
                    Transcriber.kick(applicationContext, manual = true)
                    result.success(null)
                }
                "status" -> {
                    val ctx = applicationContext
                    val tr = Transcriber.state(ctx)
                    val flutterPrefs = ctx.getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
                    result.success(
                        mapOf(
                            "hour" to DrainSchedule.hour(ctx),
                            "minute" to DrainSchedule.minute(ctx),
                            "lastDoneAt" to DrainSchedule.lastDoneAt(ctx),
                            "lastResult" to DrainSchedule.lastResult(ctx),
                            "due" to DrainSchedule.isDue(ctx),
                            "draining" to flutterPrefs.getBoolean("flutter.pendantDraining", false),
                            "progressDone" to DrainSchedule.progress(ctx).first,
                            "progressTotal" to DrainSchedule.progress(ctx).second,
                            "signalAt" to com.friend.ios.amuless.RecordingMonitor.signalAt(ctx),
                            "pauseReminderMin" to com.friend.ios.amuless.RecordingMonitor.reminderMinutes(ctx),
                            "battery" to DrainSchedule.battery(ctx).first,
                            "batteryAt" to DrainSchedule.battery(ctx).second,
                            "pagesStored" to (flutterPrefs.all["flutter.pendantPagesStored"] as? Number)?.toLong(),
                            "serviceRunning" to (OmiBleForegroundService.instance != null),
                            "transcribing" to tr.running,
                            "transcribeResult" to tr.result,
                            "transcribeError" to tr.error,
                            "transcribeAt" to tr.at,
                            "pendingFiles" to tr.pending,
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
