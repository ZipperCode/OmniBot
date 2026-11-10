import 'package:go_router/go_router.dart';
import '../../features/home/router_config.dart';
import '../../features/home/pages/settings/virtual_screen_setting_page.dart';
import '../../features/task/router_config.dart';
import '../../features/welcome/router_config.dart';
import '../../features/memory/router_config.dart';
import '../../features/my/router_config.dart';

class AppRouterConfig {
  static List<GoRoute> getAllRoutes() {
    return [
      ...homeRoutes,
      GoRoute(
        path: '/home/virtual_screen_setting',
        name: 'home/virtual_screen_setting',
        builder: (context, state) => const VirtualScreenSettingPage(),
      ),
      ...taskRoutes,
      ...welcomeRoutes,
      ...memoryRoutes,
      ...myRoutes,
    ];
  }
}
