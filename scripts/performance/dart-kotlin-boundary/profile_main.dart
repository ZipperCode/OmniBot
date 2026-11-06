// Measurement entrypoint only; production entrypoint remains main_standard.dart.
import 'dart:async';
import 'dart:convert';
import 'dart:developer';
import 'dart:io';
import 'dart:ui';

import 'package:crypto/crypto.dart';
import 'package:flutter/scheduler.dart';
import 'package:flutter/widgets.dart';
import 'package:ui/services/storage_service.dart';
import 'package:ui/constants/storage_keys.dart';
import 'package:flutter/services.dart';
import 'package:ui/main_standard.dart' as app;
import 'package:ui/core/router/go_router_manager.dart';
import 'package:ui/services/conversation_service.dart';

import 'legacy_token_usage.dart';

import 'package:ui/services/office_preview_service.dart' as current;

import 'legacy_office_preview.dart' as original;

const channel = MethodChannel('cn.com.omnimind.bot/AssistCoreEvent');
Map<String, dynamic> officeJson(dynamic data) => {
  'kindLabel': data.kindLabel,
  'summary': data.summary,
  'truncated': data.truncated,
  'sections': [
    for (final s in data.sections)
      {
        'title': s.title,
        'subtitle': s.subtitle,
        'lines': s.lines,
        'tableRows': s.tableRows,
      },
  ],
};
String digest(dynamic value) =>
    sha256.convert(utf8.encode(jsonEncode(value))).toString();
Map<String, dynamic> dates(String? referenceTodayMs) {
  final now = DateTime.now();
  final today = referenceTodayMs == null
      ? DateTime(now.year, now.month, now.day)
      : DateTime.fromMillisecondsSinceEpoch(int.parse(referenceTodayMs));
  final start = today.subtract(const Duration(days: 111));
  var aligned = start;
  while (aligned.weekday != DateTime.monday) {
    aligned = aligned.subtract(const Duration(days: 1));
  }
  final weeks = ((today.difference(aligned).inDays + 1) / 7).ceil();
  return {
    'todayMs': today.millisecondsSinceEpoch,
    'startDateMs': start.millisecondsSinceEpoch,
    'alignedStartMs': aligned.millisecondsSinceEpoch,
    'weekStartsMs': List.generate(
      weeks,
      (i) => aligned.add(Duration(days: i * 7)).millisecondsSinceEpoch,
    ),
  };
}

Future<dynamic> stats(bool native, String? referenceTodayMs) async {
  final d = dates(referenceTodayMs);
  if (native) {
    final conversation = Map<String, dynamic>.from(
      await channel.invokeMethod('getConversationActivitySummary', d),
    );
    final token = await channel.invokeMethod('getTokenUsageSummary', d);
    conversation.remove('localSummary');
    return {'conversation': conversation, 'token': token};
  }
  final c = await ConversationService.getAllConversations(
    includeArchived: true,
  );
  final today = DateTime.fromMillisecondsSinceEpoch(d['todayMs']);
  final start = DateTime.fromMillisecondsSinceEpoch(d['startDateMs']);
  String key(DateTime date) =>
      '${date.year}-${date.month.toString().padLeft(2, '0')}-${date.day.toString().padLeft(2, '0')}';
  final activity = <String, int>{};
  var total = 0;
  for (final x in c) {
    final t = DateTime.fromMillisecondsSinceEpoch(x.createdAt);
    final day = DateTime(t.year, t.month, t.day);
    if (day.isBefore(start) || day.isAfter(today)) continue;
    activity[key(day)] = (activity[key(day)] ?? 0) + 1;
    total++;
  }
  var streak = 0;
  var check = today;
  while ((activity[key(check)] ?? 0) > 0) {
    streak++;
    check = check.subtract(const Duration(days: 1));
  }
  final aligned = DateTime.fromMillisecondsSinceEpoch(d['alignedStartMs']);
  final starts = List<int>.from(d['weekStartsMs']);
  final records = await TokenUsageService.getRecordsSince(d['alignedStartMs']);
  final buckets = List.generate(
    starts.length,
    (i) => <String, dynamic>{
      'weekStartMs': starts[i],
      'modelTokens': <String, int>{},
      'cachedTokens': 0,
    },
  );
  var cached = 0;
  final totals = <String, int>{};
  for (final r in records) {
    final days = DateTime.fromMillisecondsSinceEpoch(r.createdAt)
        .difference(aligned)
        .inDays;
    if (days < 0) continue;
    final i = days ~/ 7;
    if (i >= buckets.length) continue;
    buckets[i]['cachedTokens'] += r.cachedTokens;
    cached += r.cachedTokens;
    final tokens = r.totalTokens;
    final model = r.modelId;
    if (tokens > 0) {
      final map = buckets[i]['modelTokens'] as Map<String, int>;
      map[model] = (map[model] ?? 0) + tokens;
      totals[model] = (totals[model] ?? 0) + tokens;
    }
  }
  final order = totals.keys.toList()
    ..sort((a, b) {
      final v = totals[b]!.compareTo(totals[a]!);
      return v != 0 ? v : a.compareTo(b);
    });
  return {
    'conversation': {
      'activityMap': activity,
      'totalConversations': total,
      'currentStreak': streak,
    },
    'token': {
      'weeklyData': buckets,
      'totalCached': cached,
      'modelTotals': totals,
      'modelOrder': order,
    },
  };
}

