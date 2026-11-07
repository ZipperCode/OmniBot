package cn.com.omnimind.bot.agent.development

import cn.com.omnimind.baselib.privileged.PrivilegedCapabilityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * APK 产物与 DEX 符号静态逆向分析服务。
 * 遵循 skill://reverse-engineer-anything 规范，基于真实安装包与多 DEX 字节码提取确凿符号，
 * 绝不凭空编造类名、方法名或组件定义。
 */
object ApkAnalysisService {

    data class ManifestComponent(
        val type: String, // "activity" | "service" | "receiver" | "provider"
        val name: String,
        val exported: Boolean? = null,
        val permission: String? = null
    )

    data class MethodSignature(
        val className: String,
        val methodName: String,
        val returnType: String,
        val parameterTypes: List<String>
    ) {
        val fullSignature: String
            get() = "$returnType $className#$methodName(${parameterTypes.joinToString(", ")})"
    }

    data class CandidateClass(
        val className: String,
        val superclass: String?,
        val methods: List<MethodSignature>
    )

    data class ManifestInfo(
        val packageName: String,
        val versionCode: Long?,
        val versionName: String?,
        val minSdkVersion: Int?,
        val targetSdkVersion: Int?,
        val applicationClass: String?,
        val components: List<ManifestComponent>
    )

    data class ApkAnalysisResult(
        val apkPath: String,
        val fileSizeBytes: Long,
        val sha256: String,
        val md5: String,
        val manifest: ManifestInfo,
        val dexFileCount: Int,
        val totalClassesCount: Int,
        val candidateClasses: List<CandidateClass>,
        val isExtractedFromDevice: Boolean
    )

    suspend fun analyzeApk(
        apkFile: File,
        context: android.content.Context? = null,
        searchKeywords: List<String> = emptyList(),
        maxCandidates: Int = 100,
        isExtractedFromDevice: Boolean = false
    ): ApkAnalysisResult = withContext(Dispatchers.IO) {
        require(apkFile.isFile && apkFile.length() > 0) {
            "APK file not found or empty: ${apkFile.absolutePath}"
        }

        // 1. 计算文件哈希
        val (sha256, md5) = computeFileDigests(apkFile)

        // 2. 解析 AndroidManifest.xml (优先复用 Android 原生 PackageManager)
        val manifest = parseManifestFromApk(apkFile, context)

        // 3. 扫描多 DEX 符号
        val dexResults = scanDexSymbolsFromApk(apkFile, searchKeywords, maxCandidates, manifest.packageName)
        ApkAnalysisResult(
            apkPath = apkFile.absolutePath,
            fileSizeBytes = apkFile.length(),
            sha256 = sha256,
            md5 = md5,
            manifest = manifest,
            dexFileCount = dexResults.dexCount,
            totalClassesCount = dexResults.totalClasses,
            candidateClasses = dexResults.candidates,
            isExtractedFromDevice = isExtractedFromDevice
        )
    }

