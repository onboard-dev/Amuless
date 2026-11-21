import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../gen/pigeon_communicator.g.dart';

const limitlessServiceUuid = '632de001-604c-446b-a80f-7963e950f3fb';
const limitlessRxCharUuid = '632de003-604c-446b-a80f-7963e950f3fb';

/// Bridge to omi's native BLE layer (scan / pair / Transcribe Later flash drain).
///
/// The native foreground service does the actual work. It reads these
/// SharedPreferences keys (Flutter writes them with the "flutter." prefix):
///  - batchModeEnabled         : true  -> record to pendant flash, drain into .bin files
///  - nativeBleStreamConfig    : which device / GATT characteristic to listen on
///  - batchAudioDir            : where the .bin recordings are written
class PendantBle extends ChangeNotifier implements BleFlutterApi {
  PendantBle._();
  static final PendantBle instance = PendantBle._();

  static const _prefSavedId = 'amuless.pendantId';
  static const _prefSavedName = 'amuless.pendantName';

  final BleHostApi _host = BleHostApi();

  String bluetoothState = 'unknown';
  bool scanning = false;
  bool ready = false;
  String? pendantId;
  String? pendantName;
  final Map<String, BlePeripheral> found = {};
  final List<String> recordings = [];
  String? lastError;

  Future<void> init() async {
    BleFlutterApi.setUp(this);
    final prefs = await SharedPreferences.getInstance();
    pendantId = prefs.getString(_prefSavedId);
    pendantName = prefs.getString(_prefSavedName);
    try {
      bluetoothState = await _host.getBluetoothState();
    } catch (e) {
      lastError = '$e';
    }
    if (pendantId != null) {
      await _configureNative(pendantId!);
      await _connect(pendantId!);
    }
    notifyListeners();
  }

  Future<void> startScan() async {
    found.clear();
    scanning = true;
    lastError = null;
    notifyListeners();
    try {
      await _host.startScan(20, [limitlessServiceUuid]);
    } catch (e) {
      lastError = '$e';
      scanning = false;
    }
    notifyListeners();
  }

  Future<void> stopScan() async {
    try {
      await _host.stopScan();
    } catch (_) {}
    scanning = false;
    notifyListeners();
  }

  Future<void> pair(BlePeripheral p) async {
    await stopScan();
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_prefSavedId, p.uuid);
    await prefs.setString(_prefSavedName, p.name);
    pendantId = p.uuid;
    pendantName = p.name;
    await _configureNative(p.uuid);
    await _connect(p.uuid);
    notifyListeners();
  }

  Future<void> forget() async {
    final id = pendantId;
    if (id != null) {
      try {
        await _host.unmanageDevice(id);
      } catch (_) {}
    }
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_prefSavedId);
    await prefs.remove(_prefSavedName);
    await prefs.remove('nativeBleStreamConfig');
    await prefs.setBool('batchModeEnabled', false);
    pendantId = null;
    pendantName = null;
    ready = false;
    notifyListeners();
  }

  Future<void> _configureNative(String id) async {
    final prefs = await SharedPreferences.getInstance();
    final docs = await getApplicationDocumentsDirectory();
    await prefs.setString('batchAudioDir', docs.path);
    await prefs.setString(
      'nativeBleStreamConfig',
      jsonEncode({
        'deviceId': id,
        'codec': 'opus_fs320',
        'sampleRate': 16000,
        'source': 'limitless',
        'apiBaseUrl': '',
        'serviceUuid': limitlessServiceUuid,
        'characteristicUuid': limitlessRxCharUuid,
        'deviceType': 'limitless',
      }),
    );
    await prefs.setBool('batchModeEnabled', true);
    await prefs.setBool('nativeBleStreamingEnabled', false);
  }

  Future<void> _connect(String id) async {
    try {
      await _host.configureSavedReconnect(id, true);
      await _host.manageDevice(id, false);
    } catch (e) {
      lastError = '$e';
    }
  }

  // ── BleFlutterApi (callbacks from native) ──

  @override
  void onBluetoothStateChanged(String state) {
    bluetoothState = state;
    notifyListeners();
  }

  @override
  void onPeripheralDiscovered(BlePeripheral peripheral) {
    found[peripheral.uuid] = peripheral;
    notifyListeners();
  }

  @override
  void onDeviceReady(String peripheralUuid, List<BleService> services) {
    if (peripheralUuid.toUpperCase() == pendantId?.toUpperCase()) {
      ready = true;
      notifyListeners();
    }
  }

  @override
  void onPeripheralDisconnected(String peripheralUuid, String? error) {
    if (peripheralUuid.toUpperCase() == pendantId?.toUpperCase()) {
      ready = false;
      notifyListeners();
    }
  }

  @override
  void onBatchRecordingFinalized(String fileName) {
    recordings.insert(0, fileName);
    notifyListeners();
  }

  @override
  void onScanBlocked(String reason) {
    lastError = 'scan blocked: $reason';
    scanning = false;
    notifyListeners();
  }

  @override
  void onSavedReconnect(String peripheralUuid, int attempt, String result, int disconnectedMs) {}
  @override
  void onCharacteristicValueUpdated(String peripheralUuid, String serviceUuid, String characteristicUuid, Uint8List value) {}
  @override
  void onRssiUpdate(String peripheralUuid, int rssi) {}
  @override
  void onStateRestored(List<String> peripheralUuids) {}
  @override
  void onCaptureHealth(String peripheralUuid, String snapshot) {}
}
