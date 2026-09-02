@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

internal object AndroidPlayerSubtitleRtlFix {

    private const val RLE = '\u202B' // Right-to-Left Embedding (إجبار الاتجاه RTL)
    private const val PDF = '\u202C' // Pop Directional Format (إنهاء عزل السطر)
    private const val RLM = '\u200F' // Right-to-Left Mark (تثبيت محاذاة الرموز المحايدة)

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
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length + 16)
        val lines = text.splitByNewlines()
        var changed = false

        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            val line = lines[i]

            val fixed = when {
                hasHebrewCharacters(line) -> fixHebrewPunctuationForLtr(line, preserveSpans)
                else -> fixArabicPunctuation(line, preserveSpans)
            }

            if (fixed !== line && fixed.toString() != line.toString()) {
                changed = true
            }
            builder.append(fixed)
        }

        if (!changed) return null
        return finishBuilder(builder)
    }

    private fun fixArabicPunctuation(line: CharSequence, preserveSpans: Boolean): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line[line.length - 1] == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var rawStart = 0
        while (rawStart < end0 && line[rawStart].isWhitespace()) {
            rawStart++
        }
        var rawEnd = end0
        while (rawEnd > rawStart && line[rawEnd - 1].isWhitespace()) {
            rawEnd--
        }
        if (rawStart >= rawEnd) return line

        // 1. فحص شرطات الحوار المقلوبة في الطرف الأيسر (النهاية برمجياً)
        val isDialogEnd = line[rawEnd - 1] == '-' && (rawEnd - 1 == rawStart || line[rawEnd - 2].isWhitespace())

        // 2. فحص الرموز المقلوبة في البداية
        var startPunctLen = 0
        val isDialogStart = line[rawStart] == '-' && (rawStart + 1 == rawEnd || line[rawStart + 1].isWhitespace() || !isMalformedLeadingPunctuation(line[rawStart + 1]))

        if (!isDialogStart && !isDialogEnd) {
            while (rawStart + startPunctLen < rawEnd && isMalformedLeadingPunctuation(line[rawStart + startPunctLen])) {
                startPunctLen++
            }
        }

        val out: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0 + 10)

        // تغليف السطر بالكامل داخل عازل اتجاهي قوي
        out.append(RLE)

        if (isDialogEnd) {
            out.append("- ")
            var textEnd = rawEnd - 1
            while (textEnd > rawStart && (line[textEnd - 1] == '-' || line[textEnd - 1].isWhitespace())) {
                textEnd--
            }
            appendWithNeutralProtection(out, line, rawStart, textEnd)
        } else {
            val contentStart = rawStart + startPunctLen
            appendWithNeutralProtection(out, line, contentStart, rawEnd)

            if (startPunctLen > 0) {
                out.append(RLM)
                for (i in 0 until startPunctLen) {
                    out.append(mirrorPunctuation(line[rawStart + i]))
                }
            }
        }

        // غلق العازل الاتجاهي للسطر
        out.append(PDF)
        if (hasCr) out.append('\r')

        return finishBuilder(out)
    }

    // حقن محرف التوجيه RLM بجانب الأقواس والاقتباسات والنقاط لضمان عدم ابتلاعها أو انعكاسها
    private fun appendWithNeutralProtection(out: Appendable, line: CharSequence, from: Int, to: Int) {
        var i = from
        while (i < to) {
            val c = line[i]
            when (c) {
                '"', '”', '“' -> {
                    out.append(RLM).append(c).append(RLM)
                }
                ')' -> {
                    // إذا كان القوس متبوعاً بنقطة أو فاصلة، نضع RLM بينهما لمنع انقلاب الترتيب
                    out.append(c).append(RLM)
                }
                '(' -> {
                    out.append(RLM).append(c)
                }
                else -> {
                    out.append(c)
                }
            }
            i++
        }
    }

    private fun fixHebrewPunctuationForLtr(line: CharSequence, preserveSpans: Boolean): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line[line.length - 1] == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var start = 0
        while (start < end0 && isHebrewPunctuation(line[start], isEnd = false)) {
            start++
        }
        var end = end0
        while (end > start && isHebrewPunctuation(line[end - 1], isEnd = true)) {
            end--
        }

        if (start == 0 && end == end0) {
            return line
        }

        val out: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0)
        appendHebrewMirroredReversed(out, line, end, end0)
        out.append(line.subSequence(start, end))
        appendHebrewMirroredReversed(out, line, 0, start)
        if (hasCr) out.append('\r')

        return finishBuilder(out)
    }

    private fun isMalformedLeadingPunctuation(c: Char): Boolean {
        return c == '.' || c == '،' || c == ',' || c == '؟' || c == '?' || c == '!' ||
               c == ':' || c == ';' || c == '…' || c == ')' || c == '(' || c == ']' || c == '['
    }

    private fun isHebrewPunctuation(ch: Char, isEnd: Boolean): Boolean {
        if (isEnd && ch.isDigit()) return false
        return ch in HEBREW_PUNCTUATION || ch.isWhitespace()
    }

    private fun mirrorPunctuation(c: Char): Char = when (c) {
        '(' -> ')'
        ')' -> '('
        '[' -> ']'
        ']' -> '['
        '{' -> '}'
        '}' -> '{'
        else -> c
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
                if (codePoint in 0x0590..0x08FF || codePoint in 0xFB1D..0xFEFF) {
                    return true
                }
                val d = Character.getDirectionality(codePoint)
                if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                    d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                ) {
                    return true
                }
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
            if (codePoint in 0x0590..0x05FF || codePoint in 0xFB1D..0xFB4F) {
                return true
            }
            i += Character.charCount(codePoint)
        }
        return false
    }

    private val HEBREW_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(', '[', ']', '{', '}', '\'', '"') + ('0'..'9')
}
