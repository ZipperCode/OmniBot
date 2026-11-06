import 'dart:convert';
import 'dart:io';

import 'package:vm_service/vm_service_io.dart';

const package = 'cn.com.omnimind.bot.boundaryperf';

Future<void> main(List<String> args) async {
  final options = <String, String>{};
  var captureFrames = false;
  for (var index = 0; index < args.length; index++) {
    if (args[index] == '--capture-frames') {
      captureFrames = true;
    } else if (args[index].startsWith('--') && index + 1 < args.length) {
      final key = args[index];
      options[key] = args[++index];
    } else {
      stderr.writeln(
        'Expected --output DIR --params JSON [--timeout-seconds 180] [--capture-frames]',
      );
      exitCode = 2;
      return;
    }
  }
  try {
    final output = Directory(options['--output']!);
    final metadata = jsonDecode(
      File('${output.path}/boundary-perf.json').readAsStringSync(),
    );
    final connection = jsonDecode(
      File('${output.path}/vm-connection-private.json').readAsStringSync(),
    );
    if (metadata['package'] != package || connection['package'] != package) {
      throw StateError('The output is not an isolated boundaryperf workspace');
    }
    final address = Uri.parse(connection['ws'] as String);
    if (address.scheme != 'ws' || address.host != '127.0.0.1') {
      throw StateError('Only a loopback forwarded VM service is accepted');
    }
    final parameters = Map<String, String>.from(
      jsonDecode(options['--params']!),
    );
    final dataset = File('${output.path}/dataset.json');
    if (dataset.existsSync() && !parameters.containsKey('todayMs')) {
      parameters['todayMs'] =
          '${jsonDecode(dataset.readAsStringSync())['todayMs']}';
    }
    final timeout = Duration(
      seconds: int.parse(options['--timeout-seconds'] ?? '180'),
    );
    final vm = await vmServiceConnectUri(address.toString()).timeout(timeout);
    try {
      final state = await vm.getVM().timeout(timeout);
      final isolate = state.isolates!.firstWhere((item) => item.name == 'main');
      final details = await vm.getIsolate(isolate.id!).timeout(timeout);
      if (!(details.extensionRPCs ?? const []).contains(
        'ext.omnibot.boundary',
      )) {
        throw StateError(
          'The isolated process has not registered the boundary extension',
        );
      }
      Future<dynamic> call(Map<String, String> params) async =>
          (await vm
                  .callServiceExtension(
                    'ext.omnibot.boundary',
                    isolateId: isolate.id,
                    args: params,
                  )
                  .timeout(timeout))
              .json;
      dynamic frameResult;
      dynamic result;
      if (captureFrames) await call({'op': 'startFrames'});
      try {
        result = await call(parameters);
        if (captureFrames)
          await Future<void>.delayed(const Duration(milliseconds: 1500));
      } finally {
        if (captureFrames) frameResult = await call({'op': 'stopFrames'});
      }
      stdout.writeln(
        jsonEncode(
          captureFrames
              ? {'result': result, 'frameTimings': frameResult}
              : result,
        ),
      );
    } finally {
      await vm.dispose();
    }
  } catch (error) {
    // WebSocket exceptions can contain the authenticated VM address. Do not print them.
    stderr.writeln(
      'Benchmark request failed (${error.runtimeType}); inspect setup and isolated process status.',
    );
    exitCode = 1;
  }
}
