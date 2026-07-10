package com.example.kennys_dokidoki_wallpaper

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class CharacterData(
    val name: String,
    val description: String,
    val personality: String,
    val firstMes: String,
    val scenario: String,
    val systemPrompt: String
)

object TavernCardParser {
    private const val TAG = "TavernCardParser"

    fun parsePng(inputStream: InputStream): JSONObject? {
        try {
            val signature = ByteArray(8)
            if (inputStream.read(signature) != 8) return null
            if (signature[0] != 0x89.toByte() || signature[1] != 0x50.toByte() ||
                signature[2] != 0x4E.toByte() || signature[3] != 0x47.toByte()) {
                Log.e(TAG, "Not a valid PNG file signature")
                return null
            }

            val buffer = ByteArray(4)
            while (true) {
                if (inputStream.read(buffer) != 4) break
                val length = ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN).int
                val typeBytes = ByteArray(4)
                if (inputStream.read(typeBytes) != 4) break
                val type = String(typeBytes, Charsets.US_ASCII)

                if (type == "IEND") break

                if (type == "tEXt" || type == "iTXt" || type == "zTXt") {
                    val data = ByteArray(length)
                    var read = 0
                    while (read < length) {
                        val r = inputStream.read(data, read, length - read)
                        if (r == -1) break
                        read += r
                    }
                    // Skip CRC
                    inputStream.skip(4)

                    val json = parseTextChunk(type, data)
                    if (json != null) {
                        return json
                    }
                } else {
                    // Skip chunk data + CRC
                    var toSkip = length.toLong() + 4
                    while (toSkip > 0) {
                        val skipped = inputStream.skip(toSkip)
                        if (skipped <= 0) break
                        toSkip -= skipped
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PNG", e)
        }
        return null
    }

    private fun decompress(data: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(data)
        val outputStream = java.io.ByteArrayOutputStream(data.size)
        val buffer = ByteArray(1024)
        try {
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) {
                    break
                }
                outputStream.write(buffer, 0, count)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inflating bytes", e)
        } finally {
            inflater.end()
        }
        return outputStream.toByteArray()
    }

    private fun parseTextChunk(type: String, data: ByteArray): JSONObject? {
        try {
            if (type == "tEXt") {
                // Key and text are separated by a null byte (0x00)
                var nullIndex = -1
                for (i in data.indices) {
                    if (data[i] == 0.toByte()) {
                        nullIndex = i
                        break
                    }
                }
                if (nullIndex == -1) return null
                val key = String(data, 0, nullIndex, Charsets.ISO_8859_1)
                if (key.equals("chara", ignoreCase = true) || key.equals("character", ignoreCase = true)) {
                    val valueBytes = data.copyOfRange(nullIndex + 1, data.size)
                    val value = String(valueBytes, Charsets.UTF_8)
                    return decodeJson(value)
                }
            } else if (type == "zTXt") {
                // Key (null terminated) + Compression method (1 byte, must be 0) + Compressed text (zlib)
                var nullIndex = -1
                for (i in data.indices) {
                    if (data[i] == 0.toByte()) {
                        nullIndex = i
                        break
                    }
                }
                if (nullIndex == -1) return null
                val key = String(data, 0, nullIndex, Charsets.ISO_8859_1)
                if (key.equals("chara", ignoreCase = true) || key.equals("character", ignoreCase = true)) {
                    if (nullIndex + 1 < data.size) {
                        val compMethod = data[nullIndex + 1]
                        if (compMethod == 0.toByte()) {
                            val compressedBytes = data.copyOfRange(nullIndex + 2, data.size)
                            val decompressedBytes = decompress(compressedBytes)
                            val value = String(decompressedBytes, Charsets.UTF_8)
                            return decodeJson(value)
                        }
                    }
                }
            } else if (type == "iTXt") {
                // Key (null terminated) + Compression flag (1 byte) + Compression method (1 byte) + Language tag (null term) + Trans key (null term) + Text
                var idx = 0
                while (idx < data.size && data[idx] != 0.toByte()) {
                    idx++
                }
                if (idx >= data.size) return null
                val key = String(data, 0, idx, Charsets.ISO_8859_1)
                if (key.equals("chara", ignoreCase = true) || key.equals("character", ignoreCase = true)) {
                    idx++ // skip null
                    if (idx + 2 >= data.size) return null
                    val compFlag = data[idx]
                    val compMethod = data[idx + 1]
                    idx += 2
                    
                    // Skip Lang Tag
                    while (idx < data.size && data[idx] != 0.toByte()) {
                        idx++
                    }
                    idx++ // skip null
                    
                    // Skip Trans Key
                    while (idx < data.size && data[idx] != 0.toByte()) {
                        idx++
                    }
                    idx++ // skip null
                    
                    if (idx < data.size) {
                        val textBytes = data.copyOfRange(idx, data.size)
                        if (compFlag == 1.toByte()) {
                            // Deflate-compressed
                            val decompressedBytes = decompress(textBytes)
                            val value = String(decompressedBytes, Charsets.UTF_8)
                            return decodeJson(value)
                        } else {
                            val value = String(textBytes, Charsets.UTF_8)
                            return decodeJson(value)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing text chunk", e)
        }
        return null
    }

    private fun decodeJson(value: String): JSONObject? {
        val trimmed = value.trim()
        if (trimmed.startsWith("{")) {
            return JSONObject(trimmed)
        }
        try {
            val decoded = String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8)
            if (decoded.trim().startsWith("{")) {
                return JSONObject(decoded)
            }
        } catch (e: Exception) {
            // Not Base64
        }
        return null
    }

    fun extractCharacterData(json: JSONObject): CharacterData {
        val charObj = json.optJSONObject("character")
        val dataObj = json.optJSONObject("data")
        val source = charObj ?: dataObj ?: json

        val name = source.optString("name", "Unknown Name")
        val description = source.optString("description", "")
        val personality = source.optString("personality", "")
        val firstMes = source.optString("first_mes", source.optString("first_message", source.optString("first_msg", "")))
        val scenario = source.optString("scenario", "")
        val systemPrompt = source.optString("system_prompt", "")

        return CharacterData(name, description, personality, firstMes, scenario, systemPrompt)
    }

    fun formatDescriptionText(data: CharacterData): String {
        val sb = java.lang.StringBuilder()
        sb.append("【キャラクター名】\n${data.name}\n\n")
        if (data.description.isNotEmpty()) {
            sb.append("【説明・プロフィール】\n${data.description}\n\n")
        }
        if (data.personality.isNotEmpty()) {
            sb.append("【性格設定】\n${data.personality}\n\n")
        }
        if (data.scenario.isNotEmpty()) {
            sb.append("【シチュエーション】\n${data.scenario}\n\n")
        }
        if (data.systemPrompt.isNotEmpty()) {
            sb.append("【システムプロンプト指示】\n${data.systemPrompt}\n\n")
        }
        
        sb.append("<TavernMeta>\n")
        val meta = JSONObject().apply {
            put("name", data.name)
            if (data.firstMes.isNotEmpty()) {
                put("first_mes", data.firstMes)
            }
        }
        sb.append(meta.toString())
        sb.append("\n</TavernMeta>")
        
        return sb.toString()
    }

    fun getTavernMeta(description: String?): JSONObject? {
        if (description == null) return null
        val startTag = "<TavernMeta>"
        val endTag = "</TavernMeta>"
        val startIdx = description.indexOf(startTag)
        val endIdx = description.indexOf(endTag)
        if (startIdx != -1 && endIdx != -1 && endIdx > startIdx) {
            val jsonStr = description.substring(startIdx + startTag.length, endIdx).trim()
            try {
                return JSONObject(jsonStr)
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing metadata block JSON", e)
            }
        }
        return null
    }

    fun cleanDescriptionText(description: String?): String {
        if (description == null) return ""
        val startTag = "<TavernMeta>"
        val startIdx = description.indexOf(startTag)
        if (startIdx != -1) {
            return description.substring(0, startIdx).trim()
        }
        return description
    }

    fun copyUriToLocalFiles(context: Context, sourceUri: Uri, prefix: String, extension: String): Uri? {
        try {
            val resolver = context.contentResolver
            val inputStream = resolver.openInputStream(sourceUri) ?: return null
            val fileName = "${prefix}_${System.currentTimeMillis()}.$extension"
            val destFile = File(context.filesDir, fileName)
            destFile.outputStream().use { outputStream ->
                inputStream.use { it.copyTo(outputStream) }
            }
            return Uri.fromFile(destFile)
        } catch (e: Exception) {
            Log.e(TAG, "Error copying URI to local files", e)
        }
        return null
    }
}