    private fun computeFileDigests(file: File): Pair<String, String> {
        val sha256Digest = MessageDigest.getInstance("SHA-256")
        val md5Digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                sha256Digest.update(buf, 0, n)
                md5Digest.update(buf, 0, n)
            }
        }
        val sha256 = sha256Digest.digest().joinToString("") { "%02x".format(it) }
        val md5 = md5Digest.digest().joinToString("") { "%02x".format(it) }
        return sha256 to md5
    }

    // ==========================================
    // Android Binary XML (AXML) 基础解析器
    // ==========================================

    fun parseManifestFromApk(apkFile: File, context: android.content.Context? = null): ManifestInfo {
        // 优先复用 Android 原生 PackageManager 解析
        if (context != null) {
            val flags = android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS or
                android.content.pm.PackageManager.GET_PROVIDERS or
                android.content.pm.PackageManager.GET_META_DATA
            val packageInfo = runCatching {
                context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, flags)
            }.getOrNull()

            if (packageInfo != null) {
                val components = mutableListOf<ManifestComponent>()
                packageInfo.activities?.forEach {
                    components.add(ManifestComponent("activity", it.name, it.exported, it.permission))
                }
                packageInfo.services?.forEach {
                    components.add(ManifestComponent("service", it.name, it.exported, it.permission))
                }
                packageInfo.receivers?.forEach {
                    components.add(ManifestComponent("receiver", it.name, it.exported, it.permission))
                }
                packageInfo.providers?.forEach {
                    components.add(ManifestComponent("provider", it.name, it.exported, null))
                }

                @Suppress("DEPRECATION")
                val verCode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode
                } else {
                    packageInfo.versionCode.toLong()
                }

                return ManifestInfo(
                    packageName = packageInfo.packageName ?: "unknown",
                    versionCode = verCode,
                    versionName = packageInfo.versionName,
                    minSdkVersion = packageInfo.applicationInfo?.minSdkVersion,
                    targetSdkVersion = packageInfo.applicationInfo?.targetSdkVersion,
                    applicationClass = packageInfo.applicationInfo?.name,
                    components = components
                )
            }
        }

        var manifestBytes: ByteArray? = null
        ZipFile(apkFile).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
            if (entry != null) {
                manifestBytes = zip.getInputStream(entry).readBytes()
            }
        }

        if (manifestBytes == null || manifestBytes!!.isEmpty()) {
            return ManifestInfo("unknown", null, null, null, null, null, emptyList())
        }

        return parseAxmlManifest(manifestBytes!!)
    }
    fun parseAxmlManifest(data: ByteArray): ManifestInfo {
        var pkg = "unknown"
        var verCode: Long? = null
        var verName: String? = null
        var minSdk: Int? = null
        var targetSdk: Int? = null
        var appClass: String? = null
        val components = mutableListOf<ManifestComponent>()

        runCatching {
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val fileType = bb.short.toInt() and 0xFFFF
            val headerSize = bb.short.toInt() and 0xFFFF
            val fileSize = bb.int

            if (fileType != 0x0003) {
                // Not standard AXML, fallback
                return ManifestInfo(pkg, verCode, verName, minSdk, targetSdk, appClass, components)
            }

            // Read chunks
            val stringPool = mutableListOf<String>()

            while (bb.hasRemaining()) {
                val chunkType = bb.short.toInt() and 0xFFFF
                val chunkHeaderSize = bb.short.toInt() and 0xFFFF
                val chunkSize = bb.int

                if (chunkSize <= 0 || chunkSize > bb.remaining() + 8) break
                val chunkStart = bb.position() - 8

                when (chunkType) {
                    0x0001 -> {
                        // String pool chunk
                        val stringCount = bb.int
                        val styleCount = bb.int
                        val flags = bb.int
                        val stringsStart = bb.int
                        val stylesStart = bb.int

                        val stringOffsets = IntArray(stringCount) { bb.int }

                        for (i in 0 until stringCount) {
                            val off = chunkStart + stringsStart + stringOffsets[i]
                            if (off in 0 until data.size) {
                                val s = readAxmlString(data, off, (flags and (1 shl 8)) != 0)
                                stringPool.add(s)
                            } else {
                                stringPool.add("")
                            }
                        }
                    }
                    0x0102 -> {
                        // START_TAG
                        val lineNumber = bb.int
                        val commentIdx = bb.int
                        val nsIdx = bb.int
                        val nameIdx = bb.int
                        val attrStart = bb.short.toInt() and 0xFFFF
                        val attrSize = bb.short.toInt() and 0xFFFF
                        val attrCount = bb.short.toInt() and 0xFFFF
                        val idIndex = bb.short.toInt() and 0xFFFF
                        val classIndex = bb.short.toInt() and 0xFFFF
                        val styleIndex = bb.short.toInt() and 0xFFFF

                        val tagName = stringPool.getOrNull(nameIdx) ?: ""

                        // Parse attributes
                        val attrs = mutableMapOf<String, String>()
                        for (i in 0 until attrCount) {
                            val aNsIdx = bb.int
                            val aNameIdx = bb.int
                            val aRawValIdx = bb.int
                            val aTypedValSize = bb.short.toInt() and 0xFFFF
                            val aTypedValRes0 = bb.get()
                            val aTypedValType = bb.get().toInt() and 0xFF
                            val aTypedValData = bb.int

                            val aName = stringPool.getOrNull(aNameIdx) ?: ""
                            val aVal = when {
                                aTypedValType == 0x03 && aTypedValData in 0 until stringPool.size -> stringPool[aTypedValData]
                                aRawValIdx in 0 until stringPool.size -> stringPool[aRawValIdx]
                                aTypedValType == 0x10 -> aTypedValData.toString() // INT_DEC
                                aTypedValType == 0x11 -> "0x" + Integer.toHexString(aTypedValData) // INT_HEX
                                aTypedValType == 0x12 -> if (aTypedValData != 0) "true" else "false" // INT_BOOLEAN
                                else -> aTypedValData.toString()
                            }
                            if (aName.isNotBlank()) {
                                attrs[aName] = aVal
                            }
                        }

                        when (tagName) {
                            "manifest" -> {
                                attrs["package"]?.let { pkg = it }
                                attrs["versionCode"]?.toLongOrNull()?.let { verCode = it }
                                attrs["versionName"]?.let { verName = it }
                            }
                            "uses-sdk" -> {
                                attrs["minSdkVersion"]?.toIntOrNull()?.let { minSdk = it }
                                attrs["targetSdkVersion"]?.toIntOrNull()?.let { targetSdk = it }
                            }
                            "application" -> {
                                attrs["name"]?.let { appClass = resolveFullClassName(pkg, it) }
                            }
                            "activity", "service", "receiver", "provider" -> {
                                val compName = attrs["name"]?.let { resolveFullClassName(pkg, it) } ?: ""
                                val exported = attrs["exported"]?.toBooleanStrictOrNull()
                                val perm = attrs["permission"]
                                if (compName.isNotBlank()) {
                                    components.add(ManifestComponent(tagName, compName, exported, perm))
                                }
                            }
                        }
                    }
                }

                // Advance to end of chunk
                val nextPos = chunkStart + chunkSize
                if (nextPos in 0..data.size) {
                    bb.position(nextPos)
                } else {
                    break
                }
            }
        }

        return ManifestInfo(pkg, verCode, verName, minSdk, targetSdk, appClass, components)
    }

    private fun readAxmlString(data: ByteArray, offset: Int, isUtf8: Boolean): String {
        return runCatching {
            if (isUtf8) {
                // UTF-8 string: skip length varints
                var pos = offset
                while (pos < data.size && (data[pos].toInt() and 0x80) != 0) pos++
                pos++ // string length
                while (pos < data.size && (data[pos].toInt() and 0x80) != 0) pos++
                pos++ // byte length
                val start = pos
                while (pos < data.size && data[pos] != 0.toByte()) pos++
                String(data, start, pos - start, Charsets.UTF_8)
            } else {
                // UTF-16 string
                var pos = offset
                val lenLow = data[pos].toInt() and 0xFF
                val lenHigh = data[pos + 1].toInt() and 0xFF
                var charLen = lenLow or (lenHigh shl 8)
                pos += 2
                if ((charLen and 0x8000) != 0) {
                    pos += 2 // skip secondary length word
                }
                val start = pos
                val byteLen = charLen * 2
                if (start + byteLen <= data.size) {
                    String(data, start, byteLen, Charsets.UTF_16LE)
                } else ""
            }
        }.getOrDefault("")
    }

    private fun resolveFullClassName(pkg: String, name: String): String {
        return when {
            name.startsWith(".") -> "$pkg$name"
            !name.contains(".") -> "$pkg.$name"
            else -> name
        }
    }

    // ==========================================
    // Multi-DEX 符号扫描器 (纯 Kotlin 实现)
    // ==========================================

    data class DexScanSummary(
        val dexCount: Int,
        val totalClasses: Int,
        val candidates: List<CandidateClass>
    )

    fun scanDexSymbolsFromApk(
        apkFile: File,
        keywords: List<String>,
        maxCandidates: Int,
        appPackageName: String
    ): DexScanSummary {
        var dexCount = 0
        var totalClasses = 0
        val candidateMap = mutableMapOf<String, CandidateClass>()

        ZipFile(apkFile).use { zip ->
            val dexEntries = zip.entries().asSequence()
                .filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                .sortedBy { it.name }
                .toList()

            dexCount = dexEntries.size

            dexEntries.forEach { entry ->
                zip.getInputStream(entry).use { stream ->
                    val bytes = stream.readBytes()
                    val parsed = parseDexBytes(bytes, keywords, appPackageName)
                    totalClasses += parsed.totalClasses
                    parsed.matchedCandidates.forEach { candidate ->
                        if (candidateMap.size < maxCandidates) {
                            candidateMap[candidate.className] = candidate
                        }
                    }
                }
            }
        }

        return DexScanSummary(
            dexCount = dexCount,
            totalClasses = totalClasses,
            candidates = candidateMap.values.toList()
        )
    }

    data class SingleDexParseResult(
        val totalClasses: Int,
        val matchedCandidates: List<CandidateClass>
    )

    fun parseDexBytes(
        bytes: ByteArray,
        keywords: List<String>,
        appPackageName: String
    ): SingleDexParseResult {
        if (bytes.size < 0x70) return SingleDexParseResult(0, emptyList())
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // 校验 DEX Magic: dex\n035\0 .. dex\n039\0
        val magic = ByteArray(8)
        bb.get(magic)
        if (magic[0] != 'd'.code.toByte() || magic[1] != 'e'.code.toByte() || magic[2] != 'x'.code.toByte()) {
            return SingleDexParseResult(0, emptyList())
        }

        bb.position(0x38)
        val stringIdsSize = bb.int
        val stringIdsOff = bb.int
        val typeIdsSize = bb.int
        val typeIdsOff = bb.int
        val protoIdsSize = bb.int
        val protoIdsOff = bb.int
        val fieldIdsSize = bb.int
        val fieldIdsOff = bb.int
        val methodIdsSize = bb.int
        val methodIdsOff = bb.int
        val classDefsSize = bb.int
        val classDefsOff = bb.int

        // 1. 读取 String IDs
        val stringOffsets = IntArray(stringIdsSize)
        bb.position(stringIdsOff)
        for (i in 0 until stringIdsSize) {
            stringOffsets[i] = bb.int
        }

        fun readDexString(idx: Int): String {
            if (idx !in 0 until stringIdsSize) return ""
            var pos = stringOffsets[idx]
            // Read ULEB128 utf16_size
            while (pos < bytes.size && (bytes[pos].toInt() and 0x80) != 0) pos++
            pos++ // skip the last byte of uleb128
            val start = pos
            while (pos < bytes.size && bytes[pos] != 0.toByte()) pos++
            return String(bytes, start, pos - start, Charsets.UTF_8)
        }

        // 2. 读取 Type IDs -> 对应的字符串描述符
        val typeDescriptors = Array(typeIdsSize) { "" }
        bb.position(typeIdsOff)
        for (i in 0 until typeIdsSize) {
            val descriptorIdx = bb.int
            typeDescriptors[i] = readDexString(descriptorIdx)
        }

        fun descriptorToClassName(desc: String): String {
            if (desc.startsWith("L") && desc.endsWith(";")) {
                return desc.substring(1, desc.length - 1).replace('/', '.')
            }
            return when (desc) {
                "V" -> "void"
                "Z" -> "boolean"
                "B" -> "byte"
                "S" -> "short"
                "C" -> "char"
                "I" -> "int"
                "J" -> "long"
                "F" -> "float"
                "D" -> "double"
                else -> desc
            }
        }

        // 3. 读取 Proto IDs (方法原型: 返回值与参数)
        data class ProtoInfo(val returnType: String, val paramTypes: List<String>)
        val protos = Array(protoIdsSize) { ProtoInfo("void", emptyList()) }
        bb.position(protoIdsOff)
        for (i in 0 until protoIdsSize) {
            val shortyIdx = bb.int
            val returnTypeIdx = bb.int
            val parametersOff = bb.int

            val retType = descriptorToClassName(typeDescriptors.getOrElse(returnTypeIdx) { "void" })
            val paramTypes = mutableListOf<String>()

            if (parametersOff > 0 && parametersOff < bytes.size) {
                val origPos = bb.position()
                bb.position(parametersOff)
                val paramCount = bb.int
                for (p in 0 until paramCount) {
                    val pTypeIdx = bb.short.toInt() and 0xFFFF
                    paramTypes.add(descriptorToClassName(typeDescriptors.getOrElse(pTypeIdx) { "Object" }))
                }
                bb.position(origPos)
            }

            protos[i] = ProtoInfo(retType, paramTypes)
        }

        // 4. 读取 Method IDs
        val classMethods = mutableMapOf<String, MutableList<MethodSignature>>()
        bb.position(methodIdsOff)
        for (i in 0 until methodIdsSize) {
            val classIdx = bb.short.toInt() and 0xFFFF
            val protoIdx = bb.short.toInt() and 0xFFFF
            val nameIdx = bb.int

            val className = descriptorToClassName(typeDescriptors.getOrElse(classIdx) { "" })
            val methodName = readDexString(nameIdx)
            val proto = protos.getOrElse(protoIdx) { ProtoInfo("void", emptyList()) }

            if (className.isNotBlank() && methodName.isNotBlank()) {
                val list = classMethods.getOrPut(className) { mutableListOf() }
                list.add(MethodSignature(className, methodName, proto.returnType, proto.paramTypes))
            }
        }

        // 5. 读取 Class Defs
        val candidateList = mutableListOf<CandidateClass>()
        bb.position(classDefsOff)
        for (i in 0 until classDefsSize) {
            val classIdx = bb.int
            val accessFlags = bb.int
            val superclassIdx = bb.int
            val interfacesOff = bb.int
            val sourceFileIdx = bb.int
            val annotationsOff = bb.int
            val classDataOff = bb.int
            val staticValuesOff = bb.int

            val className = descriptorToClassName(typeDescriptors.getOrElse(classIdx) { "" })
            val superclass = if (superclassIdx in 0 until typeIdsSize) {
                descriptorToClassName(typeDescriptors[superclassIdx])
            } else null

            // 过滤匹配候选类
            val isPackageMatch = appPackageName.isNotBlank() && className.startsWith(appPackageName)
            val isKeywordMatch = keywords.isEmpty() || keywords.any { kw ->
                className.contains(kw, ignoreCase = true) ||
                (classMethods[className]?.any { it.methodName.contains(kw, ignoreCase = true) } == true)
            }

            if (isPackageMatch || isKeywordMatch || keywords.isEmpty()) {
                val methods = classMethods[className] ?: emptyList()
                candidateList.add(CandidateClass(className, superclass, methods))
            }
        }

        return SingleDexParseResult(
            totalClasses = classDefsSize,
            matchedCandidates = candidateList
        )
    }
}
