import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:ui/models/scheduled_task.dart';
import 'package:ui/services/assists_core_service.dart';
import 'package:ui/services/scheduled_task_storage_service.dart';

/// UI adapter for the existing native AlarmManager scheduler.
/// Task admission, ACP execution and recurrence belong to the Kotlin host.
class ScheduledTaskSchedulerService {
  static Future<void> initialize() async {
    try {
      final tasks = await ScheduledTaskStorageService.loadScheduledTasks();
      await AssistsMessageService.syncWorkspaceScheduledTasks(
        tasks.map((task) => task.toJson()).toList(),
      );
    } catch (error) {
      debugPrint('ScheduledTaskSchedulerService: Initialize error - $error');
    }
  }

  static void scheduleTask(ScheduledTask task) {
    if (!task.isEnabled) {
      cancelTask(task.id);
      return;
    }
    unawaited(
      AssistsMessageService.upsertWorkspaceScheduledTask(task.toJson()),
    );
  }

  static void cancelTask(String taskId) {
    unawaited(AssistsMessageService.deleteWorkspaceScheduledTask(taskId));
  }

  static void cancelAllTasks() {
    unawaited(AssistsMessageService.syncWorkspaceScheduledTasks(const []));
  }
}
