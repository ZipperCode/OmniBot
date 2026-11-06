part of 'chat_input_area.dart';

class _AgentPermissionOptionData {
  const _AgentPermissionOptionData({
    required this.mode,
    required this.label,
    required this.iconAsset,
  });

  final AgentPermissionMode mode;
  final String label;
  final String iconAsset;
}

class _AgentPermissionGlassMenuContent extends StatefulWidget {
  const _AgentPermissionGlassMenuContent({
    required this.width,
    required this.options,
    required this.selected,
    required this.selectedColor,
    required this.inactiveColor,
    required this.textColor,
    required this.onSelect,
  });

  final double width;
  final List<_AgentPermissionOptionData> options;
  final AgentPermissionMode selected;
  final Color selectedColor;
  final Color inactiveColor;
  final Color textColor;
  final ValueChanged<AgentPermissionMode> onSelect;

  @override
  State<_AgentPermissionGlassMenuContent> createState() =>
      _AgentPermissionGlassMenuContentState();
}

class _AgentPermissionGlassMenuContentState
    extends State<_AgentPermissionGlassMenuContent> {
  void _select(AgentPermissionMode mode) {
    widget.onSelect(mode);
  }

  Widget _buildIcon(_AgentPermissionOptionData option, bool selected) {
    return SvgPicture.asset(
      option.iconAsset,
      width: OmniMenuStyle.iconSize,
      height: OmniMenuStyle.iconSize,
      colorFilter: ColorFilter.mode(
        selected ? widget.selectedColor : widget.inactiveColor,
        BlendMode.srcIn,
      ),
    );
  }

  Widget _buildRow(_AgentPermissionOptionData option) {
    final isSelected = option.mode == widget.selected;
    return OmniMenuItem(
      key: ValueKey('chat-input-agent-permission-option-${option.mode.name}'),
      onTap: () => _select(option.mode),
      label: option.label,
      leading: _buildIcon(option, isSelected),
      selected: isSelected,
      trailing: isSelected
          ? Icon(Icons.check_rounded, color: widget.selectedColor)
          : null,
    );
  }

  @override
  Widget build(BuildContext context) {
    return OmniMenuSurface(
      width: widget.width,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [for (final option in widget.options) _buildRow(option)],
      ),
    );
  }
}

enum _AgentRunSettingsMenuPage { overview, models, reasoning }

class _AgentRunSettingsMenuContent extends StatefulWidget {
  const _AgentRunSettingsMenuContent({
    required this.width,
    required this.maxHeight,
    required this.modelHeader,
    required this.reasoningHeader,
    required this.searchHint,
    required this.noMatchesLabel,
    required this.emptyModelsLabel,
    required this.modelOptions,
    required this.currentModelId,
    required this.reasoningOptions,
    required this.currentReasoningEffort,
    required this.effortLabelBuilder,
    required this.selectedColor,
    required this.textColor,
    required this.onSelectModel,
    required this.onSelectReasoning,
  });

  final double width;
  final double maxHeight;
  final String modelHeader;
  final String reasoningHeader;
  final String searchHint;
  final String noMatchesLabel;
  final String emptyModelsLabel;
  final List<String> modelOptions;
  final String currentModelId;
  final List<String> reasoningOptions;
  final String currentReasoningEffort;
  final String Function(String) effortLabelBuilder;
  final Color selectedColor;
  final Color textColor;
  final ValueChanged<String> onSelectModel;
  final ValueChanged<String> onSelectReasoning;

  @override
  State<_AgentRunSettingsMenuContent> createState() =>
      _AgentRunSettingsMenuContentState();
}

