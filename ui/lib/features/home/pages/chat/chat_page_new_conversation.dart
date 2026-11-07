part of 'chat_page.dart';

/// Presentation for an unbound draft. Conversation/session admission remains
/// with the existing target lifecycle and send barrier.
mixin _ChatPageNewConversationMixin on _ChatPageStateBase {
  @override
  bool _isFullAccessDraftSelected = false;
  @override
  bool get _showNewConversationModeSelector =>
      !_isWorkspaceSurface &&
      !_isFirstUseTourActive &&
      _resolvedThreadTarget?.isNewConversation == true &&
      (_resolvedThreadTarget?.initialMessage?.trim().isEmpty ?? true) &&
      _currentConversationId == null &&
      _messages.isEmpty &&
      !_isAiResponding;

  @override
  bool get _canSwitchNewConversationMode =>
      _showNewConversationModeSelector &&
      !_harnessSwitchSendBarrier.isActive &&
      !_sendMessageInFlightTargetIds.contains(_conversationTargetRequestId);

  @override
  String get _newConversationModeId {
    if (_activeMode == ChatPageMode.normal && _isPureChatSelected) {
      return kNewConversationPureChatModeId;
    }
    if (_isFullAccessDraftSelected && _showNewConversationModeSelector) {
      return kNewConversationFullAccessModeId;
    }
    return _activeAcpAgentId ?? _kXiaowanAcpAgentId;
  }
  @override
  Future<void> _selectNewConversationMode(String modeId) async {
    final id = modeId.trim();
    if (!_canSwitchNewConversationMode || id == _newConversationModeId) {
      return;
    }
    final isFullAccess = id == kNewConversationFullAccessModeId;
    final pureChat = id == kNewConversationPureChatModeId;
    bool isAvailable() =>
        pureChat ||
        isFullAccess ||
        _chatAcpAgentModeOptions.any(
          (option) => option.id == id && option.isAvailable,
        );
    if (!isAvailable()) return;
    final originRequestId = _conversationTargetRequestId;
    final generation = _harnessSwitchSendBarrier.begin();
    setState(() {});
    var applied = false;
    try {
      // The first frame can be interactive while the initial target is still
      // loading. Finish that owner before applying the user's next draft.
      await _conversationBootstrapFuture;
      if (!mounted ||
          !_isConversationTargetRequestCurrent(originRequestId) ||
          !_showNewConversationModeSelector) {
        return;
      }
      await _harnessSwitchSendBarrier.runIfCurrent(generation, () async {
        if (!_isConversationTargetRequestCurrent(originRequestId) ||
            !_showNewConversationModeSelector ||
            !isAvailable()) {
          return;
        }
        _isFullAccessDraftSelected = isFullAccess;
        // 同步更新权限模式：全权模式对应 fullAccess，关闭恢复默认 defaultMode
        _agentPermissionMode = isFullAccess
            ? AgentPermissionMode.fullAccess
            : AgentPermissionMode.defaultMode;
        final targetAgentId = isFullAccess
            ? _kXiaowanAcpAgentId
            : (pureChat ? null : id);
        final target = ConversationThreadTarget.newConversation(
          mode: pureChat ? ConversationMode.chatOnly : ConversationMode.agent,
          agentId: targetAgentId,
          requestKey: DateTime.now().microsecondsSinceEpoch.toString(),
        );
        // 明确草稿目标归属于本次选择，保留现有输入内容
        await _applyConversationThreadTarget(target, preserveComposer: true);
        final unboundSession = _unboundConfigurationSessions[ChatPageMode.agent];
        if (unboundSession != null &&
            unboundSession.agentId == targetAgentId &&
            unboundSession.sessionId.isNotEmpty) {
          unawaited(AgentRuntimeService.setSessionMode(
            sessionId: unboundSession.sessionId,
            agentId: targetAgentId,
            modeId: isFullAccess ? 'agent_full_access' : 'default',
          ));
        }
        applied =
            _resolvedThreadTarget?.requestKey == target.requestKey &&
            _newConversationModeId == id;
      });
    } catch (error) {
      if (mounted &&
          _harnessSwitchSendBarrier.isCurrent(generation) &&
          _showNewConversationModeSelector) {
        showToast(
          LegacyTextLocalizer.isEnglish
              ? 'Unable to switch conversation mode: $error'
              : '切换会话模式失败：$error',
          type: ToastType.error,
        );
      }
    } finally {
      _harnessSwitchSendBarrier.finish(generation, succeeded: applied);
      if (mounted) setState(() {});
    }
  }
}
