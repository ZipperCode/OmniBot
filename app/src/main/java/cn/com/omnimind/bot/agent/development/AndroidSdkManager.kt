package cn.com.omnimind.bot.agent.development

import android.content.Context
import cn.com.omnimind.bot.termux.TermuxCommandRunner
import cn.com.omnimind.bot.termux.TermuxCommandSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

object AndroidSdkManager {

    const val GOOGLE_REPOSITORY_BASE_URL = "https://dl.google.com/android/repository/"
    const val REPOSITORY_XML = "repository2-3.xml"
    val SUPPORTED_API_RANGE = 26..36
    const val DEFAULT_API = 35
    private const val MIN_JAR_BYTES = 1_000_000L

    const val DEFAULT_BUILD_TOOLS_VERSION = "35.0.1"
    const val DEFAULT_BUILD_TOOLS_ARCHIVE = "build-tools_r35.0.1_linux.zip"
    const val DEFAULT_BUILD_TOOLS_SHA1 = "e009a9b188cfeb1d2b4c318ab5cb4f1ddc368861"
    const val DEFAULT_BUILD_TOOLS_SIZE = 61959297L

    const val APKSIGNER_JAR_SHA1 = "a183c68d76048c587fee78eb7f84e17178ec2198"
    const val APKSIGNER_JAR_SHA256 = "00ef9948f843fe395d2440ae3ef41405b8040a6d5d46493bd1902ac0ee6deae7"
    const val APKSIGNER_JAR_BYTES = 1074241L

    private val safeZipNamePattern = Regex("^[A-Za-z0-9._-]+\\.zip$")

    data class SdkArchive(
        val file: String,
        val bytes: Long,
        val sha1: String
    )

    data class BuildToolsArchive(
        val version: String,
        val file: String,
        val bytes: Long,
        val sha1: String
    )

    data class ToolchainStatus(
        val architecture: String,
        val jdkInstalled: Boolean,
        val jdkVersion: String?,
        val aapt2Available: Boolean,
        val d8Available: Boolean,
        val apksignerAvailable: Boolean,
        val keytoolAvailable: Boolean,
        val installedPlatforms: List<Int>,
        val isReadyForBuild: Boolean,
        val details: Map<String, String> = emptyMap(),
        val zipalignAvailable: Boolean = false
    )

    fun apkSignerJarPath(sdkRoot: File): File =
        File(sdkRoot, "share/java/apksigner.jar")

    fun platformJarPath(sdkRoot: File, api: Int): File =
        File(sdkRoot, "platforms/android-$api/android.jar")

    /**
     * 解析 Google 官方 repository2-3.xml 提取对应平台的归档元数据，严格防路径穿越。
     */
    fun parsePlatformArchive(xmlListing: String, api: Int): SdkArchive? {
        val packageBlockPattern = Regex(
            "<remotePackage\\s+path=\"platforms;android-$api\">(.*?)</remotePackage>",
            RegexOption.DOT_MATCHES_ALL
        )
        val block = packageBlockPattern.find(xmlListing)?.groupValues?.get(1) ?: return null

        val completePattern = Regex("<complete>(.*?)</complete>", RegexOption.DOT_MATCHES_ALL)
        val completeBlock = completePattern.find(block)?.groupValues?.get(1) ?: return null

        val size = Regex("<size>(\\d+)</size>").find(completeBlock)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        val sha1 = Regex("<checksum\\s+type=\"sha1\">([0-9a-fA-F]{40})</checksum>")
            .find(completeBlock)?.groupValues?.get(1)?.lowercase() ?: return null
        val file = Regex("<url>([^<]+)</url>").find(completeBlock)?.groupValues?.get(1)?.trim() ?: return null

        // 安全约束：杜绝任何重定向、路径穿越与非 zip 资源
        if (!safeZipNamePattern.matches(file) || size <= 0) return null

        return SdkArchive(file = file, bytes = size, sha1 = sha1)
    }

