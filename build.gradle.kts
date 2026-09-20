import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.kotlin.dsl.configure

// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // Make FlutterExtension visible to Kotlin DSL scripts of embedded plugins.
    id("dev.flutter.flutter-gradle-plugin") apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.library) apply false
}

val forcedCoroutinesVersion = libs.versions.kotlinxCoroutines.get()
val androidNdkVersion = providers.gradleProperty("OMNI_NDK_VERSION").getOrElse("29.0.14206865")

subprojects {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
        val storageUrl: String =
            System.getenv("FLUTTER_STORAGE_BASE_URL") ?: "https://storage.googleapis.com"
        maven(url = "$storageUrl/download.flutter.io")
    }

    configurations.configureEach {
        resolutionStrategy.force(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:$forcedCoroutinesVersion",
            "org.jetbrains.kotlinx:kotlinx-coroutines-android:$forcedCoroutinesVersion",
        )
    }

    if (path == ":app") {
        plugins.withId("com.android.application") {
            extensions.configure<ApplicationExtension> {
                ndkVersion = androidNdkVersion
            }
        }
    }

    // 仅修正已核实且获准处理的插件，不为其他模块推断 namespace。
    val pluginNamespace = when (path) {
        ":fluttertoast" -> "io.github.ponnamkarthik.toast.fluttertoast"
        ":gal" -> "studio.midoridesign.gal"
        ":mobile_scanner" -> "dev.steenbakker.mobile_scanner"
        ":jni" -> "com.github.dart_lang.jni"
        ":jni_flutter" -> "com.github.dart_lang.jni_flutter"
        else -> null
    }
    if (pluginNamespace != null) {
        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryAndroidComponentsExtension> {
                finalizeDsl { extension ->
                    extension.namespace = pluginNamespace
                }
            }
        }
    }

    // Flutter's generated module still defaults to SDK 36, while the latest
    // Android dependencies in this host require SDK 37. Keep the generated
    // project out of source control and override it from the host build.
    if (path == ":flutter" || path == ":core:terminal-emulator" || path == ":jni") {
        pluginManager.withPlugin("com.android.library") {
            extensions.configure<LibraryAndroidComponentsExtension> {
                finalizeDsl { extension ->
                    if (path == ":flutter") {
                        extension.compileSdk = 37
                    }
                    // 与宿主共用 NDK 配置，避免生成模块单独下载默认版本。
                    extension.ndkVersion = androidNdkVersion
                }
            }
        }
    }
}
