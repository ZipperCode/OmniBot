---
name: lsposed-develop
description: Xposed / LSPosed 模块开发模式库与最佳实践指南，涵盖方法拦截、构造器 Hook、私有成员读写、Context 获取、多重载匹配及防检测模式。
---

# Xposed / LSPosed 模块开发模式库 (LSPosed Develop)

本手册提供在 Android 平台上基于 XposedBridge API 进行 Hook 开发的核心代码模式与最佳实践。

## 1. 核心接口与入口定义

模块必须实现 `de.robv.android.xposed.IXposedHookLoadPackage` 并在 `assets/xposed_init` 中声明入口全限定类名：

```java
package com.example.hook;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class HookEntry implements IXposedHookLoadPackage {
    private static final String TAG = "OmniBotHook";
    private static final String TARGET_PKG = "com.target.app";

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }
        Log.i(TAG, "Loaded in target process: " + lpparam.packageName);
        initHooks(lpparam.classLoader);
    }
}
```

## 2. 常用 Hook 模式与代码样例

### 模式 A：普通方法拦截 (Before / After)
```java
XposedHelpers.findAndHookMethod(
    "com.target.app.ui.MainActivity",
    classLoader,
    "showDialog",
    String.class,      // 参数 1
    boolean.class,     // 参数 2
    new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
            // 修改入参
            param.args[0] = "Modified Dialog Title";
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            // 读取或替换返回值
            Object result = param.getResult();
            param.setResult(true);
        }
    }
);
```

### 模式 B：完全替换方法执行 (Method Replacement)
```java
XposedHelpers.findAndHookMethod(
    "com.target.app.util.SecurityCheck",
    classLoader,
    "isDeviceRooted",
    new XC_MethodReplacement() {
        @Override
        protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
            return false; // 绕过 Root 检测
        }
    }
);
```

### 模式 C：构造函数拦截 (Constructor Hook)
```java
XposedHelpers.findAndHookConstructor(
    "com.target.app.model.UserProfile",
    classLoader,
    String.class,
    int.class,
    new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            Object userProfileInstance = param.thisObject;
            XposedHelpers.setBooleanField(userProfileInstance, "isVip", true);
        }
    }
);
```

### 模式 D：私有字段与方法访问 (Reflection Helpers)
```java
// 读取私有字段
String token = (String) XposedHelpers.getObjectField(targetObj, "mAuthToken");
int retryCount = XposedHelpers.getIntField(targetObj, "mRetryCount");

// 修改私有字段
XposedHelpers.setObjectField(targetObj, "mAuthToken", "fake_token_value");
XposedHelpers.setBooleanField(targetObj, "mIsProUser", true);

// 调用私有方法
Object res = XposedHelpers.callMethod(targetObj, "internalCalculate", 100, "param");

// 调用私有静态方法
Object staticRes = XposedHelpers.callStaticMethod(targetClass, "generateSignature", "data");
```

### 模式 E：获取 Android Context 环境
在 `handleLoadPackage` 中不要直接寻找 Context，推荐通过 Hook `Application.attachBaseContext` 获取真正的应用上下文：
```java
XposedHelpers.findAndHookMethod(
    "android.app.Application",
    classLoader,
    "attachBaseContext",
    android.content.Context.class,
    new XC_MethodHook() {
        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            android.content.Context context = (android.content.Context) param.args[0];
            Log.i(TAG, "Application Context resolved: " + context.getPackageName());
        }
    }
);
```

## 3. 防检测与高可靠性最佳实践

1. **务必使用 `lpparam.classLoader`**：绝不使用当前模块自己的 `ClassLoader`，否则无法加载目标应用私有类。
2. **谨慎使用通配符匹配**：除非修改 System Server，否则务必精准限定 `lpparam.packageName`，防止无关进程加载产生崩溃。
3. **安全反射调用**：优先使用 `XposedHelpers.findClassIfExists` 判断类是否存在，防止不同 App 版本因类名重构导致闪退。
4. **日志脱敏防风控**：正式模块只输出功能状态标识，严禁打印涉及用户密码、支付数据或真实身份的敏感字符串。
