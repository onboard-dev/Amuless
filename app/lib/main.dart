import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';

import 'ble/pendant_ble.dart';
import 'drain/drain_schedule.dart';
import 'notes_page.dart';
import 'settings_page.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const AmulessApp());
}

class AmulessApp extends StatelessWidget {
  const AmulessApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Amuless',
      theme: ThemeData(colorSchemeSeed: Colors.deepPurple, useMaterial3: true),
      darkTheme: ThemeData(colorSchemeSeed: Colors.deepPurple, brightness: Brightness.dark, useMaterial3: true),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final ble = PendantBle.instance;
  List<FileSystemEntity> files = [];
  DrainStatus? drain;
  Timer? _poll;
  int? _lastPages;
  DateTime? _pagesChangedAt;
  DateTime? _pagesCheckedAt;

  @override
  void initState() {
    super.initState();
    ble.addListener(_refresh);
    Permission.notification.request(); // for the "recording is paused" reminder
    _start();
    _pollStatus();
    _poll = Timer.periodic(const Duration(seconds: 3), (_) => _pollStatus());
  }

  @override
  void dispose() {
    _poll?.cancel();
    ble.removeListener(_refresh);
    super.dispose();
  }

  Future<void> _start() async {
    await [Permission.bluetoothScan, Permission.bluetoothConnect, Permission.locationWhenInUse, Permission.notification]
        .request();
    await ble.init();
    await _loadFiles();
  }

  Future<void> _loadFiles() async {
    final dir = await getApplicationDocumentsDirectory();
    final list = dir.listSync().where((e) => e.path.endsWith('.bin')).toList()
      ..sort((a, b) => b.path.compareTo(a.path));
    if (mounted) setState(() => files = list);
  }

  Future<void> _pollStatus() async {
    try {
      final st = await DrainSchedule.status();
      if (mounted) {
        setState(() {
          drain = st;
          final n = st.pagesStored;
          if (n != null) {
            _pagesCheckedAt = DateTime.now();
            if (n != _lastPages) _pagesChangedAt = DateTime.now();
            _lastPages = n;
          }
        });
      }
    } catch (_) {}
    _loadFiles();
  }

  Future<void> _pickTime() async {
    final d = drain;
    final picked = await showTimePicker(
      context: context,
      initialTime: TimeOfDay(hour: d?.hour ?? 2, minute: d?.minute ?? 0),
      helpText: '毎日、音声を取り込む時刻',
    );
    if (picked == null) return;
    await DrainSchedule.setTime(picked.hour, picked.minute);
    await _pollStatus();
  }

