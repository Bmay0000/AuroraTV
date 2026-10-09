import 'dart:convert';
import 'package:path/path.dart' as path;
import 'package:sqflite/sqflite.dart';

enum MediaKind { live, movie, series }

class MediaEntry {
  final String id, title, category, artwork, extension, streamId, directUrl, epgId;
  final MediaKind kind;
  final int year;
  final double rating;
  final bool favorite, hidden;
  const MediaEntry({
    required this.id, required this.title, required this.kind,
    this.category = '', this.artwork = '', this.extension = '',
    this.streamId = '', this.directUrl = '', this.epgId = '', this.year = 0, this.rating = 0,
    this.favorite = false, this.hidden = false,
  });

  factory MediaEntry.fromRow(Map<String, Object?> v) => MediaEntry(
    id: v['id'] as String,
    title: v['title'] as String? ?? '',
    kind: MediaKind.values.firstWhere((t) => t.name == v['kind'], orElse: () => MediaKind.live),
    category: v['category'] as String? ?? '',
    artwork: v['artwork'] as String? ?? '',
    extension: v['extension'] as String? ?? '',
    streamId: v['stream_id'] as String? ?? '',
    directUrl: v['direct_url'] as String? ?? '', epgId: v['epg_id'] as String? ?? '',
    year: v['year'] as int? ?? 0,
    rating: (v['rating'] as num?)?.toDouble() ?? 0,
    favorite: (v['favorite'] as int? ?? 0) == 1,
    hidden: (v['hidden'] as int? ?? 0) == 1,
  );

  Map<String, Object?> toRow() => {
    'id': id, 'title': title, 'kind': kind.name, 'category': category,
    'artwork': artwork, 'extension': extension, 'stream_id': streamId,
    'direct_url': directUrl, 'epg_id': epgId, 'year': year, 'rating': rating,
    'favorite': favorite ? 1 : 0, 'hidden': hidden ? 1 : 0,
  };

  String get cleanTitle => title
      .replaceFirst(RegExp(r'^(EN|ENG|US|UK|AU|NZ|VOD|MOVIE|SERIES)\s*[-|:]\s*', caseSensitive: false), '')
      .replaceAll(RegExp(r'\s+(4K|UHD|FHD|HD)\s*$', caseSensitive: false), '')
      .trim();

  static String normalize(String value) => value
      .toLowerCase()
      .replaceFirst(RegExp(r'^(en|eng|us|uk|au|nz|vod|movie|series)\s*[-|:]\s*'), '')
      .replaceAll(RegExp(r'\s*\((?:19|20)\d{2}\)\s*$'), '')
      .replaceAll(RegExp(r'[^a-z0-9]+'), '');

  bool get likelyEnglish {
    final code = category.toUpperCase();
    if (RegExp(r'(^|[^A-Z])(FR|DE|ES|IT|PT|TR|AR|RU|IN|HINDI|FRENCH|SPANISH|GERMAN|TURKISH|ARABIC)([^A-Z]|$)').hasMatch(code)) return false;
    return RegExp(r'(^|[^A-Z])(EN|ENG|UK|US|USA|CA|CANADA|NZ|AU|AUS|ENGLISH|BRITISH|NORTH AMERICA)([^A-Z]|$)').hasMatch(code) ||
        RegExp(r'^(EN|ENG|US|UK|NZ|AU)\s*[-|:]', caseSensitive: false).hasMatch(title);
  }
}

class TvProgramme {
  final String channelId, title, description;
  final DateTime start, end;
  const TvProgramme({required this.channelId, required this.title, required this.start, required this.end, this.description = ''});
  factory TvProgramme.fromRow(Map<String, Object?> r) => TvProgramme(
    channelId: r['channel_id'] as String,
    title: r['title'] as String,
    description: r['description'] as String? ?? '',
    start: DateTime.fromMillisecondsSinceEpoch(r['start_ms'] as int),
    end: DateTime.fromMillisecondsSinceEpoch(r['end_ms'] as int),
  );
}

class CatalogDatabase {
  Database? _db;
  Future<void> open() async {
    _db ??= await openDatabase(path.join(await getDatabasesPath(), 'aurora_flutter_2.db'), version: 3,
      onCreate: (db, version) async {
        await db.execute('CREATE TABLE media (id TEXT PRIMARY KEY, title TEXT NOT NULL, kind TEXT NOT NULL, category TEXT, artwork TEXT, extension TEXT, stream_id TEXT, direct_url TEXT, epg_id TEXT, year INTEGER DEFAULT 0, rating REAL DEFAULT 0, favorite INTEGER DEFAULT 0, hidden INTEGER DEFAULT 0)');
        await db.execute('CREATE INDEX idx_media_kind ON media(kind, hidden, category)');
        await db.execute('CREATE INDEX idx_media_title ON media(kind, title)');
        await db.execute('CREATE TABLE programme (channel_id TEXT, start_ms INTEGER, end_ms INTEGER, title TEXT, description TEXT, PRIMARY KEY(channel_id,start_ms))');
        await db.execute('CREATE INDEX idx_programme_time ON programme(channel_id,start_ms,end_ms)');
      }, onUpgrade: (db, old, now) async {
        if (old < 3) {
          await db.execute('CREATE TABLE IF NOT EXISTS programme (channel_id TEXT, start_ms INTEGER, end_ms INTEGER, title TEXT, description TEXT, PRIMARY KEY(channel_id,start_ms))');
          await db.execute('CREATE INDEX IF NOT EXISTS idx_programme_time ON programme(channel_id,start_ms,end_ms)');
          if (old >= 2) await db.execute('ALTER TABLE media ADD COLUMN epg_id TEXT');
        }
      });
  }