dynamic canonical(dynamic x) {
  if (x is Map) {
    final keys = x.keys.map((k) => k.toString()).toList()..sort();
    return {for (final k in keys) k: canonical(x[k])};
  }
  if (x is List) return x.map(canonical).toList();
  return x;
}

Future<void> main(List<String> args) async {
  const testPackage = 'cn.com.omnimind.bot.boundaryperf';
  if (!Platform.isAndroid ||
      File('/proc/self/cmdline').readAsStringSync().split('\u0000').first !=
          testPackage) {
    throw StateError(
      'The measurement entrypoint runs only inside the independent boundaryperf package',
    );
  }
  WidgetsFlutterBinding.ensureInitialized();
  await StorageService.init();
  await StorageService.setBool(StorageKeys.welcomeCompleted, true);
  await app.main([...args, '--route=/task/execution_history']);
  final frames = <Map<String, int>>[];
  var collecting = false;
  SchedulerBinding.instance.addTimingsCallback((ts) {
    if (!collecting) return;
    for (final t in ts) {
      frames.add({
        'start': t.timestampInMicroseconds(FramePhase.buildStart),
        'build': t.buildDuration.inMicroseconds,
        'raster': t.rasterDuration.inMicroseconds,
        'total': t.totalSpan.inMicroseconds,
      });
    }
  });
  registerExtension('ext.omnibot.boundary', (method, params) async {
    try {
      final op = params['op'];
      dynamic value;
      if (op == 'transfer') {
        final d = dates(params['todayMs']);
        final oldConversation = await channel.invokeMethod('getConversations', {
          'includeArchived': true,
          'archivedOnly': false,
        });
        final oldToken = await channel.invokeMethod('getTokenUsageRecords', {
          'since': d['alignedStartMs'],
        });
        final newConversation = await channel.invokeMethod(
          'getConversationActivitySummary',
          d,
        );
        final newToken = await channel.invokeMethod('getTokenUsageSummary', d);
        const codec = StandardMessageCodec();
        value = {
          'beforeBytes':
              codec.encodeMessage(oldConversation)!.lengthInBytes +
              codec.encodeMessage(oldToken)!.lengthInBytes,
          'afterBytes':
              codec.encodeMessage(newConversation)!.lengthInBytes +
              codec.encodeMessage(newToken)!.lengthInBytes,
          'conversationRows': (oldConversation as List).length,
          'tokenRows': (oldToken as List).length,
        };
        return ServiceExtensionResponse.result(jsonEncode(value));
      }
      if (op == 'route') {
        GoRouterManager.push('/task/execution_history');
        value = {'opened': true};
      } else if (op == 'pop') {
        GoRouterManager.pop();
        value = {'popped': true};
      } else if (op == 'startFrames') {
        frames.clear();
        collecting = true;
        value = {'start': Timeline.now};
      } else if (op == 'stopFrames') {
        collecting = false;
        value = {'end': Timeline.now, 'frames': List.of(frames)};
      } else if (op == 'office' || op == 'stats') {
        final native = params['native'] == 'true';
        final n = int.parse(params['n'] ?? '8');
        if (n < 1 || n > 1000) throw ArgumentError('n must be 1..1000');
        final samples = <Map<String, dynamic>>[];
        dynamic last;
        for (var i = 0; i < n + 2; i++) {
          final gaps = <int>[];
          var previous = Timeline.now;
          final heartbeat = Timer.periodic(const Duration(milliseconds: 1), (
            _,
          ) {
            final now = Timeline.now;
            gaps.add(now - previous);
            previous = now;
          });
          final sw = Stopwatch()..start();
          try {
            if (op == 'office') {
              final kind = params['kind']!;
              final name = {
                'office_word': 'word.docx',
                'office_sheet': 'sheet.xlsx',
                'office_slide': 'slides.pptx',
              }[kind];
              if (name == null)
                throw ArgumentError('Unknown generated Office fixture kind');
              final path = '/data/user/0/$testPackage/files/$name';
              last = officeJson(
                native
                    ? await current.OmnibotOfficePreviewService.loadPreview(
                        path: path,
                        previewKind: kind,
                      )
                    : await original.OmnibotOfficePreviewService.loadPreview(
                        path: path,
                        previewKind: kind,
                      ),
              );
            } else {
              last = await stats(native, params['todayMs']);
            }
            sw.stop();
            await Future<void>.delayed(const Duration(milliseconds: 3));
          } finally {
            sw.stop();
            heartbeat.cancel();
          }
          if (i >= 2)
            samples.add({
              'ms': sw.elapsedMicroseconds / 1000,
              'maxTimerGapMs':
                  gaps.fold<int>(0, (a, b) => a > b ? a : b) / 1000,
            });
          await Future<void>.delayed(const Duration(milliseconds: 120));
        }
        value = {
          'samples': samples,
          'locale': PlatformDispatcher.instance.locale.toLanguageTag(),
          if (op == 'stats')
            'statsInvocationOrder':
                'conversation then token; UI loads them concurrently',
          'sha256': digest(canonical(last)),
          'jsonBytes': utf8.encode(jsonEncode(last)).length,
        };
        if (op == 'office')
          value['sections'] = (last['sections'] as List).length;
        if (params['dump'] == 'true') value['output'] = canonical(last);
        if (op == 'stats') value['summary'] = canonical(last);
      } else {
        throw ArgumentError('Unknown operation');
      }
      return ServiceExtensionResponse.result(jsonEncode(value));
    } catch (e, st) {
      return ServiceExtensionResponse.error(
        ServiceExtensionResponse.extensionError,
        '$e\n$st',
      );
    }
  });
}