  Future<void> _drainNow() async {
    final started = await DrainSchedule.drainNow();
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(started ? '取り込みを開始します' : 'ペンダントに接続したら取り込みます'),
    ));
    await _pollStatus();
  }

  String _resultLabel(String r) => switch (r) {
        'ok' => '成功',
        'nothing_to_drain' => '取り込む音声なし',
        '' => '',
        _ => '失敗($r)',
      };

  String _transcribeLabel(DrainStatus d) {
    if (d.transcribing) return '文字起こし中…';
    final at = d.transcribeAt;
    final when = at == null
        ? ''
        : '${at.month}/${at.day} ${at.hour.toString().padLeft(2, '0')}:${at.minute.toString().padLeft(2, '0')} ';
    final what = switch (d.transcribeResult) {
      'ok' => 'memoria に保存しました',
      'no_speech' => '話し声なし',
      'not_configured' => '設定が未入力です',
      'whisper_failed' => '文字起こしに失敗(自動で再試行)',
      'memoria_failed' => 'memoria への保存に失敗',
      'error' => 'エラー',
      _ => '',
    };
    return when + what;
  }

  Widget _transcribeCard() {
    final d = drain;
    if (d == null) return const SizedBox.shrink();
    final label = _transcribeLabel(d);
    return Card(
      child: ListTile(
        leading: Icon(d.transcribing ? Icons.hourglass_top : Icons.notes),
        title: const Text('文字起こし → memoria'),
        subtitle: Text([
          if (label.isNotEmpty) label,
          if (d.transcribeError.isNotEmpty) d.transcribeError,
          '待機中の録音: ${d.pendingFiles} 件',
        ].join('\n')),
        isThreeLine: d.transcribeError.isNotEmpty,
        trailing: TextButton(
          onPressed: d.transcribing
              ? null
              : () async {
                  await DrainSchedule.transcribeNow();
                  await _pollStatus();
                },
          child: const Text('今すぐ'),
        ),
      ),
    );
  }

  /// Recording state. A recording pendant reports storage every few seconds; silence for 2 minutes
  /// while connected means it was paused with a long press.
  String _recordingLine() {
    final s = drain?.signalAt;
    if (!ble.ready || s == null) return '';
    final quiet = DateTime.now().difference(s);
    if (quiet.inSeconds <= 120) return '\n録音中';
    final since = '${s.hour.toString().padLeft(2, '0')}:${s.minute.toString().padLeft(2, '0')}';
    return '\n録音停止中($since から ${quiet.inMinutes}分)。ボタン長押しで再開';
  }

  String _hm(DateTime t) => '${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}:${t.second.toString().padLeft(2, '0')}';

  Widget _drainCard() {
    final d = drain;
    if (d == null) return const SizedBox.shrink();
    final last = d.lastDoneAt;
    final lastText = last == null
        ? 'まだ取り込んでいません'
        : '${last.month}/${last.day} ${last.hour.toString().padLeft(2, '0')}:${last.minute.toString().padLeft(2, '0')}'
            ' ${_resultLabel(d.lastResult)}';
    return Card(
      child: Column(children: [
        ListTile(
          leading: const Icon(Icons.schedule),
          title: Text('毎日 ${d.timeLabel} に取り込み'),
          subtitle: Text([
            '前回: $lastText',
            if (!d.draining && d.pagesStored != null)
              'ペンダント内の未取得: ${d.pagesStored} ページ'
                  '${_pagesChangedAt == null ? '' : '\n最後に増えた: ${_hm(_pagesChangedAt!)}(確認 ${_hm(_pagesCheckedAt!)})'}',
            if (d.draining && d.progressTotal == 0) '取り込み準備中…',
            if (d.draining && d.progressTotal > 0)
              '取り込み中 ${d.progressDone}/${d.progressTotal} ページ'
                  '(${(d.progressDone * 100 / d.progressTotal).clamp(0, 100).toStringAsFixed(0)}%)',
          ].join('\n')),
          isThreeLine: d.draining || (d.pagesStored != null && _pagesChangedAt != null),
          trailing: TextButton(onPressed: _pickTime, child: const Text('時刻を変更')),
        ),
        if (d.draining)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
            child: LinearProgressIndicator(value: d.progressTotal > 0 ? d.progressDone / d.progressTotal : null),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
          child: Row(children: [
            FilledButton.tonalIcon(
              onPressed: d.draining ? null : _drainNow,
              icon: const Icon(Icons.download),
              label: Text(d.draining ? '取り込み中…' : '今すぐ取り込む'),
            ),
            const SizedBox(width: 8),
            TextButton(
              onPressed: () => Permission.ignoreBatteryOptimizations.request(),
              child: const Text('電池の最適化を除外'),
            ),
          ]),
        ),
      ]),
    );
  }

  void _refresh() {
    if (mounted) setState(() {});
    _loadFiles();
  }

  @override
  Widget build(BuildContext context) {
    final paired = ble.pendantId != null;
    return Scaffold(
      appBar: AppBar(title: const Text('Amuless'), actions: [
        IconButton(
          icon: const Icon(Icons.article_outlined),
          tooltip: '保存したノート',
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const NotesPage())),
        ),
        IconButton(
          icon: const Icon(Icons.settings),
          tooltip: '設定',
          onPressed: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const SettingsPage())),
        ),
      ]),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: ListTile(
              leading: Icon(ble.ready ? Icons.bluetooth_connected : Icons.bluetooth_disabled),
              title: Text(paired ? (ble.pendantName ?? 'Pendant') : 'ペンダント未設定'),
              subtitle: Text('Bluetooth: ${ble.bluetoothState} / ${ble.ready ? "接続中" : "未接続"}'
                  '${(drain?.battery ?? -1) >= 0 ? "\n電池 ${drain!.battery}%${drain!.batteryAt == null ? "" : "(${_hm(drain!.batteryAt!).substring(0, 5)} 時点)"}" : ""}'
                  '${_recordingLine()}'),
              isThreeLine: (drain?.battery ?? -1) >= 0 || _recordingLine().isNotEmpty,
              trailing: paired ? TextButton(onPressed: ble.forget, child: const Text('解除')) : null,
            ),
          ),
          if (drain != null)
            Card(
              child: ListTile(
                leading: Icon(
                  drain!.tailscaleUp ? Icons.vpn_key : Icons.vpn_key_off,
                  color: drain!.tailscaleUp ? Colors.green : Theme.of(context).colorScheme.error,
                ),
                title: Text('Tailscale: ${drain!.tailscaleUp ? "接続中" : "切断中"}'),
                subtitle: Text(drain!.tailscaleUp
                    ? '自宅の Mac に文字起こしを依頼できます'
                    : 'Tailscale をオンにしてください(家の中なら、予備の URL があればそちらを使います)'),
              ),
            ),
          if (paired) _drainCard(),
          if (paired) _transcribeCard(),
          if (ble.lastError != null) Text(ble.lastError!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
          if (!paired) ...[
            const SizedBox(height: 8),
            FilledButton.icon(
              onPressed: ble.scanning ? ble.stopScan : ble.startScan,
              icon: const Icon(Icons.search),
              label: Text(ble.scanning ? 'スキャン停止' : 'ペンダントを探す'),
            ),
            for (final p in ble.found.values)
              ListTile(
                title: Text(p.name.isEmpty ? '(no name)' : p.name),
                subtitle: Text('${p.uuid}  RSSI ${p.rssi}'),
                onTap: () => ble.pair(p),
              ),
          ],
          const SizedBox(height: 16),
          Text('取り込み済みの録音 (${files.length})', style: Theme.of(context).textTheme.titleMedium),
          for (final f in files)
            ListTile(
              dense: true,
              title: Text(f.path.split(Platform.pathSeparator).last),
              subtitle: Text('${(f.statSync().size / 1024).round()} KB'),
            ),
        ],
      ),
    );
  }
}
