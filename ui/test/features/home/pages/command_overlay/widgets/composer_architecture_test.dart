import 'package:flutter_test/flutter_test.dart';
import 'package:ui/features/home/pages/command_overlay/state/chat_composer_state_machine.dart';

void main() {
  group('ChatComposerStateMachine 架构行为验证', () {
    test('验证输入区状态机在焦点、键盘与弹窗之间的原子流转，避免并发交互竞态', () {
      final machine = ChatComposerStateMachine(hasText: false, hasFocus: false);

      // 初始解耦状态验证
      expect(machine.value.hasText, isFalse);
      expect(machine.value.hasFocus, isFalse);
      expect(machine.value.isHovered, isFalse);
      expect(machine.value.openPopups, isEmpty);
      expect(machine.value.openingPopups, isEmpty);

      // 悬停状态隔离，不应影响输入框展开或焦点
      machine.hoverChanged(true);
      expect(machine.value.isHovered, isTrue);
      expect(machine.value.expandsTextField, isFalse);

      // 弹窗状态生命周期流转（opening -> open -> closed）
      expect(
        machine.beginPopupOpening(ChatComposerPopup.compactActions),
        isTrue,
      );
      expect(
        machine.value.isPopupOpening(ChatComposerPopup.compactActions),
        isTrue,
      );
      expect(
        machine.value.isPopupActive(ChatComposerPopup.compactActions),
        isTrue,
      );

      machine.popupOpened(ChatComposerPopup.compactActions);
      expect(
        machine.value.isPopupOpen(ChatComposerPopup.compactActions),
        isTrue,
      );
      expect(
        machine.value.isPopupOpening(ChatComposerPopup.compactActions),
        isFalse,
      );

      machine.popupClosed(ChatComposerPopup.compactActions);
      expect(
        machine.value.isPopupActive(ChatComposerPopup.compactActions),
        isFalse,
      );
    });

    test('运行时主动作正确分流排队(queue)与停止(cancel)，空闲时分流发送(send)', () {
      final machine = ChatComposerStateMachine(hasText: true, hasFocus: true);

      // 运行中且有输入文本：主动作为排队发送(queue)
      expect(
        machine.value.primaryAction(
          isProcessing: true,
          hasAttachments: false,
          hasExternalPayload: false,
        ),
        ChatComposerPrimaryAction.queue,
      );

      // 运行中但无输入负载：主动作为停止当前任务(cancel)
      expect(
        machine.value.primaryAction(
          isProcessing: true,
          hasAttachments: false,
          hasExternalPayload: false,
          hasTextOverride: false,
        ),
        ChatComposerPrimaryAction.cancel,
      );

      // 非运行中且有输入：主动作为普通发送(send)
      expect(
        machine.value.primaryAction(
          isProcessing: false,
          hasAttachments: false,
          hasExternalPayload: false,
        ),
        ChatComposerPrimaryAction.send,
      );
    });
  });
}
