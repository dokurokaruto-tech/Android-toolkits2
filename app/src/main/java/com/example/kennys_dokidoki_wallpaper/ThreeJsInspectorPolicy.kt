package com.example.kennys_dokidoki_wallpaper

enum class ThreeJsJsonKind {
    THREE_APP,
    THREE_SCENE,
    THREE_GEOMETRY,
    GENERIC_JSON,
    NOT_JSON;

    fun isThreeJsPlayable(): Boolean {
        return this == THREE_APP || this == THREE_SCENE || this == THREE_GEOMETRY
    }

    fun isValidJson(): Boolean {
        return this != NOT_JSON
    }
}

enum class NetworkResourceBadge {
    THREE_APP,
    THREE_3D,
    JSON,
    SCRIPT,
    OTHER;

    fun priorityRank(): Int {
        return when (this) {
            THREE_APP -> 0
            THREE_3D -> 1
            JSON -> 2
            SCRIPT -> 3
            OTHER -> 4
        }
    }
}

enum class NetworkFilterKind {
    ALL,
    THREE_CANDIDATES,
    SCRIPTS
}

data class JsonInspectionResult(
    val kind: ThreeJsJsonKind,
    val suggestedTitle: String,
    val metadataType: String,
    val generator: String,
    val scriptCount: Int,
    val geometryCount: Int,
    val materialCount: Int,
    val imageCount: Int,
    val summaryText: String,
    val errorDetail: String = ""
)

data class CompactedJsonDoc(
    val displayText: String,
    val placeholders: Map<String, String>
)

object ThreeJsInspectorPolicy {
    const val DEFAULT_BROWSER_URL = "https://www.google.com/"
    private const val GOOGLE_SEARCH_PREFIX = "https://www.google.com/search?q="
    private const val MIN_BLOB_CHARS = 256
    private const val MIN_ARRAY_COMPACT_CHARS = 2400
    private const val PRETTY_PRINT_MAX_CHARS = 180_000
    private const val SINGLE_LINE_THRESHOLD = 4
    private const val MAX_PREVIEW_CHARS = 16_000
    private const val BYTES_PER_KB = 1024

