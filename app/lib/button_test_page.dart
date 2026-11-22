import 'dart:async';

import 'package:flutter/material.dart';

import 'drain/drain_schedule.dart';

/// Records whatever the pendant sends besides audio, so a button press can be seen.
class ButtonTestPage extends StatefulWidget {
  const ButtonTestPage({super.key});

  @override
  State<ButtonTestPage> createState() => _ButtonTestPageState();
}

class _ButtonTestPageState extends State<ButtonTestPage> {
  bool _enabled = false;
  List<String> _lines = const [];
  Timer? _timer;

  @override
  void initState() {
    super.initState();
    _refresh();
    _timer = Timer.periodic(const Duration(seconds: 2), (_) => _refresh());
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }

  Future<void> _refresh([String? op]) async {
    final r = await DrainSchedule.probe(op);
    if (!mounted) return;
    setState(() {
      _enabled = r.enabled;
      _lines = r.lines;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('ボタンテスト'), actions: [
        IconButton(onPressed: () => _refresh('clear'), icon: const Icon(Icons.delete_sweep_outlined), tooltip: '消去'),
      ]),
      body: Column(children: [
        SwitchListTile(
          title: const Text('テストを有効にする'),
          subtitle: const Text('オンにして、ペンダントを再接続し(ペアリングし直さず、電源の入れ直しか、アプリの再起動)、ボタンを押してください。使い終わったらオフに。'),
          value: _enabled,
          onChanged: (v) => _refresh(v ? 'on' : 'off'),
        ),
        const Divider(height: 1),
        Expanded(
          child: _lines.isEmpty
              ? const Center(child: Text('記録なし'))
              : ListView(
                  reverse: true,
                  padding: const EdgeInsets.all(12),
                  children: [for (final l in _lines.reversed) SelectableText(l, style: const TextStyle(fontFamily: 'monospace', fontSize: 12))],
                ),
        ),
      ]),
    );
  }
}
