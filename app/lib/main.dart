import 'dart:io';

import 'package:flutter/material.dart';
import 'package:path_provider/path_provider.dart';
import 'package:permission_handler/permission_handler.dart';

import 'ble/pendant_ble.dart';

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

  @override
  void initState() {
    super.initState();
    ble.addListener(_refresh);
    _start();
  }

  @override
  void dispose() {
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

  void _refresh() {
    if (mounted) setState(() {});
    _loadFiles();
  }

  @override
  Widget build(BuildContext context) {
    final paired = ble.pendantId != null;
    return Scaffold(
      appBar: AppBar(title: const Text('Amuless')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: ListTile(
              leading: Icon(ble.ready ? Icons.bluetooth_connected : Icons.bluetooth_disabled),
              title: Text(paired ? (ble.pendantName ?? 'Pendant') : 'ペンダント未設定'),
              subtitle: Text('Bluetooth: ${ble.bluetoothState} / ${ble.ready ? "接続中" : "未接続"}'),
              trailing: paired ? TextButton(onPressed: ble.forget, child: const Text('解除')) : null,
            ),
          ),
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
