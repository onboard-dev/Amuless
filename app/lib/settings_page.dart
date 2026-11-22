import 'package:flutter/material.dart';

import 'button_test_page.dart';
import 'drain/drain_schedule.dart';

/// Whisper server, Memoria URL and API token. The token is stored encrypted on the native side.
class SettingsPage extends StatefulWidget {
  const SettingsPage({super.key});

  @override
  State<SettingsPage> createState() => _SettingsPageState();
}

class _SettingsPageState extends State<SettingsPage> {
  final _whisperUrl = TextEditingController();
  final _model = TextEditingController();
  final _memoriaUrl = TextEditingController();
  final _token = TextEditingController();
  bool _hasToken = false;
  String _notebookId = '';
  String _notebookTitle = '';
  bool _loadingNotebooks = false;
  bool _loaded = false;
  bool _testing = false;
  List<String> _results = const [];

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _whisperUrl.dispose();
    _model.dispose();
    _memoriaUrl.dispose();
    _token.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final s = await DrainSchedule.getSettings();
    if (!mounted) return;
    setState(() {
      _whisperUrl.text = s.whisperUrl;
      _model.text = s.whisperModel;
      _memoriaUrl.text = s.memoriaUrl;
      _hasToken = s.hasToken;
      _notebookId = s.notebookId;
      _notebookTitle = s.notebookTitle;
      _loaded = true;
    });
  }

  Future<void> _save() async {
    await DrainSchedule.saveSettings(
      whisperUrl: _whisperUrl.text,
      whisperModel: _model.text,
      memoriaUrl: _memoriaUrl.text,
      token: _token.text.isEmpty ? null : _token.text,
    );
    _token.clear();
    await _load();
  }

  Future<void> _pickNotebook() async {
    setState(() => _loadingNotebooks = true);
    await _save(); // the list needs the URL and token that are on screen
    List<Notebook> items = const [];
    String? error;
    try {
      items = await DrainSchedule.listNotebooks();
    } catch (e) {
      error = '$e';
    }
    if (!mounted) return;
    setState(() => _loadingNotebooks = false);
    if (error != null) {
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(error)));
      return;
    }
    final chosen = await showDialog<Notebook>(
      context: context,
      builder: (ctx) => SimpleDialog(
        title: const Text('保存先のノートブック'),
        children: [
          if (items.isEmpty) const Padding(padding: EdgeInsets.all(24), child: Text('ノートブックがありません')),
          for (final n in items)
            ListTile(
              leading: Icon(n.id == _notebookId ? Icons.radio_button_checked : Icons.radio_button_unchecked),
              title: Text(n.title),
              onTap: () => Navigator.of(ctx).pop(n),
            ),
        ],
      ),
    );
    if (chosen == null) return;
    await DrainSchedule.saveSettings(notebookId: chosen.id, notebookTitle: chosen.title);
    await _load();
  }

  Future<void> _clearToken() async {
    await DrainSchedule.saveSettings(token: '');
    await _load();
  }

  Future<void> _test() async {
    setState(() {
      _testing = true;
      _results = const [];
    });
    await _save();
    final r = await DrainSchedule.testConnections();
    if (mounted) {
      setState(() {
        _testing = false;
        _results = r;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('設定')),
      body: !_loaded
          ? const Center(child: CircularProgressIndicator())
          : ListView(padding: const EdgeInsets.all(16), children: [
              Text('文字起こしサーバー(mlx-audio)', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              TextField(
                controller: _whisperUrl,
                keyboardType: TextInputType.url,
                decoration: const InputDecoration(
                  labelText: 'ベース URL',
                  hintText: 'http://<MacのTailscale IP>:8000/v1',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _model,
                decoration: const InputDecoration(labelText: 'モデル名', border: OutlineInputBorder()),
              ),
              const SizedBox(height: 24),
              Text('memoria', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              TextField(
                controller: _memoriaUrl,
                keyboardType: TextInputType.url,
                decoration: const InputDecoration(
                  labelText: 'URL',
                  hintText: 'https://<ホスト>:8443',
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 12),
              TextField(
                controller: _token,
                obscureText: true,
                autocorrect: false,
                enableSuggestions: false,
                decoration: InputDecoration(
                  labelText: 'API トークン(mmr_...)',
                  helperText: _hasToken ? '保存済み(空欄のままなら変更しません)' : '未保存',
                  border: const OutlineInputBorder(),
                  suffixIcon: _hasToken
                      ? IconButton(onPressed: _clearToken, icon: const Icon(Icons.delete_outline), tooltip: 'トークンを削除')
                      : null,
                ),
              ),
              const SizedBox(height: 12),
              Card(
                margin: EdgeInsets.zero,
                child: ListTile(
                  leading: const Icon(Icons.book_outlined),
                  title: const Text('保存先のノートブック'),
                  subtitle: Text(_notebookId.isEmpty ? '未選択(タップして選ぶ)' : '$_notebookTitle\nセクション「文字起こし」に保存します'),
                  isThreeLine: _notebookId.isNotEmpty,
                  trailing: _loadingNotebooks
                      ? const SizedBox(width: 24, height: 24, child: CircularProgressIndicator(strokeWidth: 2))
                      : const Icon(Icons.chevron_right),
                  onTap: _loadingNotebooks ? null : _pickNotebook,
                ),
              ),
              const SizedBox(height: 12),
              Card(
                margin: EdgeInsets.zero,
                child: ListTile(
                  leading: const Icon(Icons.touch_app_outlined),
                  title: const Text('ペンダントのボタンテスト'),
                  subtitle: const Text('ボタンを押したときに信号が届くか調べます'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => const ButtonTestPage())),
                ),
              ),
              const SizedBox(height: 24),
              Row(children: [
                FilledButton(onPressed: _save, child: const Text('保存')),
                const SizedBox(width: 12),
                OutlinedButton(onPressed: _testing ? null : _test, child: Text(_testing ? 'テスト中…' : '保存して接続テスト')),
              ]),
              const SizedBox(height: 12),
              for (final r in _results) Padding(padding: const EdgeInsets.symmetric(vertical: 2), child: Text(r)),
              const SizedBox(height: 24),
              const Text(
                'スマホで Tailscale をオンにしておいてください。トークンはこの端末の暗号化領域にだけ保存され、画面やログには出ません。',
                style: TextStyle(fontSize: 12),
              ),
            ]),
    );
  }
}
