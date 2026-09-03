@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

internal object AndroidPlayerSubtitleRtlFix {

    private const val ZWNJ = '\u200C'

    fun fixCueText(cue: Cue, isBuiltInSubtitle: Boolean = false): Cue {
        val text = cue.text ?: return cue
        if (!hasAnyRtlCharacter(text)) {
            return cue
        }

        val fixed = fixRtlLines(text) ?: return cue
        if (fixed.contentEquals(text)) return cue
        return cue.buildUpon().setText(fixed).build()
    }

    fun fixTimedCues(
        cues: List<CuesWithTiming>,
        isBuiltInSubtitle: Boolean = false
    ): List<CuesWithTiming> {
        if (cues.isEmpty()) return cues
        var anyChanged = false
        val out = ArrayList<CuesWithTiming>(cues.size)
        for (entry in cues) {
            val entryCues = entry.cues
            var modified: ArrayList<Cue>? = null
            for (i in entryCues.indices) {
                val original = entryCues[i]
                val fixed = fixCueText(original, isBuiltInSubtitle)
                if (fixed !== original) {
                    if (modified == null) {
                        modified = ArrayList(entryCues.size)
                        for (j in 0 until i) {
                            modified.add(entryCues[j])
                        }
                    }
                    modified.add(fixed)
                } else {
                    modified?.add(original)
                }
            }
            if (modified != null) {
                anyChanged = true
                out.add(copyTimedCues(entry, modified))
            } else {
                out.add(entry)
            }
        }
        return if (anyChanged) out else cues
    }

    private fun copyTimedCues(entry: CuesWithTiming, cues: List<Cue>): CuesWithTiming {
        val durationUs = when {
            entry.durationUs != C.TIME_UNSET -> entry.durationUs
            entry.endTimeUs != C.TIME_UNSET && entry.startTimeUs != C.TIME_UNSET ->
                (entry.endTimeUs - entry.startTimeUs).coerceAtLeast(1L)
            else -> 5_000_000L
        }
        return CuesWithTiming(cues, entry.startTimeUs, durationUs)
    }

    private fun fixRtlLines(text: CharSequence): CharSequence? {
        val preserveSpans = text is Spanned
        val lines = text.splitByNewlines()
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length + 16)
        var changed = false

        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            val line = lines[i]

            val fixed = when {
                hasHebrewCharacters(line) -> fixHebrewPunctuationForLtr(line, preserveSpans)
                else -> fixArabicLine(line, preserveSpans)
            }