    fun resolveBrowserInput(rawInput: String?): String {
        val trimmed = rawInput?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return DEFAULT_BROWSER_URL
        }
        val lower = trimmed.lowercase()
        if (lower.startsWith("https://") || lower.startsWith("http://") || lower.startsWith("about:")) {
            return trimmed
        }
        if (looksLikeHostOrPath(trimmed)) {
            if (lower.startsWith("localhost") || lower.startsWith("127.0.0.1")) {
                return "http://$trimmed"
            }
            return "https://$trimmed"
        }
        return GOOGLE_SEARCH_PREFIX + percentEncodeQuery(trimmed)
    }

    fun suggestTitleFromUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) {
            return ""
        }
        val clean = rawUrl.substringBefore('#').substringBefore('?').trimEnd('/')
        val segments = clean.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) {
            return ""
        }
        val last = segments.last()
        val stem = last.removeSuffix(".json").removeSuffix(".JSON")
        if ((stem.equals("app", ignoreCase = true) || stem.equals("scene", ignoreCase = true)) &&
            segments.size >= 2
        ) {
            return sanitizeTitle(segments[segments.size - 2])
        }
        return sanitizeTitle(stem)
    }

    fun classifyNetworkItem(
        url: String,
        mimeType: String?,
        inspection: JsonInspectionResult?
    ): NetworkResourceBadge {
        if (inspection != null) {
            return when (inspection.kind) {
                ThreeJsJsonKind.THREE_APP -> NetworkResourceBadge.THREE_APP
                ThreeJsJsonKind.THREE_SCENE,
                ThreeJsJsonKind.THREE_GEOMETRY -> NetworkResourceBadge.THREE_3D
                ThreeJsJsonKind.GENERIC_JSON -> NetworkResourceBadge.JSON
                ThreeJsJsonKind.NOT_JSON -> classifyByUrlAndMime(url, mimeType)
            }
        }
        return classifyByUrlAndMime(url, mimeType)
    }

    fun isLikelyJsonRequest(url: String, mimeType: String?): Boolean {
        val badge = classifyByUrlAndMime(url, mimeType)
        return badge == NetworkResourceBadge.JSON
    }

    fun matchesFilter(badge: NetworkResourceBadge, filter: NetworkFilterKind): Boolean {
        return when (filter) {
            NetworkFilterKind.ALL -> true
            NetworkFilterKind.THREE_CANDIDATES -> {
                badge == NetworkResourceBadge.THREE_APP ||
                    badge == NetworkResourceBadge.THREE_3D ||
                    badge == NetworkResourceBadge.JSON
            }
            NetworkFilterKind.SCRIPTS -> badge == NetworkResourceBadge.SCRIPT
        }
    }

    fun inspectJsonText(rawText: String, fallbackUrl: String? = null): JsonInspectionResult {
        val cleaned = rawText.removePrefix("\uFEFF").trim()
        if (cleaned.isEmpty()) {
            return invalidResult("空のレスポンスです")
        }
        val scanner = StreamingJsonScanner(cleaned)
        val scan = scanner.scanRoot()
        if (scan.error != null) {
            return invalidResult(scan.error)
        }
        if (!scan.isObjectRoot) {
            return JsonInspectionResult(
                kind = ThreeJsJsonKind.GENERIC_JSON,
                suggestedTitle = suggestTitleFromUrl(fallbackUrl).ifEmpty { "json-data" },
                metadataType = "Array/Primitive",
                generator = "",
                scriptCount = 0,
                geometryCount = 0,
                materialCount = 0,
                imageCount = 0,
                summaryText = "有効な JSON ですが、Three.js シーンオブジェクトではありません"
            )
        }

        val kind = determineThreeKind(scan)
        val rawTitle = scan.projectTitle
            .ifEmpty { scan.objectName }
            .ifEmpty { suggestTitleFromUrl(fallbackUrl) }
            .ifEmpty { defaultTitleForKind(kind) }

        return JsonInspectionResult(
            kind = kind,
            suggestedTitle = rawTitle,
            metadataType = scan.metadataType,
            generator = scan.generator,
            scriptCount = scan.scriptCount,
            geometryCount = scan.geometryCount,
            materialCount = scan.materialCount,
            imageCount = scan.imageCount,
            summaryText = buildSummaryText(kind, scan)
        )
    }

    fun compactForEditor(rawJson: String): CompactedJsonDoc {
        val placeholders = LinkedHashMap<String, String>()
        var blobIndex = 0
        val afterStrings = compactDataUriLiterals(rawJson) { literalContent ->
            val token = "__THREEJS_DATA_URI_${blobIndex}__[${summarizeDataUri(literalContent)}]"
            blobIndex++
            placeholders[token] = literalContent
            token
        }
        var arrayIndex = 0
        val afterArrays = compactLargeNumericArrays(afterStrings) { body ->
            val kb = (body.length / BYTES_PER_KB).coerceAtLeast(1)
            val token = "__THREEJS_NUMERIC_ARRAY_${arrayIndex}__[${kb}KB]"
            arrayIndex++
            placeholders["\"$token\""] = body
            "\"$token\""
        }
        val formatted = if (shouldPrettyPrint(afterArrays)) {
            prettyPrintJson(afterArrays)
        } else {
            afterArrays
        }
        return CompactedJsonDoc(displayText = formatted, placeholders = placeholders)
    }

    fun expandFromEditor(editedText: String, doc: CompactedJsonDoc): String {
        if (doc.placeholders.isEmpty()) {
            return editedText
        }
        var restored = editedText
        for ((token, original) in doc.placeholders) {
            restored = restored.replace(token, original)
        }
        return restored
    }

    fun formatInspectorPreview(rawContent: String): String {
        val compacted = compactForEditor(rawContent).displayText
        if (compacted.length <= MAX_PREVIEW_CHARS) {
            return compacted
        }
        return compacted.substring(0, MAX_PREVIEW_CHARS) + "\n\n... (${compacted.length} chars)"
    }

    private fun classifyByUrlAndMime(url: String, mimeType: String?): NetworkResourceBadge {
        val cleanPath = url.substringBefore('#').substringBefore('?').lowercase()
        val mime = mimeType?.lowercase().orEmpty()
        if (cleanPath.endsWith(".json") || mime.contains("json")) {
            return NetworkResourceBadge.JSON
        }
        if (cleanPath.endsWith(".js") || cleanPath.endsWith(".mjs") || mime.contains("javascript")) {
            return NetworkResourceBadge.SCRIPT
        }
        return NetworkResourceBadge.OTHER
    }

    private fun looksLikeHostOrPath(input: String): Boolean {
        if (input.contains(' ') || input.contains('\n') || input.contains('\t')) {
            return false
        }
        val hostPart = input.substringBefore('/').substringBefore(':').lowercase()
        if (hostPart == "localhost" || hostPart == "127.0.0.1") {
            return true
        }
        if (!hostPart.contains('.')) {
            return false
        }
        val tld = hostPart.substringAfterLast('.')
        return tld.length >= 2 && tld.all { it.isLetterOrDigit() }
    }

    private fun percentEncodeQuery(query: String): String {
        val bytes = query.encodeToByteArray()
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val code = byte.toInt() and 0xFF
            val ch = code.toChar()
            val isUnreserved = (ch in 'a'..'z') ||
                (ch in 'A'..'Z') ||
                (ch in '0'..'9') ||
                ch == '-' || ch == '_' || ch == '.' || ch == '~'
            if (isUnreserved) {
                out.append(ch)
            } else if (ch == ' ') {
                out.append('+')
            } else {
                out.append('%')
                out.append( HEX_DIGITS[code ushr 4] )
                out.append( HEX_DIGITS[code and 0x0F] )
            }
        }
        return out.toString()
    }

    private fun sanitizeTitle(raw: String): String {
        return raw.replace('_', ' ').replace('-', ' ').trim()
    }

    private fun invalidResult(reason: String): JsonInspectionResult {
        return JsonInspectionResult(
            kind = ThreeJsJsonKind.NOT_JSON,
            suggestedTitle = "",
            metadataType = "",
            generator = "",
            scriptCount = 0,
            geometryCount = 0,
            materialCount = 0,
            imageCount = 0,
            summaryText = "JSON ではありません: $reason",
            errorDetail = reason
        )
    }

    private fun determineThreeKind(scan: RootScan): ThreeJsJsonKind {
        val metaLower = scan.metadataType.lowercase()
        if (metaLower == "app" || (scan.hasScene && scan.hasCamera) || (scan.hasScene && scan.hasScripts)) {
            return ThreeJsJsonKind.THREE_APP
        }
        if (metaLower == "object" || metaLower == "scene" || scan.hasObjectNode) {
            return ThreeJsJsonKind.THREE_SCENE
        }
        if (metaLower == "buffergeometry" || metaLower == "geometry" || scan.hasGeometryData) {
            return ThreeJsJsonKind.THREE_GEOMETRY
        }
        return ThreeJsJsonKind.GENERIC_JSON
    }

    private fun defaultTitleForKind(kind: ThreeJsJsonKind): String {
        return when (kind) {
            ThreeJsJsonKind.THREE_APP -> "three-app"
            ThreeJsJsonKind.THREE_SCENE -> "three-scene"
            ThreeJsJsonKind.THREE_GEOMETRY -> "three-geometry"
            ThreeJsJsonKind.GENERIC_JSON -> "json-work"
            ThreeJsJsonKind.NOT_JSON -> ""
        }
    }

    private fun buildSummaryText(kind: ThreeJsJsonKind, scan: RootScan): String {
        val parts = mutableListOf<String>()
        when (kind) {
            ThreeJsJsonKind.THREE_APP -> parts.add("Three.js App 作品データ (そのまま再生可能)")
            ThreeJsJsonKind.THREE_SCENE -> parts.add("Three.js Scene / Object データ (自動カメラ再生対応)")
            ThreeJsJsonKind.THREE_GEOMETRY -> parts.add("Three.js Geometry データ (自動プレビュー再生対応)")
            ThreeJsJsonKind.GENERIC_JSON -> parts.add("一般 JSON データ (Three.js シーン構造は未検出)")
            ThreeJsJsonKind.NOT_JSON -> parts.add("非 JSON")
        }
        if (scan.generator.isNotEmpty()) {
            parts.add("generator=${scan.generator}")
        }
        if (scan.scriptCount > 0) {
            parts.add("scripts=${scan.scriptCount}")
        }
        if (scan.geometryCount > 0) {
            parts.add("geometries=${scan.geometryCount}")
        }
        if (scan.materialCount > 0) {
            parts.add("materials=${scan.materialCount}")
        }
        if (scan.imageCount > 0) {
            parts.add("images=${scan.imageCount}")
        }
        return parts.joinToString(" · ")
    }

    private fun summarizeDataUri(content: String): String {
        val header = content.removePrefix("data:").substringBefore(',', "blob")
        val kb = (content.length / BYTES_PER_KB).coerceAtLeast(1)
        return "$header,${kb}KB"
    }

    private fun compactDataUriLiterals(
        input: String,
        makePlaceholder: (String) -> String
    ): String {
        val out = StringBuilder(minOf(input.length, 65_536))
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            if (ch != '"') {
                out.append(ch)
                i++
                continue
            }
            val start = i
            i++
            var escaped = false
            while (i < input.length) {
                val c = input[i]
                if (escaped) {
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else if (c == '"') {
                    break
                }
                i++
            }
            if (i >= input.length) {
                out.append(input, start, input.length)
                break
            }
            val inner = input.substring(start + 1, i)
            if (inner.length >= MIN_BLOB_CHARS && inner.startsWith("data:")) {
                out.append('"')
                out.append(makePlaceholder(inner))
                out.append('"')
            } else {
                out.append(input, start, i + 1)
            }
            i++
        }
        return out.toString()
    }

    private fun compactLargeNumericArrays(
        input: String,
        makePlaceholder: (String) -> String
    ): String {
        val out = StringBuilder(input.length)
        var i = 0
        var inString = false
        var escaped = false
        while (i < input.length) {
            val ch = input[i]
            if (inString) {
                out.append(ch)
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                i++
                continue
            }
            if (ch == '"') {
                inString = true
                out.append(ch)
                i++
                continue
            }
            if (ch == '[') {
                val end = findNumericArrayEnd(input, i + 1)
                if (end > 0 && (end - (i + 1)) >= MIN_ARRAY_COMPACT_CHARS) {
                    val inner = input.substring(i + 1, end)
                    out.append('[')
                    out.append(makePlaceholder(inner))
                    out.append(']')
                    i = end + 1
                    continue
                }
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun findNumericArrayEnd(input: String, startInside: Int): Int {
        var j = startInside
        var sawDigit = false
        while (j < input.length) {
            val c = input[j]
            if (c == ']') {
                return if (sawDigit) j else -1
            }
            val allowed = (c in '0'..'9') ||
                c == '-' || c == '+' || c == '.' ||
                c == 'e' || c == 'E' || c == ',' ||
                c == ' ' || c == '\n' || c == '\r' || c == '\t'
            if (!allowed) {
                return -1
            }
            if (c in '0'..'9') {
                sawDigit = true
            }
            j++
        }
        return -1
    }

    private fun shouldPrettyPrint(text: String): Boolean {
        if (text.length > PRETTY_PRINT_MAX_CHARS || text.length < 40) {
            return false
        }
        var lines = 1
        for (c in text) {
            if (c == '\n') {
                lines++
                if (lines > SINGLE_LINE_THRESHOLD) {
                    return false
                }
            }
        }
        return true
    }

    private fun prettyPrintJson(raw: String): String {
        val out = StringBuilder(raw.length + raw.length / 4)
        var indent = 0
        var inString = false
        var escaped = false
        for (ch in raw) {
            if (inString) {
                out.append(ch)
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
                continue
            }
            when (ch) {
                '"' -> {
                    inString = true
                    out.append(ch)
                }
                '{', '[' -> {
                    out.append(ch)
                    indent++
                    appendNewlineIndent(out, indent)
                }
                '}', ']' -> {
                    indent = (indent - 1).coerceAtLeast(0)
                    appendNewlineIndent(out, indent)
                    out.append(ch)
                }
                ',' -> {
                    out.append(ch)
                    appendNewlineIndent(out, indent)
                }
                ':' -> out.append(": ")
                ' ', '\n', '\r', '\t' -> Unit
                else -> out.append(ch)
            }
        }
        return out.toString()
    }

    private fun appendNewlineIndent(out: StringBuilder, indent: Int) {
        out.append('\n')
        repeat(indent) {
            out.append("  ")
        }
    }

    private val HEX_DIGITS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    )
}

private data class RootScan(
    val isObjectRoot: Boolean = false,
    val metadataType: String = "",
    val generator: String = "",
    val projectTitle: String = "",
    val objectName: String = "",
    val hasScene: Boolean = false,
    val hasCamera: Boolean = false,
    val hasScripts: Boolean = false,
    val hasObjectNode: Boolean = false,
    val hasGeometryData: Boolean = false,
    val scriptCount: Int = 0,
    val geometryCount: Int = 0,
    val materialCount: Int = 0,
    val imageCount: Int = 0,
    val error: String? = null
)

private class StreamingJsonScanner(private val src: String) {
    private var pos = 0
    private var metadataType = ""
    private var generator = ""
    private var projectTitle = ""
    private var objectName = ""
    private var hasScene = false
    private var hasCamera = false
    private var hasScripts = false
    private var hasObjectNode = false
    private var hasGeometryData = false
    private var scriptCount = 0
    private var geometryCount = 0
    private var materialCount = 0
    private var imageCount = 0

    fun scanRoot(): RootScan {
        try {
            skipWs()
            if (pos >= src.length) {
                return RootScan(error = "空の入力です")
            }
            val first = src[pos]
            if (first != '{') {
                parseAnyValue(depth = 0, path = "")
                skipWs()
                if (pos != src.length) {
                    return RootScan(error = "末尾に余分な文字があります (位置 $pos)")
                }
                return RootScan(isObjectRoot = false)
            }
            parseObject(depth = 0, path = "")
            skipWs()
            if (pos != src.length) {
                return RootScan(error = "末尾に余分な文字があります (位置 $pos)")
            }
            return RootScan(
                isObjectRoot = true,
                metadataType = metadataType,
                generator = generator,
                projectTitle = projectTitle,
                objectName = objectName,
                hasScene = hasScene,
                hasCamera = hasCamera,
                hasScripts = hasScripts,
                hasObjectNode = hasObjectNode,
                hasGeometryData = hasGeometryData,
                scriptCount = scriptCount,
                geometryCount = geometryCount,
                materialCount = materialCount,
                imageCount = imageCount
            )
        } catch (e: JsonSyntaxProblem) {
            return RootScan(error = e.message ?: "JSON 構文エラー")
        }
    }

    private fun parseAnyValue(depth: Int, path: String) {
        if (depth > 96) {
            throw JsonSyntaxProblem("ネストが深すぎます (位置 $pos)")
        }
        skipWs()
        if (pos >= src.length) {
            throw JsonSyntaxProblem("途中で JSON が終了しています (位置 $pos)")
        }
        when (val ch = src[pos]) {
            '{' -> parseObject(depth, path)
            '[' -> parseArray(depth, path)
            '"' -> {
                val readMode = if (shouldCaptureString(path)) {
                    StringReadMode.CAPTURE
                } else {
                    StringReadMode.SKIP
                }
                val value = parseString(readMode)
                recordStringAtPath(path, value)
            }
            't' -> consumeLiteral("true")
            'f' -> consumeLiteral("false")
            'n' -> consumeLiteral("null")
            '-', in '0'..'9' -> consumeNumber()
            else -> throw JsonSyntaxProblem("不正な文字 '$ch' (位置 $pos)")
        }
    }

    private fun parseObject(depth: Int, path: String): Int {
        expectChar('{')
        skipWs()
        if (pos < src.length && src[pos] == '}') {
            pos++
            return 0
        }
        var entries = 0
        while (true) {
            skipWs()
            if (pos >= src.length || src[pos] != '"') {
                throw JsonSyntaxProblem("オブジェクトキーが必要です (位置 $pos)")
            }
            val keyMode = if (depth <= 2) StringReadMode.CAPTURE else StringReadMode.SKIP
            val key = parseString(keyMode)
            skipWs()
            expectChar(':')
            val childPath = if (depth <= 2) {
                if (path.isEmpty()) key else "$path.$key"
            } else {
                ""
            }
            recordKeyPresence(depth, childPath, key)
            skipWs()
            if (childPath == "scripts" && pos < src.length && src[pos] == '{') {
                scriptCount = parseObject(depth + 1, childPath)
            } else if ((childPath == "geometries" || childPath == "scene.geometries") &&
                pos < src.length && src[pos] == '['
            ) {
                geometryCount += parseArray(depth + 1, childPath)
            } else if ((childPath == "materials" || childPath == "scene.materials") &&
                pos < src.length && src[pos] == '['
            ) {
                materialCount += parseArray(depth + 1, childPath)
            } else if ((childPath == "images" || childPath == "scene.images") &&
                pos < src.length && src[pos] == '['
            ) {
                imageCount += parseArray(depth + 1, childPath)
            } else {
                parseAnyValue(depth + 1, childPath)
            }
            entries++
            skipWs()
            if (pos >= src.length) {
                throw JsonSyntaxProblem("'}' が閉じられていません (位置 $pos)")
            }
            when (src[pos]) {
                ',' -> pos++
                '}' -> {
                    pos++
                    return entries
                }
                else -> throw JsonSyntaxProblem("',' または '}' が必要です (位置 $pos)")
            }
        }
    }

    private fun parseArray(depth: Int, path: String): Int {
        expectChar('[')
        skipWs()
        if (pos < src.length && src[pos] == ']') {
            pos++
            return 0
        }
        var count = 0
        while (true) {
            parseAnyValue(depth + 1, if (depth <= 1) "$path[]" else "")
            count++
            skipWs()
            if (pos >= src.length) {
                throw JsonSyntaxProblem("']' が閉じられていません (位置 $pos)")
            }
            when (src[pos]) {
                ',' -> pos++
                ']' -> {
                    pos++
                    return count
                }
                else -> throw JsonSyntaxProblem("',' または ']' が必要です (位置 $pos)")
            }
        }
    }

    private fun recordKeyPresence(depth: Int, childPath: String, key: String) {
        if (depth == 0) {
            when (key) {
                "scene" -> hasScene = true
                "camera" -> hasCamera = true
                "scripts" -> hasScripts = true
                "object" -> hasObjectNode = true
            }
        }
        if (childPath == "scene.object") {
            hasObjectNode = true
        }
        if (childPath == "data.attributes") {
            hasGeometryData = true
        }
    }

    private fun shouldCaptureString(path: String): Boolean {
        return path == "metadata.type" ||
            path == "metadata.generator" ||
            path == "project.title" ||
            path == "object.name" ||
            path == "scene.object.name"
    }

    private fun recordStringAtPath(path: String, value: String) {
        when (path) {
            "metadata.type" -> if (metadataType.isEmpty()) {
                metadataType = value
            }
            "metadata.generator" -> if (generator.isEmpty()) {
                generator = value
            }
            "project.title" -> if (projectTitle.isEmpty()) {
                projectTitle = value
            }
            "object.name", "scene.object.name" -> if (objectName.isEmpty()) {
                objectName = value
            }
        }
    }

    private fun parseString(mode: StringReadMode): String {
        expectChar('"')
        val out = if (mode == StringReadMode.CAPTURE) StringBuilder() else null
        while (pos < src.length) {
            val ch = src[pos++]
            if (ch == '"') {
                return out?.toString().orEmpty()
            }
            if (ch < ' ') {
                throw JsonSyntaxProblem("文字列内に未エスケープの制御文字があります (位置 ${pos - 1})")
            }
            if (ch != '\\') {
                out?.append(ch)
                continue
            }
            if (pos >= src.length) {
                throw JsonSyntaxProblem("エスケープシーケンスが途中で終了しています")
            }
            when (val esc = src[pos++]) {
                '"', '\\', '/' -> out?.append(esc)
                'b' -> out?.append('\b')
                'f' -> out?.append('\u000C')
                'n' -> out?.append('\n')
                'r' -> out?.append('\r')
                't' -> out?.append('\t')
                'u' -> {
                    if (pos + 4 > src.length) {
                        throw JsonSyntaxProblem("\\uXXXX が不完全です (位置 $pos)")
                    }
                    var code = 0
                    repeat(4) {
                        val hex = hexValue(src[pos++])
                        if (hex < 0) {
                            throw JsonSyntaxProblem("不正な16進エスケープ (位置 ${pos - 1})")
                        }
                        code = (code shl 4) or hex
                    }
                    out?.append(code.toChar())
                }
                else -> throw JsonSyntaxProblem("不正なエスケープ '\\$esc' (位置 ${pos - 1})")
            }
        }
        throw JsonSyntaxProblem("文字列リテラルが閉じられていません")
    }

    private fun consumeLiteral(expected: String) {
        if (!src.startsWith(expected, pos)) {
            throw JsonSyntaxProblem("リテラル '$expected' が不正です (位置 $pos)")
        }
        pos += expected.length
    }

    private fun consumeNumber() {
        if (src[pos] == '-') {
            pos++
        }
        if (pos >= src.length) {
            throw JsonSyntaxProblem("数値が不完全です (位置 $pos)")
        }
        if (src[pos] == '0') {
            pos++
        } else if (src[pos] in '1'..'9') {
            while (pos < src.length && src[pos] in '0'..'9') {
                pos++
            }
        } else {
            throw JsonSyntaxProblem("数値形式が不正です (位置 $pos)")
        }
        if (pos < src.length && src[pos] == '.') {
            pos++
            if (pos >= src.length || src[pos] !in '0'..'9') {
                throw JsonSyntaxProblem("小数部の数字が必要です (位置 $pos)")
            }
            while (pos < src.length && src[pos] in '0'..'9') {
                pos++
            }
        }
        if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
            pos++
            if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) {
                pos++
            }
            if (pos >= src.length || src[pos] !in '0'..'9') {
                throw JsonSyntaxProblem("指数部の数字が必要です (位置 $pos)")
            }
            while (pos < src.length && src[pos] in '0'..'9') {
                pos++
            }
        }
    }

    private fun expectChar(expected: Char) {
        if (pos >= src.length || src[pos] != expected) {
            throw JsonSyntaxProblem("'$expected' が必要です (位置 $pos)")
        }
        pos++
    }

    private fun skipWs() {
        while (pos < src.length) {
            val c = src[pos]
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                pos++
            } else {
                break
            }
        }
    }

    private fun hexValue(c: Char): Int {
        return when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> -1
        }
    }
}

private enum class StringReadMode {
    CAPTURE,
    SKIP
}

private class JsonSyntaxProblem(message: String) : RuntimeException(message)
