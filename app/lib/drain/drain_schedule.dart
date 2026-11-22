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
    required this.progressDone,
    required this.battery,
    required this.batteryAt,
    required this.progressTotal,
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
  final int progressDone;
  final int battery; // percent, -1 unknown
  final DateTime? batteryAt;
  final int progressTotal;
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
      battery: (m['battery'] as num?)?.toInt() ?? -1,
      batteryAt: (m['batteryAt'] as num?)?.toInt() == null || (m['batteryAt'] as num).toInt() == 0
          ? null
          : DateTime.fromMillisecondsSinceEpoch((m['batteryAt'] as num).toInt()),
      progressDone: (m['progressDone'] as num?)?.toInt() ?? 0,
      progressTotal: (m['progressTotal'] as num?)?.toInt() ?? 0,
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
  static Future<void> saveSettings({
    String? whisperUrl,
    String? whisperModel,
    String? memoriaUrl,
    String? token,
    String? notebookId,
    String? notebookTitle,
  }) =>
      _channel.invokeMethod<void>('saveSettings', {
        'whisperUrl': whisperUrl,
        'whisperModel': whisperModel,
        'memoriaUrl': memoriaUrl,
        'token': token,
        'notebookId': notebookId,
        'notebookTitle': notebookTitle,
      });

  /// Notebooks on the Memoria server (needs the URL and token saved first). Throws a message string on failure.
  static Future<List<Notebook>> listNotebooks() async {
    final m = await _channel.invokeMapMethod<Object?, Object?>('listNotebooks');
    final error = m?['error'] as String?;
    if (error != null) throw error;
    return ((m?['notebooks'] as List?) ?? const [])
        .map((e) => Notebook(id: (e as Map)['id'] as String, title: e['title'] as String))
        .toList();
  }

  /// Newest notes in the chosen Memoria notebook. Throws a message string on failure.
  static Future<List<NoteSummary>> listNotes() async {
    final m = await _channel.invokeMapMethod<Object?, Object?>('listNotes');
    final error = m?['error'] as String?;
    if (error != null) throw error;
    return ((m?['notes'] as List?) ?? const [])
        .map((e) => NoteSummary(id: (e as Map)['id'] as String, title: e['title'] as String, createdAt: (e['createdAt'] as String?) ?? ''))
        .toList();
  }

  /// The stored HTML of one note. Throws a message string on failure.
  static Future<String> readNote(String id) async {
    final m = await _channel.invokeMapMethod<Object?, Object?>('readNote', {'id': id});
    final error = m?['error'] as String?;
    if (error != null) throw error;
    return (m?['html'] as String?) ?? '';
  }

  /// Button test: op is 'on', 'off', 'clear' or null (just read).
  static Future<({bool enabled, List<String> lines})> probe([String? op]) async {
    final m = await _channel.invokeMapMethod<Object?, Object?>('probe', {'op': op});
    return (enabled: m?['enabled'] == true, lines: ((m?['lines'] as List?) ?? const []).cast<String>());
  }

  static Future<List<String>> testConnections() async =>
      ((await _channel.invokeListMethod<String>('testConnections')) ?? const <String>[]);

  /// Returns false when the BLE service is not running yet (the drain then starts on connect).
  static Future<bool> drainNow() async => (await _channel.invokeMethod<bool>('drainNow')) ?? false;
}

/// Transcription / Memoria settings. The Memoria token is never returned to Dart, only whether one is stored.
class AppSettings {
  const AppSettings({
    required this.whisperUrl,
    required this.whisperModel,
    required this.memoriaUrl,
    required this.hasToken,
    required this.notebookId,
    required this.notebookTitle,
  });
  final String whisperUrl;
  final String whisperModel;
  final String memoriaUrl;
  final bool hasToken;
  final String notebookId;
  final String notebookTitle;

  factory AppSettings.fromMap(Map<Object?, Object?> m) => AppSettings(
        whisperUrl: (m['whisperUrl'] as String?) ?? '',
        whisperModel: (m['whisperModel'] as String?) ?? '',
        memoriaUrl: (m['memoriaUrl'] as String?) ?? '',
        hasToken: m['hasToken'] == true,
        notebookId: (m['notebookId'] as String?) ?? '',
        notebookTitle: (m['notebookTitle'] as String?) ?? '',
      );
}

class NoteSummary {
  const NoteSummary({required this.id, required this.title, required this.createdAt});
  final String id;
  final String title;
  final String createdAt;
}

class Notebook {
  const Notebook({required this.id, required this.title});
  final String id;
  final String title;
}