            if (fixed !== line && fixed.toString() != line.toString()) {
                changed = true
            }
            builder.append(fixed)
        }

        if (!changed) return null
        return finishBuilder(builder)
    }

    // الخوارزمية الحصرية الجديدة: التبديل المكاني للرموز (الخداع الهندسي)
    private fun fixArabicLine(line: CharSequence, preserveSpans: Boolean): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line.last() == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        val text = line.subSequence(0, end0).toString()

        // 1. استخراج كتلة الرموز والمسافات الموجودة في بداية الجملة
        var startIdx = 0
        while (startIdx < text.length && isBoundaryPunctuation(text[startIdx])) {
            startIdx++
        }

        // 2. استخراج كتلة الرموز والمسافات الموجودة في نهاية الجملة
        var endIdx = text.length
        while (endIdx > startIdx && isBoundaryPunctuation(text[endIdx - 1])) {
            endIdx--
        }

        // إذا كان السطر بالكامل مجرد رموز ومسافات، نتركه كما هو
        if (startIdx >= endIdx) {
            return line
        }

        val leadingPunct = text.substring(0, startIdx)
        val trailingPunct = text.substring(endIdx, text.length)
        val coreText = text.substring(startIdx, endIdx)

        val out: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0 + 16)

        // 3. الخدعة: لصق رموز (النهاية) في (بداية) السطر بعد عكسها وتوجيهها
        if (trailingPunct.isNotEmpty()) {
            out.append(mirrorAndReverse(trailingPunct))
        }

        // 4. لصق النص العربي الأساسي في المنتصف
        appendProcessedCore(out, coreText)

        // 5. الخدعة: لصق رموز (البداية) في (نهاية) السطر بعد عكسها وتوجيهها
        if (leadingPunct.isNotEmpty()) {
            out.append(mirrorAndReverse(leadingPunct))
        }

        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    // دالة للتعرف على الرموز الطرفية التي تحتاج للتبديل
    private fun isBoundaryPunctuation(c: Char): Boolean {
        return c.isWhitespace() || c in setOf(
            '-', '—', '"', '”', '“', '\'', '«', '»',
            '(', ')', '[', ']', '{', '}',
            '!', '؟', '?', '.', ',', '،', ':', ';', '…'
        )
    }

    // دالة لعكس ترتيب الرموز ومرايا الأقواس لتناسب موقعها الجديد
    private fun mirrorAndReverse(s: String): String {
        val sb = StringBuilder(s.length)
        for (i in s.indices.reversed()) {
            sb.append(mirrorPunctuation(s[i]))
        }
        return sb.toString()
    }

    // معالجة النص الداخلي (حماية النقطة داخل الأقواس مستقرة ولا نمسها)
    private fun appendProcessedCore(out: Appendable, core: String) {
        var i = 0
        while (i < core.length) {
            val c = core[i]
            out.append(c)
            if (c == ')' && i + 1 < core.length && core[i + 1] == '.') {
                out.append(ZWNJ)
            }
            i++
        }
    }

    private fun mirrorPunctuation(c: Char): Char = when (c) {
        '(' -> ')'
        ')' -> '('
        '[' -> ']'
        ']' -> '['
        '{' -> '}'
        '}' -> '{'
        '«' -> '»'
        '»' -> '«'
        else -> c
    }

    private fun fixHebrewPunctuationForLtr(line: CharSequence, preserveSpans: Boolean): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line[line.length - 1] == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var start = 0
        while (start < end0 && isHebrewPunctuation(line[start], isEnd = false)) start++
        var end = end0
        while (end > start && isHebrewPunctuation(line[end - 1], isEnd = true)) end--

        if (start == 0 && end == end0) return line

        val out: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0)
        appendHebrewMirroredReversed(out, line, end, end0)
        out.append(line.subSequence(start, end))
        appendHebrewMirroredReversed(out, line, 0, start)
        if (hasCr) out.append('\r')

        return finishBuilder(out)
    }

    private fun isHebrewPunctuation(ch: Char, isEnd: Boolean): Boolean {
        if (isEnd && ch.isDigit()) return false
        return ch in HEBREW_PUNCTUATION || ch.isWhitespace()
    }

    private fun appendHebrewMirroredReversed(
        out: Appendable,
        line: CharSequence,
        from: Int,
        toExclusive: Int
    ) {
        if (from >= toExclusive) return

        fun isNumberSeparator(c: Char) = c == ',' || c == ':' || c == '.' || c == '-'

        val chunks = ArrayList<IntRange>()
        var i = from
        while (i < toExclusive) {
            if (line[i].isDigit()) {
                val start = i
                i++
                while (i < toExclusive) {
                    if (line[i].isDigit()) {
                        i++
                    } else if (
                        isNumberSeparator(line[i]) &&
                        i + 1 < toExclusive &&
                        line[i + 1].isDigit()
                    ) {
                        i++
                    } else {
                        break
                    }
                }
                chunks.add(start until i)
            } else {
                chunks.add(i until i + 1)
                i++
            }
        }

        for (idx in chunks.indices.reversed()) {
            val range = chunks[idx]
            if (range.last - range.first + 1 > 1) {
                out.append(line.subSequence(range.first, range.last + 1))
            } else {
                val c = line[range.first]
                val m = mirrorPunctuation(c)
                if (m != c) out.append(m) else out.append(line.subSequence(range.first, range.last + 1))
            }
        }
    }

    private fun finishBuilder(builder: Appendable): CharSequence = when (builder) {
        is SpannableStringBuilder -> builder
        is StringBuilder -> builder.toString()
        else -> builder.toString()
    }

    private fun CharSequence.splitByNewlines(): List<CharSequence> {
        val result = mutableListOf<CharSequence>()
        var start = 0
        var i = 0
        while (i < this.length) {
            if (this[i] == '\n') {
                result.add(this.subSequence(start, i))
                start = i + 1
            }
            i++
        }
        result.add(this.subSequence(start, this.length))
        return result
    }

    private fun hasAnyRtlCharacter(text: CharSequence): Boolean {
        var i = 0
        val len = text.length
        while (i < len) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint >= 0x0590) {
                if (codePoint in 0x0590..0x08FF || codePoint in 0xFB1D..0xFEFF) return true
                val d = Character.getDirectionality(codePoint)
                if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                    d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                ) return true
            }
            i += Character.charCount(codePoint)
        }
        return false
    }

    private fun hasHebrewCharacters(text: CharSequence): Boolean {
        var i = 0
        val len = text.length
        while (i < len) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint in 0x0590..0x05FF || codePoint in 0xFB1D..0xFB4F) return true
            i += Character.charCount(codePoint)
        }
        return false
    }

    private val HEBREW_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(', '[', ']', '{', '}', '\'', '"') + ('0'..'9')
}
