import 'package:flutter/material.dart';
import 'package:lucide_icons_flutter/lucide_icons.dart';

class PersonalContextLabel {
  const PersonalContextLabel(
    this.id,
    this.icon,
    this.zh,
    this.en,
    this.zhDetail,
    this.enDetail,
  );
  final String id;
  final IconData icon;
  final String zh;
  final String en;
  final String zhDetail;
  final String enDetail;
}

const personalContextLabels = [
  PersonalContextLabel(
    'calendar_read',
    LucideIcons.calendar,
    '读取日历',
    'Read calendars',
    '查询设备上的日历和日程，了解您的安排。',
    'Query calendars and events stored on this device.',
  ),
  PersonalContextLabel(
    'calendar_write',
    LucideIcons.calendar,
    '管理日程',
    'Manage calendar events',
    '根据您的指令创建、修改或删除日程；同时需要读取日历。',
    'Create, update or delete events at your request; also requires calendar read access.',
  ),
  PersonalContextLabel(
    'contacts_read',
    LucideIcons.users,
    '读取通讯录',
    'Read contacts',
    '按需查询联系人、电话、邮箱、组织和地址。',
    'Look up contacts, phone numbers, emails, organizations and addresses.',
  ),
  PersonalContextLabel(
    'contacts_write',
    LucideIcons.users,
    '管理联系人',
    'Manage contacts',
    '根据您的指令创建、修改或删除指定联系人；同时需要读取通讯录。',
    'Create, update or delete a selected contact at your request; also requires contact read access.',
  ),
  PersonalContextLabel(
    'notifications',
    LucideIcons.bell,
    '读取通知与通知历史',
    'Read notifications and history',
    '读取当前通知，并在本地保留授权后观察到的最近 7 天通知，最多 10000 条。与接收小万消息通知的开关独立。',
    'Read current notifications and keep up to 7 days / 10,000 observed notifications locally. Separate from receiving Omnibot notifications.',
  ),
  PersonalContextLabel(
    'app_usage',
    LucideIcons.clock,
    '应用使用情况',
    'App usage',
    '查询近期使用的应用及系统统计的使用时长。',
    'Query recently opened apps and system usage statistics.',
  ),
  PersonalContextLabel(
    'location',
    LucideIcons.mapPin,
    '位置信息',
    'Location',
    'AI 需要时查询一次位置；大致位置也可使用，不持续记录轨迹。',
    'Request one location fix when needed. Approximate location works; no continuous tracking.',
  ),
  PersonalContextLabel(
    'background_location',
    LucideIcons.mapPin,
    '后台查询位置',
    'Location in the background',
    '先开启位置信息，再在系统设置中选择“始终允许”，供后台运行的 AI 按需查询。',
    'First allow location, then choose “Allow all the time” in system settings for on-demand background queries.',
  ),
  PersonalContextLabel(
    'media_visual',
    LucideIcons.image,
    '照片与视频',
    'Photos and videos',
    '查询名称和日期；支持仅授权选定照片。AI 需要内容时才读取文件。',
    'Query names and dates, including selected-only access. File contents are read only when needed.',
  ),
  PersonalContextLabel(
    'media_audio',
    LucideIcons.music,
    '音频文件',
    'Audio files',
    '查找设备上的音频文件名称、类型和日期。',
    'Find audio files by name, type and date.',
  ),
  PersonalContextLabel(
    'documents',
    LucideIcons.folder,
    '选择文件与文件夹',
    'Selected files and folders',
    '使用系统选择器指定可访问的文档或目录，可随时取消本应用持有的授权。',
    'Select documents or folders in the system picker. You can remove the grants here.',
  ),
  PersonalContextLabel(
    'sms',
    LucideIcons.messageSquare,
    '读取短信',
    'Read SMS',
    '按需查询短信，不发送短信；是否可以授权取决于系统和安装来源。',
    'Query SMS without sending messages. Permission availability depends on the system and installer.',
  ),
  PersonalContextLabel(
    'call_log',
    LucideIcons.phone,
    '读取通话记录',
    'Read call history',
    '按需查询号码、时间和通话时长；是否可以授权取决于系统和安装来源。',
    'Query phone numbers, call times and duration. Availability depends on the system and installer.',
  ),
  PersonalContextLabel(
    'environment',
    LucideIcons.smartphone,
    '设备环境',
    'Device environment',
    '按需读取电量、充电、网络类型、屏幕状态和音频输出，无需额外授权。',
    'Read battery, charging, network type, screen state and audio output without extra permissions.',
  ),
];
