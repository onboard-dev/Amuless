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
  });

  final int hour;
  final int minute;
  final DateTime? lastDoneAt;
  final String lastResult;
  final bool due;
  final bool draining;
  final int? pagesStored;
  final bool serviceRunning;

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

  /// Returns false when the BLE service is not running yet (the drain then starts on connect).
  static Future<bool> drainNow() async => (await _channel.invokeMethod<bool>('drainNow')) ?? false;
}
