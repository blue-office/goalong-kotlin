package me.goalong.sdk

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

/**
 * 紹介コードの正規化と、紹介リンクからの抽出(cross-platform 仕様 §3・§4)。
 * iOS / React Native / Flutter と同じ規則・同じテストケース。
 */
internal object ReferralLink {
    const val DEFAULT_HOST = "goalong.me"

    private val codePattern = Regex("^[A-Z0-9]{3,20}$")
    private val linkPattern = Regex(
        "^https://([^/?#]+)(/[^?#]*)?(?:\\?[^#]*)?(?:#.*)?$",
        RegexOption.IGNORE_CASE,
    )
    private val forbiddenAuthorityChars = Regex("[:@%\\\\\\s]")
    private val hostPattern = Regex("^[a-z0-9-]+(?:\\.[a-z0-9-]+)*$")

    /** NFKC → 前後の空白除去 → 大文字 */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKC).trim().uppercase()

    fun isValidCode(code: String): Boolean = codePattern.matches(code)

    /** 入力欄の値からコードを得る。`://` を含めば紹介リンクとして厳格に抽出する */
    fun normalizedCode(text: String, expectedHost: String, appSlug: String?): String? {
        if (text.contains("://")) return extract(text, expectedHost, appSlug)
        val candidate = normalize(text)
        return if (isValidCode(candidate)) candidate else null
    }

    /**
     * 正規の紹介リンク `https://{host}/{appSlug}/{CODE}` だけを認める。
     * URL の正規化で不正な表記(:443、ドットセグメント等)を失わないよう、生文字列を検査する。
     */
    fun extract(text: String, expectedHost: String = DEFAULT_HOST, appSlug: String? = null): String? {
        val trimmed = text.trim()
        val match = linkPattern.matchEntire(trimmed) ?: return null
        val authority = match.groupValues[1]
        if (forbiddenAuthorityChars.containsMatchIn(authority)) return null
        val host = authority.lowercase()
        if (!hostPattern.matches(host)) return null
        val expected = expectedHost.lowercase()
        if (host != expected && !host.endsWith(".$expected")) return null

        val path = match.groups[2]?.value ?: return null
        val parts = path.split("/")
        if (parts.size != 3 || parts[0].isNotEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) return null
        val slug = decodeSegment(parts[1]) ?: return null
        val rawCode = decodeSegment(parts[2]) ?: return null
        if (slug == "." || slug == ".." || slug.contains('/') || slug.contains('\\')) return null
        if (appSlug != null && !slug.equals(appSlug, ignoreCase = true)) return null
        val candidate = normalize(rawCode)
        return if (isValidCode(candidate)) candidate else null
    }

    /** ASCII の 16 進数字だけを受け付ける(Character.digit は全角数字も通すため使わない) */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * パーセントエンコードを 1 回だけ厳格に解く(`+` は空白にしない)。
     * 不正な `%`・不正な UTF-8 は null。リテラルの非 ASCII 文字は UTF-8 として扱う
     */
    fun decodeSegment(segment: String): String? {
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%') {
                if (i + 2 >= segment.length) return null
                val hi = hexValue(segment[i + 1])
                val lo = hexValue(segment[i + 2])
                if (hi < 0 || lo < 0) return null
                bytes.write(hi * 16 + lo)
                i += 3
            } else {
                val cp = segment.codePointAt(i)
                val count = Character.charCount(cp)
                if (Character.isSurrogate(c) && count == 1) return null
                bytes.write(String(Character.toChars(cp)).toByteArray(Charsets.UTF_8))
                i += count
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }
}