  Future<void> replaceKind(MediaKind kind, List<MediaEntry> entries) async {
    final db = _db!;
    // Preserve favorites and hidden flags across provider refreshes.
    final prefs = await db.query('media', columns: ['id','favorite','hidden'], where: 'kind=? AND (favorite=1 OR hidden=1)', whereArgs: [kind.name]);
    final flags = {for (final p in prefs) p['id'] as String: p};
    await db.transaction((txn) async {
      await txn.delete('media', where: 'kind=?', whereArgs: [kind.name]);
      for (var offset = 0; offset < entries.length; offset += 350) {
        final batch = txn.batch();
        for (final e in entries.skip(offset).take(350)) {
          final row = e.toRow();
          final f = flags[e.id];
          if (f != null) { row['favorite'] = f['favorite']; row['hidden'] = f['hidden']; }
          batch.insert('media', row, conflictAlgorithm: ConflictAlgorithm.replace);
        }
        await batch.commit(noResult: true);
      }
    });
  }

  Future<int> count(MediaKind kind) async {
    final row = await _db!.rawQuery('SELECT COUNT(*) AS n FROM media WHERE kind=? AND hidden=0', [kind.name]);
    return row.first['n'] as int? ?? 0;
  }

  Future<List<MediaEntry>> list(MediaKind kind, {
    String search = '', String category = '', bool english = false,
    bool favorites = false, int limit = 40, int offset = 0,
  }) async {
    final clauses = <String>['kind=?', 'hidden=0'];
    final args = <Object?>[kind.name];
    if (search.trim().isNotEmpty) {
      clauses.add('title LIKE ?');
      args.add('%${search.trim().replaceAll('%', r'\%').replaceAll('_', r'\_')}%');
    }
    if (category.isNotEmpty && category != 'All') {
      clauses.add('category=?'); args.add(category);
    }
    if (favorites) clauses.add('favorite=1');
    final actualLimit = english ? limit * 5 : limit;
    final rows = await _db!.query('media', where: clauses.join(' AND '), whereArgs: args,
      orderBy: 'year DESC, title COLLATE NOCASE ASC', limit: actualLimit, offset: offset);
    final parsed = rows.map(MediaEntry.fromRow);
    return (english ? parsed.where((e) => e.likelyEnglish) : parsed).take(limit).toList(growable: false);
  }

  Future<List<String>> categories(MediaKind kind) async {
    final rows = await _db!.rawQuery(
      'SELECT category, COUNT(*) n FROM media WHERE kind=? AND hidden=0 AND category<>\'\' GROUP BY category ORDER BY n DESC LIMIT 160', [kind.name]);
    return rows.map((e) => e['category'] as String).toList();
  }

  Future<void> mark(String id, {bool? favorite, bool? hidden}) async {
    final values = <String, Object?>{};
    if (favorite != null) values['favorite'] = favorite ? 1 : 0;
    if (hidden != null) values['hidden'] = hidden ? 1 : 0;
    if (values.isNotEmpty) await _db!.update('media', values, where: 'id=?', whereArgs: [id]);
  }

  Future<List<MediaEntry>> matchTitles(MediaKind kind, List<String> titles) async {
    final out = <MediaEntry>[];
    final seen = <String>{};
    for (final name in titles) {
      final parts = name.split(' ').where((v) => v.length > 3).toList();
      if (parts.isEmpty) continue;
      final results = await list(kind, search: parts.first, limit: 60);
      final normalized = MediaEntry.normalize(name);
      for (final e in results) {
        if (MediaEntry.normalize(e.cleanTitle) == normalized && seen.add(e.id)) {
          out.add(e); break;
        }
      }
    }
    return out;
  }

  Future<void> writePrograms(List<TvProgramme> programs) async {
    final db = _db!;
    await db.transaction((tx) async {
      await tx.delete('programme');
      for (var i=0;i<programs.length;i+=400) {
        final batch=tx.batch();
        for (final e in programs.skip(i).take(400)) {
          batch.insert('programme', {
            'channel_id':e.channelId,'start_ms':e.start.millisecondsSinceEpoch,
            'end_ms':e.end.millisecondsSinceEpoch,'title':e.title,
            'description':e.description,
          }, conflictAlgorithm:ConflictAlgorithm.replace);
        }
        await batch.commit(noResult:true);
      }
    });
  }

  Future<Map<String,List<TvProgramme>>> schedules(List<String> channelIds,DateTime start,DateTime end) async {
    final result = <String,List<TvProgramme>>{};
    if (channelIds.isEmpty) return result;
    for (var i=0;i<channelIds.length;i+=300) {
      final chunk=channelIds.skip(i).take(300).toList();
      final placeholders=List.filled(chunk.length,'?').join(',');
      final rows=await _db!.rawQuery(
        'SELECT * FROM programme WHERE channel_id IN ($placeholders) AND end_ms>? AND start_ms<? ORDER BY start_ms',
        [...chunk,start.millisecondsSinceEpoch,end.millisecondsSinceEpoch]);
      for (final r in rows) {
        final p=TvProgramme.fromRow(r);
        result.putIfAbsent(p.channelId,()=>[]).add(p);
      }
    }
    return result;
  }

  Future<void> close() async {await _db?.close();_db=null;}
}
