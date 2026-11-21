import 'package:flutter/services.dart';

/// Status of the pendant drain, as reported by the native engine.
class DrainStatus {
  const DrainStatus({
    required this.hour,
    required this.minute,
    required this.lastDoneAt,
    required this.lastResult,
    required this.due,
    required this.draining,
    required this.pagesStored,
    required this.serviceRunning,
    required this.transcribing,
    required this.transcribeResult,
    required this.transcribeError,
    required this.transcribeAt,
    required this.pendingFiles,
  });

  final int hour;
  final int minute;
  final DateTime? lastDoneAt;
  final String lastResult;
  final bool due;
  final bool draining;
  final int? pagesStored;
  final bool serviceRunning;
  final bool transcribing;
  final String transcribeResult;
  final String transcribeError;
  final DateTime? transcribeAt;
  final int pendingFiles;

  String get timeLabel => '${hour.toString().padLeft(2, '0')}:${minute.toString().padLeft(2, '0')}';

  factory DrainStatus.fromMap(Map<Object?, Object?> m) {
    final done = (m['lastDoneAt'] as num?)?.toInt() ?? 0;
    return DrainStatus(
      hour: (m['hour'] as num).toInt(),
      minute: (m['minute'] as num).toInt(),
      lastDoneAt: done > 0 ? DateTime.fromMillisecondsSinceEpoch(done) : null,
      lastResult: (m['lastResult'] as String?) ?? '',
      due: m['due'] == true,
      draining: m['draining'] == true,
      pagesStored: (m['pagesStored'] as num?)?.toInt(),
      serviceRunning: m['serviceRunning'] == true,
      transcribing: m['transcribing'] == true,
      transcribeResult: (m['transcribeResult'] as String?) ?? '',
      transcribeError: (m['transcribeError'] as String?) ?? '',
      transcribeAt: ((m['transcribeAt'] as num?)?.toInt() ?? 0) > 0
          ? DateTime.fromMillisecondsSinceEpoch((m['transcribeAt'] as num).toInt())
          : null,
      pendingFiles: (m['pendingFiles'] as num?)?.toInt() ?? 0,
    );
  }
}

/// Dart side of the native drain schedule (channel `com.amuless/drain`).
class DrainSchedule {
  DrainSchedule._();
  static const _channel = MethodChannel('com.amuless/drain');

  static Future<DrainStatus> status() async {
    final m = await _channel.invokeMapMethod<Object?, Object?>('status');
    return DrainStatus.fromMap(m!);
  }

  static Future<void> setTime(int hour, int minute) =>
      _channel.invokeMethod<void>('setTime', {'hour': hour, 'minute': minute});

  static Future<void> transcribeNow() => _channel.invokeMethod<void>('transcribeNow');

  static Future<AppSettings> getSettings() async =>
      AppSettings.fromMap((await _channel.invokeMapMethod<Object?, Object?>('getSettings'))!);

  /// [token]: null keeps the stored token, empty string clears it.
  static Future<void> saveSettings({String? whisperUrl, String? whisperModel, String? memoriaUrl, String? token}) =>
      _channel.invokeMethod<void>('saveSettings', {
        'whisperUrl': whisperUrl,
        'whisperModel': whisperModel,
        'memoriaUrl': memoriaUrl,
        'token': token,
      });

  static Future<List<String>> testConnections() async =>
      ((await _channel.invokeListMethod<String>('testConnections')) ?? const <String>[]);

  /// Returns false when the BLE service is not running yet (the drain then starts on connect).
  static Future<bool> drainNow() async => (await _channel.invokeMethod<bool>('drainNow')) ?? false;
}

/// Transcription / Memoria settings. The Memoria token is never returned to Dart, only whether one is stored.
class AppSettings {
  const AppSettings({required this.whisperUrl, required this.whisperModel, required this.memoriaUrl, required this.hasToken});
  final String whisperUrl;
  final String whisperModel;
  final String memoriaUrl;
  final bool hasToken;

  factory AppSettings.fromMap(Map<Object?, Object?> m) => AppSettings(
        whisperUrl: (m['whisperUrl'] as String?) ?? '',
        whisperModel: (m['whisperModel'] as String?) ?? '',
        memoriaUrl: (m['memoriaUrl'] as String?) ?? '',
        hasToken: m['hasToken'] == true,
      );
}
