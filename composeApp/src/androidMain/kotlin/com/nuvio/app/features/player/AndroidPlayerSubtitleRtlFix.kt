@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

internal object AndroidPlayerSubtitleRtlFix {

    private const val RLM = '\u200F'

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
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length + 8)
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
        while (rawStart < end0 && line[rawStart].isWhitespace()) rawStart++
        var rawEnd = end0
        while (rawEnd > rawStart && line[rawEnd - 1].isWhitespace()) rawEnd--
        if (rawStart >= rawEnd) return line

        // 1. معالجة شارحة الحوار المقلوبة في النهاية
        val isDialogEnd = line[rawEnd - 1] == '-' && (rawEnd - 1 == rawStart || line[rawEnd - 2].isWhitespace())
        if (isDialogEnd) {
            val out: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0 + 2)
            out.append("- ")
            var textEnd = rawEnd - 1
            while (textEnd > rawStart && (line[textEnd - 1] == '-' || line[textEnd - 1].isWhitespace())) {
                textEnd--
            }
            out.append(line.subSequence(rawStart, textEnd))
            if (hasCr) out.append('\r')
            return finishBuilder(out)
        }

        // 2. فحص الرموز المقلوبة في البداية فقط (مع حماية - و ")
        val isDialogStart = line[rawStart] == '-' && (rawStart + 1 == rawEnd || line[rawStart + 1].isWhitespace() || !isMalformedLeadingPunctuation(line[rawStart + 1]))
        var startPunctLen = 0
        if (!isDialogStart) {
            while (rawStart + startPunctLen < rawEnd && isMalformedLeadingPunctuation(line[rawStart + startPunctLen])) {
                startPunctLen++
            }
        }

        val out: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0 + 6)

        // 3. تثبيت علامة الاقتباس إذا بدأت في أول السطر
        var actualStart = rawStart + startPunctLen
        if (startPunctLen == 0 && actualStart < rawEnd && (line[actualStart] == '"' || line[actualStart] == '“')) {
            out.append(line[actualStart]).append(RLM)
            actualStart++
        }

        if (startPunctLen > 0) {
            out.append(line.subSequence(actualStart, rawEnd))
            out.append(RLM)
            for (i in 0 until startPunctLen) {
                out.append(mirrorPunctuation(line[rawStart + i]))
            }
        } else {
            // معالجة الأقواس ومنع ابتلاع التنقيط أو علامات الاقتباس: (جين).
            var i = actualStart
            while (i < rawEnd) {
                val c = line[i]
                out.append(c)
                if (c == ')' && i + 1 < rawEnd && isTrailingAfterParen(line[i + 1])) {
                    out.append(RLM)
                }
                i++
            }

            // تثبيت علامة الاقتباس إذا انتهى بها السطر (السطر الأول أو الثاني)
            val lastChar = line[rawEnd - 1]
            if (lastChar == '"' || lastChar == '”') {
                out.append(RLM)
            }
        }

        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    private fun isTrailingAfterParen(c: Char): Boolean {
        return c == '.' || c == '،' || c == ',' || c == '!' || c == '؟' || c == '?' || c == '"' || c == '”'
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