    /**
     * 解析 Google 官方 repository2-3.xml 提取对应版本 build-tools 的 Linux 归档元数据，严格防路径穿越。
     */
    fun parseBuildToolsArchive(
        xmlListing: String,
        version: String = DEFAULT_BUILD_TOOLS_VERSION,
        targetOs: String = "linux"
    ): BuildToolsArchive? {
        val packageBlockPattern = Regex(
            "<remotePackage\\s+path=\"build-tools;${Regex.escape(version)}\">(.*?)</remotePackage>",
            RegexOption.DOT_MATCHES_ALL
        )
        val block = packageBlockPattern.find(xmlListing)?.groupValues?.get(1) ?: return null

        val archivePattern = Regex("<archive>(.*?)</archive>", RegexOption.DOT_MATCHES_ALL)
        for (archiveMatch in archivePattern.findAll(block)) {
            val archiveContent = archiveMatch.groupValues[1]
            val hostOs = Regex("<host-os>([^<]+)</host-os>").find(archiveContent)?.groupValues?.get(1)?.trim()
            if (hostOs != null && hostOs != targetOs) {
                continue
            }
            val completePattern = Regex("<complete>(.*?)</complete>", RegexOption.DOT_MATCHES_ALL)
            val completeBlock = completePattern.find(archiveContent)?.groupValues?.get(1) ?: continue

            val size = Regex("<size>(\\d+)</size>").find(completeBlock)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val sha1 = Regex("<checksum\\s+type=\"sha1\">([0-9a-fA-F]{40})</checksum>")
                .find(completeBlock)?.groupValues?.get(1)?.lowercase() ?: continue
            val file = Regex("<url>([^<]+)</url>").find(completeBlock)?.groupValues?.get(1)?.trim() ?: continue

            // 安全约束：杜绝任何重定向、路径穿越与非 zip 资源
            if (!safeZipNamePattern.matches(file) || size <= 0) continue

            return BuildToolsArchive(version = version, file = file, bytes = size, sha1 = sha1)
        }
        return null
    }