class _AgentRunSettingsMenuContentState
    extends State<_AgentRunSettingsMenuContent> {
  static const int _searchThreshold = 5;
  static const Duration _pageAnimationDuration = Duration(milliseconds: 150);

  final TextEditingController _searchController = TextEditingController();
  late _AgentRunSettingsMenuPage _page;

  @override
  void initState() {
    super.initState();
    _page = widget.reasoningOptions.isEmpty
        ? _AgentRunSettingsMenuPage.models
        : _AgentRunSettingsMenuPage.overview;
    _searchController.addListener(_handleSearchChanged);
  }

  @override
  void dispose() {
    _searchController
      ..removeListener(_handleSearchChanged)
      ..dispose();
    super.dispose();
  }

  void _handleSearchChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  List<String> get _filteredModels {
    final query = _searchController.text.trim().toLowerCase();
    if (query.isEmpty) {
      return widget.modelOptions;
    }
    return widget.modelOptions
        .where((model) => model.toLowerCase().contains(query))
        .toList(growable: false);
  }

  void _showPage(_AgentRunSettingsMenuPage page) {
    if (_page == page) {
      return;
    }
    setState(() {
      _page = page;
      if (page != _AgentRunSettingsMenuPage.models) {
        _searchController.clear();
      }
    });
  }

  Widget _buildOverviewRow({
    required Key key,
    required IconData icon,
    required String label,
    required String value,
    required VoidCallback onTap,
  }) {
    final palette = context.omniPalette;
    return OmniMenuItem(
      key: key,
      onTap: onTap,
      child: Row(
        children: [
          Icon(icon),
          const SizedBox(width: OmniMenuStyle.iconGap),
          Text(label),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              value.isEmpty ? '—' : value,
              textAlign: TextAlign.end,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: TextStyle(color: palette.textSecondary),
            ),
          ),
          const SizedBox(width: 4),
          Icon(Icons.chevron_right_rounded, color: palette.textTertiary),
        ],
      ),
    );
  }

  Widget _buildOverview() {
    return Padding(
      key: const ValueKey('agent-run-settings-overview'),
      padding: EdgeInsets.zero,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          _buildOverviewRow(
            key: const ValueKey('chat-input-agent-run-settings-group-model'),
            icon: LucideIcons.sparkles,
            label: widget.modelHeader,
            value: widget.currentModelId,
            onTap: () => _showPage(_AgentRunSettingsMenuPage.models),
          ),
          _buildOverviewRow(
            key: const ValueKey(
              'chat-input-agent-run-settings-group-reasoning',
            ),
            icon: LucideIcons.brain,
            label: widget.reasoningHeader,
            value: widget.effortLabelBuilder(widget.currentReasoningEffort),
            onTap: () => _showPage(_AgentRunSettingsMenuPage.reasoning),
          ),
        ],
      ),
    );
  }

  Widget _buildSubmenuHeader(String title) {
    return OmniMenuItem(
      key: const ValueKey('chat-input-agent-run-settings-back'),
      onTap: () => _showPage(_AgentRunSettingsMenuPage.overview),
      leading: const Icon(Icons.chevron_left_rounded),
      label: title,
    );
  }

  Widget _buildSearch() {
    final palette = context.omniPalette;
    return Padding(
      key: const ValueKey('chat-input-agent-run-settings-model-search'),
      padding: const EdgeInsets.fromLTRB(10, 5, 10, 6),
      child: Container(
        height: 36,
        padding: const EdgeInsets.symmetric(horizontal: 10),
        decoration: BoxDecoration(
          color: palette.surfaceSecondary,
          borderRadius: BorderRadius.circular(11),
          border: Border.all(color: palette.borderSubtle),
        ),
        child: Row(
          children: [
            Icon(Icons.search_rounded, size: 17, color: palette.textTertiary),
            const SizedBox(width: 7),
            Expanded(
              child: TextField(
                controller: _searchController,
                autofocus: false,
                scrollPadding: EdgeInsets.zero,
                cursorColor: widget.selectedColor,
                style: TextStyle(
                  fontSize: 12,
                  color: widget.textColor,
                  fontWeight: FontWeight.w500,
                ),
                decoration: InputDecoration(
                  isDense: true,
                  hintText: widget.searchHint,
                  hintStyle: TextStyle(
                    fontSize: 12,
                    color: palette.textTertiary,
                    fontWeight: FontWeight.w500,
                  ),
                  border: InputBorder.none,
                  focusedBorder: InputBorder.none,
                  enabledBorder: InputBorder.none,
                  contentPadding: EdgeInsets.zero,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildChoiceRow({
    required String keySuffix,
    required String value,
    required String label,
    required bool selected,
    required VoidCallback onTap,
    bool showVendorIcon = false,
  }) {
    final row = OmniMenuItem(
      key: ValueKey('chat-input-agent-run-settings-option-$keySuffix'),
      onTap: onTap,
      label: label,
      selected: selected,
      leading: showVendorIcon
          ? ProviderVendorIcon(
              vendor: ModelVendorCatalog.resolve(value),
              size: OmniMenuStyle.iconSize,
            )
          : null,
      trailing: SizedBox(
        width: OmniMenuStyle.iconSize,
        child: selected
            ? Icon(Icons.check_rounded, color: widget.selectedColor)
            : null,
      ),
    );
    if (!showVendorIcon) return row;
    return Tooltip(
      message: value,
      triggerMode: TooltipTriggerMode.longPress,
      waitDuration: Duration.zero,
      preferBelow: false,
      child: row,
    );
  }

  Widget _buildModelList() {
    final models = _filteredModels;
    final showBack = widget.reasoningOptions.isNotEmpty;
    final showSearch = widget.modelOptions.length > _searchThreshold;
    return Column(
      key: const ValueKey('agent-run-settings-models'),
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (showBack) _buildSubmenuHeader(widget.modelHeader),
        if (showSearch) _buildSearch(),
        if (widget.modelOptions.isEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 18),
            child: Text(
              widget.emptyModelsLabel,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 12,
                color: context.isDarkTheme
                    ? context.omniPalette.textTertiary
                    : const Color(0xFF929EB0),
                fontWeight: FontWeight.w500,
              ),
            ),
          )
        else if (models.isEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 18),
            child: Text(
              widget.noMatchesLabel,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 12,
                color: context.isDarkTheme
                    ? context.omniPalette.textTertiary
                    : const Color(0xFF929EB0),
                fontWeight: FontWeight.w500,
              ),
            ),
          )
        else
          Flexible(
            child: ListView.builder(
              shrinkWrap: true,
              padding: EdgeInsets.zero,
              itemCount: models.length,
              itemBuilder: (context, index) {
                final model = models[index];
                return _buildChoiceRow(
                  keySuffix: 'model-$model',
                  value: model,
                  label: model,
                  selected: model == widget.currentModelId,
                  showVendorIcon: true,
                  onTap: () => widget.onSelectModel(model),
                );
              },
            ),
          ),
      ],
    );
  }

  Widget _buildReasoningList() {
    return Column(
      key: const ValueKey('agent-run-settings-reasoning'),
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        _buildSubmenuHeader(widget.reasoningHeader),
        Flexible(
          child: ListView.builder(
            shrinkWrap: true,
            padding: EdgeInsets.zero,
            itemCount: widget.reasoningOptions.length,
            itemBuilder: (context, index) {
              final effort = widget.reasoningOptions[index];
              return _buildChoiceRow(
                keySuffix: 'effort-$effort',
                value: effort,
                label: widget.effortLabelBuilder(effort),
                selected: effort == widget.currentReasoningEffort,
                onTap: () => widget.onSelectReasoning(effort),
              );
            },
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final mediaQuery = MediaQuery.of(context);
    final dynamicMaxHeight =
        (mediaQuery.size.height - mediaQuery.viewInsets.bottom - 96)
            .clamp(180.0, widget.maxHeight)
            .toDouble();
    final body = switch (_page) {
      _AgentRunSettingsMenuPage.overview => _buildOverview(),
      _AgentRunSettingsMenuPage.models => _buildModelList(),
      _AgentRunSettingsMenuPage.reasoning => _buildReasoningList(),
    };
    return SizedBox(
      key: const ValueKey('chat-input-agent-run-settings-menu'),
      width: widget.width,
      child: OmniMenuSurface(
        width: widget.width,
        child: Material(
          color: Colors.transparent,
          child: ConstrainedBox(
            constraints: BoxConstraints(maxHeight: dynamicMaxHeight),
            child: AnimatedSwitcher(
              duration: _pageAnimationDuration,
              switchInCurve: Curves.easeOutCubic,
              switchOutCurve: Curves.easeInCubic,
              child: body,
            ),
          ),
        ),
      ),
    );
  }
}
