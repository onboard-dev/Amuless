import 'package:flutter/material.dart';

import 'drain/drain_schedule.dart';

/// Why monitoring stopped or resumed: service start/stop, BLE connect/disconnect, memory warnings,
/// and the reason Android gave for ending the previous process.
class EventLogPage extends StatefulWidget {
  const EventLogPage({super.key});

  @override
  State<EventLogPage> createState() => _EventLogPageState();
}

class _EventLogPageState extends State<EventLogPage> {
  List<String> _lines = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load([String? op]) async {
    final l = await DrainSchedule.events(op);
    if (mounted) setState(() => _lines = l);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('動作の記録'), actions: [
        IconButton(onPressed: _load, icon: const Icon(Icons.refresh), tooltip: '更新'),
        IconButton(onPressed: () => _load('clear'), icon: const Icon(Icons.delete_sweep_outlined), tooltip: '消去'),
      ]),
      body: _lines.isEmpty
          ? const Center(child: Text('記録なし'))
          : ListView(
              reverse: true,
              padding: const EdgeInsets.all(12),
              children: [for (final l in _lines.reversed) Padding(padding: const EdgeInsets.symmetric(vertical: 3), child: SelectableText(l, style: const TextStyle(fontSize: 12.5)))],
            ),
    );
  }
}