    suspend fun inspectToolchain(context: Context): ToolchainStatus = withContext(Dispatchers.IO) {
        val checkScript = """
            uname -m 2>/dev/null || echo "unknown"
            echo "---MARKER---"
            javac -version 2>&1 || echo "NOT_FOUND"
            echo "---MARKER---"
            aapt2 version 2>&1 || echo "NOT_FOUND"
            echo "---MARKER---"
            command -v d8 >/dev/null 2>&1 && (d8 --version 2>&1 | head -n 1) || echo "NOT_FOUND"
            echo "---MARKER---"
            command -v apksigner >/dev/null 2>&1 && (apksigner --version 2>&1 || apksigner --help 2>&1 | head -n 1) || echo "NOT_FOUND"
            echo "---MARKER---"
            command -v keytool >/dev/null 2>&1 && echo "AVAILABLE" || echo "NOT_FOUND"
            echo "---MARKER---"
            command -v zipalign >/dev/null 2>&1 && (zipalign 2>&1 | head -n 1) || echo "NOT_FOUND"
            echo "---MARKER---"
            ls -d ${'$'}HOME/android-sdk/platforms/android-*/android.jar 2>/dev/null || true
        """.trimIndent()

        val execResult = runCatching {
            TermuxCommandRunner.execute(
                context = context,
                spec = TermuxCommandSpec(
                    command = checkScript,
                    timeoutSeconds = 30
                )
            )
        }.getOrNull()

        val parts = execResult?.stdout?.split("---MARKER---")?.map { it.trim() } ?: emptyList()
        val arch = parts.getOrNull(0)?.lines()?.firstOrNull() ?: "unknown"
        val javacOut = parts.getOrNull(1) ?: "NOT_FOUND"
        val aapt2Out = parts.getOrNull(2) ?: "NOT_FOUND"
        val d8Out = parts.getOrNull(3) ?: "NOT_FOUND"
        val apksignerOut = parts.getOrNull(4) ?: "NOT_FOUND"
        val keytoolOut = parts.getOrNull(5) ?: "NOT_FOUND"
        val zipalignOut = parts.getOrNull(6) ?: "NOT_FOUND"
        val platformJars = parts.getOrNull(7)?.lines()?.filter { it.isNotBlank() } ?: emptyList()

        val platforms = platformJars.mapNotNull { line ->
            Regex("android-(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull()
        }.distinct().sorted()

        val jdkOk = !javacOut.contains("NOT_FOUND") && !javacOut.contains("command not found")
        val aapt2Ok = !aapt2Out.contains("NOT_FOUND") && !aapt2Out.contains("command not found")
        val d8Ok = !d8Out.contains("NOT_FOUND") && !d8Out.contains("command not found") &&
            (d8Out == "AVAILABLE" || d8Out.contains("R8") || d8Out.contains("D8"))
        val apksignerOk = !apksignerOut.contains("NOT_FOUND") &&
            !apksignerOut.contains("command not found") &&
            !apksignerOut.contains("no main manifest attribute") &&
            !apksignerOut.contains("ClassNotFoundException") &&
            (apksignerOut == "AVAILABLE" || apksignerOut.contains("0.") || apksignerOut.contains("apksigner") || apksignerOut.contains("Usage"))
        val keytoolOk = keytoolOut == "AVAILABLE" || (!keytoolOut.contains("NOT_FOUND") && !keytoolOut.contains("command not found"))
        val zipalignOk = !zipalignOut.contains("NOT_FOUND") &&
            !zipalignOut.contains("command not found") &&
            (zipalignOut.contains("Zip alignment") || zipalignOut.contains("zipalign") || zipalignOut.contains("Usage"))

        val ready = jdkOk && aapt2Ok && d8Ok && apksignerOk && keytoolOk && zipalignOk && platforms.isNotEmpty()

        ToolchainStatus(
            architecture = arch,
            jdkInstalled = jdkOk,
            jdkVersion = if (jdkOk) javacOut else null,
            aapt2Available = aapt2Ok,
            d8Available = d8Ok,
            apksignerAvailable = apksignerOk,
            keytoolAvailable = keytoolOk,
            installedPlatforms = platforms,
            isReadyForBuild = ready,
            details = mapOf(
                "javac" to javacOut,
                "aapt2" to aapt2Out,
                "d8" to d8Out,
                "apksigner" to apksignerOut,
                "zipalign" to zipalignOut,
                "platformsFound" to platforms.joinToString(", ")
            ),
            zipalignAvailable = zipalignOk
        )
    }

    suspend fun downloadAndExtractAndroidJar(
        destSdkRoot: File,
        tempWorkDir: File,
        platformApi: Int = DEFAULT_API,
        customListingXml: String? = null,
        client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build(),
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        require(platformApi in SUPPORTED_API_RANGE) { "Unsupported Android platform API: $platformApi" }
        val targetJar = platformJarPath(destSdkRoot, platformApi)
        if (targetJar.isFile && targetJar.length() > MIN_JAR_BYTES &&
            AndroidBuildToolchain.validateJarClasses(targetJar, listOf("android/app/Activity.class"))) {
            return@withContext targetJar
        }
        val xmlContent = if (customListingXml != null) {
            customListingXml
        } else {
            val req = Request.Builder().url(GOOGLE_REPOSITORY_BASE_URL + REPOSITORY_XML).build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "Failed to fetch Google SDK listing: HTTP ${resp.code}" }
                checkNotNull(resp.body) { "Empty SDK listing body" }.string()
            }
        }

        val archive = parsePlatformArchive(xmlContent, platformApi)
            ?: throw IllegalStateException("Google repository listing has no platform archive for android-$platformApi")

