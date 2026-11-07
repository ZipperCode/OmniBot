package cn.com.omnimind.bot.agent.development

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
class ApkAnalysisServiceTest {

    @Test
    fun parseDexBytes_extractsClassesAndMethodsAccurately() {
        val dexBytes = buildSampleDexBytes(
            className = "Lcom/target/app/SecurityManager;",
            superclassName = "Ljava/lang/Object;",
            methodName = "verifyUserToken",
            returnType = "Z", // boolean
            paramTypes = listOf("Ljava/lang/String;", "I") // (String, int)
        )

        val result = ApkAnalysisService.parseDexBytes(
            bytes = dexBytes,
            keywords = listOf("Security"),
            appPackageName = "com.target.app"
        )

        assertEquals(1, result.totalClasses)
        assertEquals(1, result.matchedCandidates.size)

        val candidate = result.matchedCandidates[0]
        assertEquals("com.target.app.SecurityManager", candidate.className)
        assertEquals("java.lang.Object", candidate.superclass)

        assertEquals(1, candidate.methods.size)
        val method = candidate.methods[0]
        assertEquals("verifyUserToken", method.methodName)
        assertEquals("boolean", method.returnType)
        assertEquals(listOf("java.lang.String", "int"), method.parameterTypes)
        assertEquals("boolean com.target.app.SecurityManager#verifyUserToken(java.lang.String, int)", method.fullSignature)
    }

    @Test
    fun parseDexBytes_returnsEmptyOnInvalidMagicOrTruncatedBytes() {
        val corruptBytes = ByteArray(50) { 0 }
        val result = ApkAnalysisService.parseDexBytes(corruptBytes, emptyList(), "")
        assertEquals(0, result.totalClasses)
        assertTrue(result.matchedCandidates.isEmpty())
    }
    @Test
    fun parseAxmlManifest_extractsPackageAndSdkWithoutInventingMissingVersion() {
        val stream = ApkAnalysisServiceTest::class.java.getResourceAsStream("/phone-development/smoke-AndroidManifest.axml")
        val bytes = checkNotNull(stream) { "Resource /phone-development/smoke-AndroidManifest.axml not found" }.readBytes()

        val manifest = ApkAnalysisService.parseAxmlManifest(bytes)

        // Verifies attrs parsing correctly decoded package name (regression for missing attrs[aName] = aVal)
        assertEquals("cn.com.omnimind.smoke.module", manifest.packageName)
        assertEquals(Integer.valueOf(29), manifest.minSdkVersion)
        assertEquals(Integer.valueOf(35), manifest.targetSdkVersion)

        // Verifies missing versionCode and versionName are null and not fabricated
        assertNull("Missing versionCode must be null", manifest.versionCode)
        assertNull("Missing versionName must be null", manifest.versionName)
    }

    private fun buildSampleDexBytes(
        className: String,
        superclassName: String,
        methodName: String,
        returnType: String,
        paramTypes: List<String>
    ): ByteArray {
        val out = ByteArrayOutputStream()
        val strings = listOf(
            "",
            className,
            superclassName,
            methodName,
            returnType,
            "V"
        ) + paramTypes

        val uniqueStrings = strings.distinct()
        val stringOffsets = mutableListOf<Int>()

        // Temporary buffer for data
        val dataOut = ByteArrayOutputStream()
        uniqueStrings.forEach { s ->
            stringOffsets.add(dataOut.size())
            dataOut.write(s.length) // ULEB128 len
            dataOut.write(s.toByteArray(Charsets.UTF_8))
            dataOut.write(0) // null terminator
        }

        val headerSize = 0x70
        val stringIdsSize = uniqueStrings.size
        val stringIdsOff = headerSize

        val typeIdsSize = uniqueStrings.size
        val typeIdsOff = stringIdsOff + stringIdsSize * 4

        val protoIdsSize = 1
        val protoIdsOff = typeIdsOff + typeIdsSize * 4

        val fieldIdsSize = 0
        val fieldIdsOff = protoIdsOff + protoIdsSize * 12

        val methodIdsSize = 1
        val methodIdsOff = fieldIdsOff

        val classDefsSize = 1
        val classDefsOff = methodIdsOff + methodIdsSize * 8

        val dataOffset = classDefsOff + classDefsSize * 32
        val stringDataBytes = dataOut.toByteArray()

        // 对齐 4 字节存放参数列表 (type_list)
        val typeListOffset = (dataOffset + stringDataBytes.size + 3) and 3.inv()
        val totalSize = typeListOffset + 4 + paramTypes.size * 2 + 32

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Magic "dex\n035\0"
        buf.put(byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 0x0A, '0'.code.toByte(), '3'.code.toByte(), '5'.code.toByte(), 0x00))
        buf.putInt(0) // checksum
        buf.put(ByteArray(20)) // signature
        buf.putInt(totalSize) // file_size
        buf.putInt(headerSize)
        buf.putInt(0x12345678) // endian_tag
        buf.putInt(0); buf.putInt(0) // link
        buf.putInt(0) // map_off

        buf.putInt(stringIdsSize)
        buf.putInt(stringIdsOff)
        buf.putInt(typeIdsSize)
        buf.putInt(typeIdsOff)
        buf.putInt(protoIdsSize)
        buf.putInt(protoIdsOff)
        buf.putInt(fieldIdsSize)
        buf.putInt(fieldIdsOff)
        buf.putInt(methodIdsSize)
        buf.putInt(methodIdsOff)
        buf.putInt(classDefsSize)
        buf.putInt(classDefsOff)
        buf.putInt(stringDataBytes.size)
        buf.putInt(dataOffset)

        // String IDs
        buf.position(stringIdsOff)
        stringOffsets.forEach { off ->
            buf.putInt(dataOffset + off)
        }

        // Type IDs
        buf.position(typeIdsOff)
        uniqueStrings.indices.forEach { idx ->
            buf.putInt(idx)
        }

        // Proto IDs
        buf.position(protoIdsOff)
        val returnTypeIdx = uniqueStrings.indexOf(returnType)
        buf.putInt(0) // shorty_idx
        buf.putInt(returnTypeIdx) // return_type_idx
        buf.putInt(typeListOffset) // parameters_off 指向真实参数类型列表

        // 写入参数类型列表 (type_list)
        buf.position(typeListOffset)
        buf.putInt(paramTypes.size)
        paramTypes.forEach { p ->
            val pTypeIdx = uniqueStrings.indexOf(p)
            buf.putShort(pTypeIdx.toShort())
        }
        // Method IDs
        buf.position(methodIdsOff)
        val classIdx = uniqueStrings.indexOf(className)
        val nameIdx = uniqueStrings.indexOf(methodName)
        buf.putShort(classIdx.toShort())
        buf.putShort(0.toShort()) // proto_idx
        buf.putInt(nameIdx)

        // Class Defs
        buf.position(classDefsOff)
        val superclassIdx = uniqueStrings.indexOf(superclassName)
        buf.putInt(classIdx)
        buf.putInt(1) // access_flags
        buf.putInt(superclassIdx)
        buf.putInt(0) // interfaces_off
        buf.putInt(0); buf.putInt(0); buf.putInt(0); buf.putInt(0)

        // String data
        buf.position(dataOffset)
        buf.put(stringDataBytes)

        return buf.array()
    }
}
