part of 'chat_page.dart';

mixin _ChatPageBrowserMixin on _ChatPageStateBase {
  @override
  String _browserSnapshotSignature(ChatBrowserSessionSnapshot? snapshot) {
    if (snapshot == null) {
      return '';
    }
    return [
      snapshot.available ? '1' : '0',
      snapshot.workspaceId,
      snapshot.activeTabId?.toString() ?? '',
      snapshot.currentUrl,
      snapshot.title,
      snapshot.userAgentProfile ?? '',
      snapshot.isBookmarked ? '1' : '0',
      snapshot.canGoBack ? '1' : '0',
      snapshot.canGoForward ? '1' : '0',
      snapshot.isLoading ? '1' : '0',
      snapshot.hasSslError ? '1' : '0',
      snapshot.isDesktopMode ? '1' : '0',
      snapshot.riskChallengeDetected ? '1' : '0',
      snapshot.riskChallengeKind ?? '',
      snapshot.recommendedNextAction ?? '',
      snapshot.throttleDelayMs?.toString() ?? '',
      snapshot.activeDownloadCount.toString(),
      snapshot.tabs.length.toString(),
      snapshot.downloads.length.toString(),
      snapshot.userscriptSummary.installedScripts.length.toString(),
      snapshot.externalOpenPrompt?.requestId ?? '',
      snapshot.pendingDialog?.requestId ?? '',
      snapshot.permissionPrompt?.requestId ?? '',
    ].join('|');
  }

  @override
  void _scheduleBrowserSessionRefreshIfNeeded() {
    if (_activeSurfaceMode != ChatSurfaceMode.normal) {
      return;
    }
    final browserMode = _activeConversationMode;
    final snapshot = _runtimeForMode(browserMode)?.browserSessionSnapshot;
    if (snapshot == null ||
        !snapshot.matchesWorkspace(_expectedBrowserWorkspaceId)) {
      return;
    }
    final signature = _browserSnapshotSignature(snapshot);
    if (signature.isEmpty ||
        signature == _lastObservedBrowserSnapshotSignature) {
      return;
    }
    _lastObservedBrowserSnapshotSignature = signature;
    unawaited(_refreshLiveBrowserSessionSnapshot(syncRuntime: true));
  }

  @override
  Future<void> _refreshLiveBrowserSessionSnapshot({
    bool syncRuntime = false,
  }) async {
    final browserMode = _activeConversationMode;
    final expectedWorkspaceId = chatConversationWorkspaceId(
      _modeState(browserMode).currentConversationId,
    );
    final snapshot = await AgentBrowserSessionService.getSnapshot(
      workspaceId: expectedWorkspaceId,
    );
    if (!mounted) {
      return;
    }
    final resolved = snapshot?.matchesWorkspace(expectedWorkspaceId) == true
        ? snapshot
        : null;
    if (syncRuntime) {
      final runtime = _runtimeForMode(browserMode);
      if (runtime != null) {
        runtime.browserSessionSnapshot = resolved;
      } else {
        _modeState(browserMode).browserSessionSnapshot = resolved;
      }
    }

    // The platform snapshot request is asynchronous. A Harness/conversation
    // switch may complete while it is in flight; keep the old runtime updated
    // but never paint that old browser session over the newly visible chat.
    if (browserMode != _activeConversationMode) {
      return;
    }

    if (!mounted) {
      return;
    }
    setState(() {
      _liveBrowserSessionSnapshot = resolved;
    });
  }

  @override
  void _handleBrowserSessionSnapshotChanged(Map<String, dynamic> raw) {
    final snapshot = ChatBrowserSessionSnapshot.fromMap(raw);
    final browserMode = _browserModeForWorkspace(snapshot.workspaceId);
    if (browserMode == null) {
      return;
    }
    final expectedWorkspaceId = chatConversationWorkspaceId(
      _modeState(browserMode).currentConversationId,
    );
    final resolved =
        snapshot.matchesWorkspace(expectedWorkspaceId) && snapshot.available
        ? snapshot
        : null;
    final runtime = _runtimeForMode(browserMode);
    if (runtime != null) {
      runtime.browserSessionSnapshot = resolved;
    } else {
      _modeState(browserMode).browserSessionSnapshot = resolved;
    }
    if (browserMode != _activeConversationMode) {
      return;
    }
    if (!mounted) {
      _liveBrowserSessionSnapshot = resolved;
      return;
    }
    setState(() {
      _liveBrowserSessionSnapshot = resolved;
    });
  }

  @override
  void _setChatIslandDisplayLayerForMode(
    ChatPageMode mode,
    ChatIslandDisplayLayer layer,
  ) {
    final resolvedLayer = mode == ChatPageMode.openclaw
        ? ChatIslandDisplayLayer.mode
        : layer;
    _modeState(mode).chatIslandDisplayLayer = resolvedLayer;
    final runtime = _runtimeForMode(mode);
    if (runtime != null) {
      runtime.chatIslandDisplayLayer = resolvedLayer;
    }
  }

  @override
  void _handleChatIslandDisplayLayerChanged(ChatIslandDisplayLayer layer) {
    if (_activeSurfaceMode != ChatSurfaceMode.normal) {
      return;
    }
    setState(() {
      _setChatIslandDisplayLayerForMode(_activeConversationMode, layer);
    });
  }

  @override
  Future<void> _handleTerminalToolTap() async {
    if (_activeSurfaceMode != ChatSurfaceMode.normal) {
      return;
    }
    final browserMode = _activeConversationMode;
    setState(() {
      _setChatIslandDisplayLayerForMode(
        browserMode,
        ChatIslandDisplayLayer.tools,
      );
    });
    try {
      await openNativeTerminal();
    } catch (error) {
      if (!mounted) {
        return;
      }
      showToast('打开终端失败: $error', type: ToastType.error);
    }
  }

  @override
  Future<void> _handleBrowserToolTap() async {
    if (_activeSurfaceMode != ChatSurfaceMode.normal) {
      return;
    }
    final browserMode = _activeConversationMode;
    if (!Platform.isAndroid) {
      showToast('当前平台暂不支持浏览器工具视图', type: ToastType.warning);
      return;
    }
    // Conversation switches bump this generation. An open that started on one
    // conversation must never land on another — even within the same chat
    // mode — and the permission sheet awaits long enough for the user to
    // switch, so guard every resume point, not just the mode check.
    final conversationGeneration = _conversationTargetRequestId;
    await _refreshLiveBrowserSessionSnapshot(syncRuntime: true);
    if (!mounted ||
        browserMode != _activeConversationMode ||
        conversationGeneration != _conversationTargetRequestId) {
      return;
    }
    final snapshot = _resolvedBrowserSessionSnapshot;
    if (snapshot == null || !snapshot.available) {
      showToast('当前会话还没有可用的浏览器会话', type: ToastType.warning);
      return;
    }
    final workspaceId = snapshot.workspaceId;
    try {
      // 浏览器页面由系统级悬浮窗宿主承载；此入口与 Agent 创建会话时
      // 自动弹出的悬浮球走同一个原生宿主，不存在第二个 WebView 宿主。
      final permitted = await AgentBrowserSessionService.isOverlayPermitted();
      if (!mounted || conversationGeneration != _conversationTargetRequestId) {
        return;
      }
      if (!permitted) {
        // 缺少悬浮窗权限时给出可操作的授权入口，而不是静默失败。
        final proceed = await AgentBrowserOverlayPermissionSheet.show(
          context,
        );
        if (!mounted ||
            !proceed ||
            conversationGeneration != _conversationTargetRequestId) {
          return;
        }
      }
      final result = await AgentBrowserSessionService.openOverlayHost(
        workspaceId,
      );
      if (!mounted || conversationGeneration != _conversationTargetRequestId) {
        return;
      }
      if (result?['opened'] != true) {
        final reason = result?['reason']?.toString();
        showToast(
          reason != null && reason.isNotEmpty
              ? '打开浏览器失败: $reason'
              : '打开浏览器失败',
          type: ToastType.error,
        );
      }
    } catch (error) {
      if (!mounted) {
        return;
      }
      showToast('打开浏览器失败: $error', type: ToastType.error);
    }
  }

  ChatPageMode? _browserModeForWorkspace(String workspaceId) {
    for (final mode in const <ChatPageMode>[
      ChatPageMode.normal,
      ChatPageMode.agent,
    ]) {
      if (workspaceId ==
          chatConversationWorkspaceId(_modeState(mode).currentConversationId)) {
        return mode;
      }
    }
    return null;
  }
}