        tempWorkDir.mkdirs()
        val tempZip = File(tempWorkDir, archive.file)
        try {
            val downloadUrl = GOOGLE_REPOSITORY_BASE_URL + archive.file
            val req = Request.Builder().url(downloadUrl).build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "Failed to download ${archive.file}: HTTP ${resp.code}" }
                val body = checkNotNull(resp.body) { "Empty response body for ${archive.file}" }
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: archive.bytes
                val digest = MessageDigest.getInstance("SHA-1")

                tempZip.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            check(read <= archive.bytes * 2) { "Downloaded archive exceeded declared size limit" }
                            onProgress(read, totalBytes)
                        }
                    }
                }

                val actualSha1 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha1.equals(archive.sha1, ignoreCase = true)) {
                    tempZip.delete()
                    throw IllegalStateException("SHA-1 checksum mismatch for ${archive.file}: expected ${archive.sha1}, actual $actualSha1")
                }
            }

            // 原子解压出 android.jar
            targetJar.parentFile?.mkdirs()
            val tempPart = File(targetJar.parentFile, "android.jar.part")
            var extracted = false

            ZipInputStream(tempZip.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (!entry.isDirectory && (name.endsWith("/android.jar") || name == "android.jar")) {
                        tempPart.outputStream().use { out ->
                            zip.copyTo(out)
                        }
                        extracted = true
                        break
                    }
                }
            }

            check(extracted && tempPart.length() >= MIN_JAR_BYTES) {
                tempPart.delete()
                "Failed to extract android.jar from platform archive"
            }

            if (!tempPart.renameTo(targetJar)) {
                tempPart.copyTo(targetJar, overwrite = true)
                tempPart.delete()
            }
        } finally {
            if (tempZip.exists()) {
                tempZip.delete()
            }
        }

        return@withContext targetJar
    }
    suspend fun downloadOfficialXposedApiJar(
        destLibsDir: File,
        client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    ): File = withContext(Dispatchers.IO) {
        destLibsDir.mkdirs()
        val targetJar = File(destLibsDir, "api-82.jar")
        val expectedSha1 = "35866b507b360d4789ff389ad7386b6e8bbf6cc4"
        val expectedSha256 = "f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25"

        if (targetJar.isFile && computeSha1(targetJar).equals(expectedSha1, ignoreCase = true) &&
            AndroidBuildToolchain.validateJarClasses(targetJar, listOf("de/robv/android/xposed/XposedHelpers.class", "de/robv/android/xposed/IXposedHookLoadPackage.class"))) {
            return@withContext targetJar
        }

        val url = "https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar"
        val temp = File(destLibsDir, "api-82.jar.part")
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Failed to download official Xposed API-82 from $url: HTTP ${resp.code}" }
            val body = checkNotNull(resp.body) { "Empty API-82 jar response" }
            val sha1Digest = MessageDigest.getInstance("SHA-1")
            val sha256Digest = MessageDigest.getInstance("SHA-256")
            temp.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha1Digest.update(buf, 0, n)
                        sha256Digest.update(buf, 0, n)
                    }
                }
            }
            val actualSha1 = sha1Digest.digest().joinToString("") { "%02x".format(it) }
            val actualSha256 = sha256Digest.digest().joinToString("") { "%02x".format(it) }
            check(actualSha1.equals(expectedSha1, ignoreCase = true) || actualSha256.equals(expectedSha256, ignoreCase = true)) {
                temp.delete()
                "SHA checksum mismatch for api-82.jar: expected sha1 $expectedSha1, actual $actualSha1"
            }
            check(AndroidBuildToolchain.validateJarClasses(temp, listOf("de/robv/android/xposed/XposedHelpers.class", "de/robv/android/xposed/IXposedHookLoadPackage.class"))) {
                temp.delete()
                "Downloaded api-82.jar did not contain required Xposed classes."
            }
        }
        if (!temp.renameTo(targetJar)) {
            temp.copyTo(targetJar, overwrite = true)
            temp.delete()
        }
        return@withContext targetJar
    }

    /**
     * 下载并解压 Google 官方 Android SDK Build-Tools 中的完整 ApkSignerTool CLI JAR（包含 apksig 引擎）。
     * 验证归档 SHA-1、解压后 JAR 大小以及 ApkSignerTool 与 ApkSigner 字节码类完整性。
     */
    suspend fun downloadAndExtractApkSignerJar(
        destSdkRoot: File,
        tempWorkDir: File,
        buildToolsVersion: String = DEFAULT_BUILD_TOOLS_VERSION,
        customListingXml: String? = null,
        client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build(),
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val targetJar = apkSignerJarPath(destSdkRoot)
        if (targetJar.isFile && targetJar.length() > MIN_JAR_BYTES &&
            AndroidBuildToolchain.validateApkSignerJar(targetJar) &&
            AndroidBuildToolchain.validateJarClasses(targetJar, listOf("com/android/apksigner/ApkSignerTool.class", "com/android/apksig/ApkSigner.class"))) {
            return@withContext targetJar
        }

        val xmlContent = if (customListingXml != null) {
            customListingXml
        } else {
            val req = Request.Builder().url(GOOGLE_REPOSITORY_BASE_URL + REPOSITORY_XML).build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "Failed to fetch Google SDK listing: HTTP ${resp.code}" }
                checkNotNull(resp.body) { "Empty SDK listing body" }.string()
            }
        }

        val archive = parseBuildToolsArchive(xmlContent, buildToolsVersion)
            ?: throw IllegalStateException("Google repository listing has no build-tools archive for version $buildToolsVersion")

        tempWorkDir.mkdirs()
        val tempZip = File(tempWorkDir, archive.file)
        try {
            val downloadUrl = GOOGLE_REPOSITORY_BASE_URL + archive.file
            val req = Request.Builder().url(downloadUrl).build()
            client.newCall(req).execute().use { resp ->
                check(resp.isSuccessful) { "Failed to download ${archive.file}: HTTP ${resp.code}" }
                val body = checkNotNull(resp.body) { "Empty response body for ${archive.file}" }
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: archive.bytes
                val digest = MessageDigest.getInstance("SHA-1")

                tempZip.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            check(read <= archive.bytes * 2) { "Downloaded archive exceeded declared size limit" }
                            onProgress(read, totalBytes)
                        }
                    }
                }

                val actualSha1 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha1.equals(archive.sha1, ignoreCase = true)) {
                    tempZip.delete()
                    throw IllegalStateException("SHA-1 checksum mismatch for ${archive.file}: expected ${archive.sha1}, actual $actualSha1")
                }
            }

            // 原子解压出 build-tools 中的 lib/apksigner.jar
            targetJar.parentFile?.mkdirs()
            val tempPart = File(targetJar.parentFile, "apksigner.jar.part")
            var extracted = false

            ZipInputStream(tempZip.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (!entry.isDirectory && (name.endsWith("/lib/apksigner.jar") || name == "lib/apksigner.jar")) {
                        tempPart.outputStream().use { out ->
                            zip.copyTo(out)
                        }
                        extracted = true
                        break
                    }
                }
            }

            check(extracted && tempPart.length() >= MIN_JAR_BYTES) {
                tempPart.delete()
                "Failed to extract lib/apksigner.jar from build-tools archive ${archive.file}"
            }

            check(AndroidBuildToolchain.validateApkSignerJar(tempPart)) {
                tempPart.delete()
                "Extracted apksigner.jar is missing ApkSignerTool.class"
            }

            check(AndroidBuildToolchain.validateJarClasses(tempPart, listOf("com/android/apksigner/ApkSignerTool.class", "com/android/apksig/ApkSigner.class"))) {
                tempPart.delete()
                "Extracted apksigner.jar did not pass bytecode integrity check for core apksigner/apksig classes"
            }

            if (!tempPart.renameTo(targetJar)) {
                tempPart.copyTo(targetJar, overwrite = true)
                tempPart.delete()
            }
        } finally {
            if (tempZip.exists()) {
                tempZip.delete()
            }
        }

        return@withContext targetJar
    }

    private fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun computeSha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun installToolchainInAlpine(
        context: Context,
        platformApi: Int = DEFAULT_API,
        withGradle: Boolean = false,
        onOutput: (String) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        val extraPackages = if (withGradle) " gradle" else ""
        val bootstrapScript = """
            set -e
            echo "==> Updating Alpine package index..."
            apk update
            echo "==> Installing build dependencies (openjdk21-jdk, gcc, musl-dev, git, curl, bash, zip, unzip, findutils)..."
            apk add --no-cache openjdk21-jdk gcc musl-dev git curl bash zip unzip findutils$extraPackages
            echo "==> Package installation finished."
        """.trimIndent()

        val pkgResult = TermuxCommandRunner.execute(
            context = context,
            spec = TermuxCommandSpec(
                command = bootstrapScript,
                timeoutSeconds = 300
            )
        )
        onOutput(pkgResult.stdout + "\n" + pkgResult.stderr)
        if (!pkgResult.success) {
            return@withContext false
        }

        // 1. 使用统一的 Kotlin 校验下载器下载并解压官方平台 android.jar（强 SHA-1 校验与大小校验）
        val localSdkDir = File(context.filesDir, "android-sdk")
        val localTempDir = File(context.cacheDir, "android_sdk_download")
        val verifiedJar = runCatching {
            downloadAndExtractAndroidJar(
                destSdkRoot = localSdkDir,
                tempWorkDir = localTempDir,
                platformApi = platformApi
            )
        }.getOrElse { e ->
            onOutput("Failed to download and verify official android.jar: ${e.message}")
            return@withContext false
        }
        onOutput("Verified official android.jar (${verifiedJar.length()} bytes, SHA-1 passed).")

        // 2. 部署官方 Xposed API-82 compileOnly 依赖（强校验，绝不吞失败）
        val terminalLibsDir = File(localSdkDir, "libs")
        val xposedJarResult = runCatching {
            downloadOfficialXposedApiJar(terminalLibsDir)
        }
        if (xposedJarResult.isFailure) {
            onOutput("Failed to acquire official Xposed API-82: ${xposedJarResult.exceptionOrNull()?.message}")
            return@withContext false
        }
        onOutput("Verified official compileOnly Xposed API-82 jar acquired.")

        // 3. 使用统一的 Kotlin 下载校验器获取官方真实 ApkSignerTool CLI JAR（强 SHA 与类校验，绝不使用 apksig 库冒充）
        val verifiedApkSignerJar = runCatching {
            downloadAndExtractApkSignerJar(
                destSdkRoot = localSdkDir,
                tempWorkDir = localTempDir,
                buildToolsVersion = DEFAULT_BUILD_TOOLS_VERSION
            )
        }.getOrElse { e ->
            onOutput("Failed to download and verify official apksigner CLI: ${e.message}")
            return@withContext false
        }
        onOutput("Verified official apksigner.jar (${verifiedApkSignerJar.length()} bytes, SHA-1 and classes passed).")
        // 提取 Java 子线程栈隔离补丁源码资产（必须显式存在，绝不静默跳过）
        val jvmCompatSource = checkNotNull(
            runCatching {
                context.assets.open("phone-development/jvm-compat.c").bufferedReader().use { it.readText() }
            }.getOrNull()
        ) { "缺少 phone-development/jvm-compat.c 资产，无法部署 JVM 隔离补丁" }
        val tempJvmCompatFile = File(localTempDir, "jvm-compat.c").also { file ->
            file.parentFile?.mkdirs()
            file.writeText(jvmCompatSource)
        }

        // 4. 将校验后的工具与依赖部署至终端环境，并使用隔离 --root 目录安装原生 musl 工具与配置官方启动器
        val deployScript = """
            set -e
            TARGET_DIR="${'$'}HOME/android-sdk/platforms/android-$platformApi"
            mkdir -p "${'$'}TARGET_DIR"
            cp '${verifiedJar.absolutePath}' "${'$'}TARGET_DIR/android.jar"
            chmod 644 "${'$'}TARGET_DIR/android.jar"
            echo "==> Verified android.jar deployed to ${'$'}TARGET_DIR/android.jar"

            # 部署官方编译期 compileOnly Xposed api-82.jar 依赖到终端环境
            LIBS_DIR="${'$'}HOME/android-sdk/libs"
            mkdir -p "${'$'}LIBS_DIR"
            cp '${terminalLibsDir.absolutePath}/api-82.jar' "${'$'}LIBS_DIR/api-82.jar"
            chmod 644 "${'$'}LIBS_DIR/api-82.jar"
            echo "==> Verified Xposed api-82.jar deployed to ${'$'}LIBS_DIR/api-82.jar"

            # 在隔离 toolroot 安装原生 aarch64 musl android-build-tools (aapt2, zipalign)，绝不升级现有宿主根
            TOOL_ROOT="${'$'}HOME/toolchain/android-build-tools"
            if ! command -v aapt2 >/dev/null 2>&1 || ! command -v zipalign >/dev/null 2>&1; then
                echo "==> 正在隔离 toolroot 中安装原生 aarch64 musl android-build-tools: ${'$'}TOOL_ROOT..."
                mkdir -p "${'$'}TOOL_ROOT"

                # 初始化 isolated toolroot 信任公钥链：apk --root 默认在目标根下查找 /etc/apk/keys，
                # 将宿主已受信任的公钥复制到 isolated root，保证验证 edge APKINDEX 官方签名。
                echo "==> 初始化隔离 toolroot 的信任公钥链..."
                mkdir -p "${'$'}TOOL_ROOT/etc/apk/keys"
                if [ -d /etc/apk/keys ]; then
                    cp -a /etc/apk/keys/. "${'$'}TOOL_ROOT/etc/apk/keys/"
                fi
                if [ -d /usr/share/apk/keys ]; then
                    mkdir -p "${'$'}TOOL_ROOT/usr/share/apk/keys"
                    cp -a /usr/share/apk/keys/. "${'$'}TOOL_ROOT/usr/share/apk/keys/"
                fi

                echo "==> 安装 android-build-tools 到隔离 toolroot..."
                apk add --root "${'$'}TOOL_ROOT" --initdb --no-cache \
                    --repository=https://dl-cdn.alpinelinux.org/alpine/edge/testing \
                    --repository=https://dl-cdn.alpinelinux.org/alpine/edge/main \
                    android-build-tools
                mkdir -p /usr/bin
                printf '#!/bin/sh\nTOOL_ROOT="%s"\nexport LD_LIBRARY_PATH="${'$'}TOOL_ROOT/usr/lib:${'$'}TOOL_ROOT/lib:${'$'}LD_LIBRARY_PATH"\nexec "${'$'}TOOL_ROOT/usr/bin/aapt2" "$@"\n' "${'$'}TOOL_ROOT" > /usr/bin/aapt2
                chmod +x /usr/bin/aapt2

                printf '#!/bin/sh\nTOOL_ROOT="%s"\nexport LD_LIBRARY_PATH="${'$'}TOOL_ROOT/usr/lib:${'$'}TOOL_ROOT/lib:${'$'}LD_LIBRARY_PATH"\nexec "${'$'}TOOL_ROOT/usr/bin/zipalign" "$@"\n' "${'$'}TOOL_ROOT" > /usr/bin/zipalign
                chmod +x /usr/bin/zipalign
            fi
            # 编译并部署 Java 子线程栈隔离补丁（解决 Alpine musl OpenJDK check_pax 与线程栈合并触发 execstack 拒绝）
            mkdir -p /usr/local/lib /usr/local/src /usr/bin
            cp '${tempJvmCompatFile.absolutePath}' /usr/local/src/jvm-compat.c
            gcc -shared -fPIC -O2 /usr/local/src/jvm-compat.c -o /usr/local/lib/libjvm-compat.so -ldl
            chmod 755 /usr/local/lib/libjvm-compat.so
            echo "==> 已成功编译并部署 /usr/local/lib/libjvm-compat.so"

            # 配置 java, javac, jar, keytool 包装器（原子覆盖原软链接，绝不直接写穿目标二进制）
            for tool in java javac jar keytool; do
                if [ -e "/usr/lib/jvm/default-jvm/bin/${'$'}tool" ]; then
                    WRAPPER_TMP="/usr/bin/${'$'}{tool}.tmp.${'$'}${'$'}"
                    printf '#!/bin/sh\nCOMPAT_LIB=/usr/local/lib/libjvm-compat.so\nif [ -f "${'$'}COMPAT_LIB" ]; then\n  export OMNI_JVM_ORIGINAL_PRELOAD="${'$'}{LD_PRELOAD:-}"\n  LD_PRELOAD="${'$'}COMPAT_LIB${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}" exec "/usr/lib/jvm/default-jvm/bin/%s" "${'$'}@"\nelse\n  exec "/usr/lib/jvm/default-jvm/bin/%s" "${'$'}@"\nfi\n' "${'$'}tool" "${'$'}tool" > "${'$'}WRAPPER_TMP"
                    chmod 755 "${'$'}WRAPPER_TMP"
                    mv -f "${'$'}WRAPPER_TMP" "/usr/bin/${'$'}tool"
                fi
            done
            echo "==> 已部署 Java 工具链专用包装器。"

            # 配置官方纯 Java d8 字节码编译器与启动脚本
            mkdir -p /usr/share/java /usr/bin
            if ! command -v d8 >/dev/null 2>&1 || [ ! -f /usr/share/java/d8.jar ]; then
                echo "==> Configuring official d8 compiler (R8 v8.2.42)..."
                curl -sSfL "https://storage.googleapis.com/r8-releases/raw/8.2.42/r8.jar" -o /usr/share/java/d8.jar
                printf '#!/bin/sh\nexec java -cp /usr/share/java/d8.jar com.android.tools.r8.D8 "$@"\n' > /usr/bin/d8
                chmod +x /usr/bin/d8
            fi

            # 配置官方纯 Java ApkSignerTool CLI 与启动脚本（真实固定版本 CLI，复用官方 build-tools，不冒充，无 conscrypt 吞错）
            echo "==> Deploying official verified ApkSignerTool CLI..."
            cp '${verifiedApkSignerJar.absolutePath}' /usr/share/java/apksigner.jar
            chmod 644 /usr/share/java/apksigner.jar
            printf '#!/bin/sh\nexec java -jar /usr/share/java/apksigner.jar "$@"\n' > /usr/bin/apksigner
            chmod +x /usr/bin/apksigner

            # 执行真实工具版本校验，确保每一项都可运行
            echo "==> Verifying toolchain executability..."
            javac -version
            aapt2 version
            zipalign 2>&1 | head -n 2
            d8 --version
            apksigner --version || apksigner --help
            echo "==> Toolchain verification passed."
        """.trimIndent()

        val deployResult = TermuxCommandRunner.execute(
            context = context,
            spec = TermuxCommandSpec(
                command = deployScript,
                timeoutSeconds = 300
            )
        )
        onOutput(deployResult.stdout + "\n" + deployResult.stderr)
        deployResult.success
    }
}
