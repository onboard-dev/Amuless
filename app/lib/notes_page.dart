import 'package:flutter/material.dart';

import 'drain/drain_schedule.dart';

/// Notes Amuless saved to Memoria (newest first), read back from the server.
class NotesPage extends StatefulWidget {
  const NotesPage({super.key});

  @override
  State<NotesPage> createState() => _NotesPageState();
}

class _NotesPageState extends State<NotesPage> {
  List<NoteSummary>? _notes;
  String? _error;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final notes = await DrainSchedule.listNotes();
      if (mounted) setState(() => _notes = notes);
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  String _when(String iso) {
    final t = DateTime.tryParse(iso)?.toLocal();
    if (t == null) return '';
    return '${t.year}/${t.month}/${t.day} ${t.hour.toString().padLeft(2, '0')}:${t.minute.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('保存したノート'),
        actions: [IconButton(onPressed: _loading ? null : _load, icon: const Icon(Icons.refresh), tooltip: '更新')],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : _error != null
              ? Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(_error!, textAlign: TextAlign.center)))
              : (_notes ?? const []).isEmpty
                  ? const Center(child: Text('まだノートがありません'))
                  : ListView(children: [
                      for (final n in _notes!)
                        ListTile(
                          leading: const Icon(Icons.description_outlined),
                          title: Text(n.title),
                          subtitle: Text(_when(n.createdAt)),
                          onTap: () => Navigator.of(context).push(MaterialPageRoute(builder: (_) => NoteViewPage(note: n))),
                        ),
                    ]),
    );
  }
}

class NoteViewPage extends StatefulWidget {
  const NoteViewPage({super.key, required this.note});
  final NoteSummary note;

  @override
  State<NoteViewPage> createState() => _NoteViewPageState();
}

class _NoteViewPageState extends State<NoteViewPage> {
  String? _text;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final html = await DrainSchedule.readNote(widget.note.id);
      if (mounted) setState(() => _text = htmlToText(html));
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    }
  }

  /// Memoria stores HTML; show it as plain text (headings and paragraphs on their own lines).
  static String htmlToText(String html) {
    var s = html
        .replaceAll(RegExp(r'</(p|h[1-3]|li|blockquote|pre|tr)>', caseSensitive: false), '\n')
        .replaceAll(RegExp(r'<(br|hr)\s*/?>', caseSensitive: false), '\n')
        .replaceAll(RegExp(r'<[^>]+>'), '')
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .replaceAll('&quot;', '"')
        .replaceAll('&#39;', "'")
        .replaceAll('&nbsp;', ' ')
        .replaceAll('&amp;', '&');
    s = s.replaceAll(RegExp(r'\n{3,}'), '\n\n').trim();
    return s;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(widget.note.title, overflow: TextOverflow.ellipsis)),
      body: _error != null
          ? Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(_error!)))
          : _text == null
              ? const Center(child: CircularProgressIndicator())
              : SingleChildScrollView(
                  padding: const EdgeInsets.all(16),
                  child: SelectableText(_text!, style: const TextStyle(height: 1.6)),
                ),
    );
  }
}
