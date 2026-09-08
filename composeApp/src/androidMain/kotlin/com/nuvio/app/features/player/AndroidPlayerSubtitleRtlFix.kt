@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

internal object AndroidPlayerSubtitleRtlFix {

    private const val DEFAULT_CUE_DURATION_US = 5_000_000L
    private const val MAX_PUNCTUATION_SCAN_DEPTH = 20

    private val DASHES = setOf('-', '—', '–', '‐', '‒', '¬')

    private val EXTENDED_TERMINALS = setOf(
        '؟', '?', '!', '.', '،', ',', ':', ';', '؛',
        '”', '’', '»', '›', '〞', '〟', '"', '\'',
        '！', '？', '。', '、', '।', '॥', '։'
    )

    private val BASE_BRACKETS = mapOf(
        '(' to ')', '[' to ']', '{' to '}', '<' to '>',
        '「' to '」', '『' to '』', '【' to '】', '〔' to '〕', '〖' to '〗', '《' to '》',
        '〈' to '〉', '〘' to '〙', '〚' to '〛', '⟦' to '⟧', '⟨' to '⟩', '⟪' to '⟫', '⟬' to '⟭', '⟮' to '⟯',
        '⦃' to '⦄', '⦅' to '⦆', '⸢' to '⸣', '⸤' to '⸥'
    )

    private val SYMMETRICAL_SYMBOLS = setOf(
        '♪', '♫', '♬', '♩', '*', '_', '|', '~', '^', '`',
        '#', '=', '+', '%', '•', '°'
    )

    private fun getMatchingSymbol(c: Char): Char {
        BASE_BRACKETS[c]?.let { return it }
        for ((open, close) in BASE_BRACKETS) {
            if (close == c) return open
        }
        return c
    }

    private fun isBoundaryPunctuation(c: Char): Boolean {
        return c in DASHES || c in EXTENDED_TERMINALS || BASE_BRACKETS.containsKey(c) || BASE_BRACKETS.containsValue(c) || c in SYMMETRICAL_SYMBOLS
    }

    private fun isUnbalancedInLine(c: Char, line: CharSequence): Boolean {
        if (c in SYMMETRICAL_SYMBOLS) {
            var count = 0
            for (i in line.indices) {
                if (line[i] == c) count++
            }
            return count % 2 != 0
        }

        if (BASE_BRACKETS.containsKey(c) || BASE_BRACKETS.containsValue(c)) {
            val open = if (BASE_BRACKETS.containsKey(c)) c else getMatchingSymbol(c)
            val close = BASE_BRACKETS[open] ?: return false
            var openCount = 0
            var closeCount = 0
            for (i in line.indices) {
                if (line[i] == open) openCount++
                else if (line[i] == close) closeCount++
            }
            return openCount != closeCount
        }

        return false
    }

    fun fixCueText(cue: Cue, isBuiltInSubtitle: Boolean): Cue {
        val text = cue.text ?: return cue
        
        var hasArabic = false
        var hasHebrew = false
        var hasRtl = false

        for (i in 0 until text.length) {
            val codePoint = Character.codePointAt(text, i)
            val d = Character.getDirectionality(codePoint)
            
            if (codePoint in 0x0600..0x08FF || codePoint in 0xFB50..0xFDFF || codePoint in 0xFE70..0xFEFF || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                hasArabic = true
                hasRtl = true
                break 
            } else if (codePoint in 0x0590..0x05FF || codePoint in 0xFB1D..0xFB4F || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT) {
                hasHebrew = true
                hasRtl = true
            }
        }

        if (!hasRtl) return cue

        if (hasArabic) {
            val isMessy = isMessySubtitle(text, isBuiltInSubtitle)
            val fixed = if (isMessy) applySmartDirectionalWrapping(text) else wrapArabicLines(text)
            if (fixed.contentEquals(text)) return cue
            return cue.buildUpon().setText(fixed).build()
        }

        if (hasHebrew) {
            val fixed = fixHebrewLines(text, isBuiltInSubtitle) ?: return cue
            if (fixed.contentEquals(text)) return cue
            return cue.buildUpon().setText(fixed).build()
        }

        return cue
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
                        for (j in 0 until i) modified.add(entryCues[j])
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
            else -> DEFAULT_CUE_DURATION_US
        }
        return CuesWithTiming(cues, entry.startTimeUs, durationUs)
    }

    private fun isMessySubtitle(text: CharSequence, isBuiltInSubtitle: Boolean): Boolean {
        if (isBuiltInSubtitle) return false
        val lines = text.splitByNewlines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (hasMessyLeadingBoundary(trimmed) || hasMessyTrailingBoundary(trimmed)) {
                return true
            }
        }
        return false
    }

    private fun hasMessyLeadingBoundary(line: CharSequence): Boolean {
        var start = 0
        while (start < line.length && start < MAX_PUNCTUATION_SCAN_DEPTH) {
            val c = line[start]
            if (c.isWhitespace()) {
                start++
                continue
            }
            if (!isBoundaryPunctuation(c)) break

            if (c == '…' || (c == '.' && start + 2 < line.length && line[start+1] == '.' && line[start+2] == '.')) {
                return true
            } else if (c in DASHES || c in EXTENDED_TERMINALS || BASE_BRACKETS.containsValue(c) || BASE_BRACKETS.containsKey(c) || isUnbalancedInLine(c, line)) {
                return true
            }
            start++
        }
        return false
    }

    private fun hasMessyTrailingBoundary(line: CharSequence): Boolean {
        var end = line.length
        var depth = 0
        while (end > 0 && depth < MAX_PUNCTUATION_SCAN_DEPTH) {
            val c = line[end - 1]
            if (c.isWhitespace()) {
                end--
                depth++
                continue
            }
            if (!isBoundaryPunctuation(c)) break

            if (c in DASHES || c in EXTENDED_TERMINALS || BASE_BRACKETS.containsKey(c) || isUnbalancedInLine(c, line)) {
                return true
            }
            end--
            depth++
        }
        return false
    }

    private fun fixStraightQuoteVisualHack(line: CharSequence): CharSequence {
        var quoteCount = 0
        var quoteIndex = -1
        for (i in line.indices) {
            if (line[i] == '"') {
                quoteCount++
                quoteIndex = i
            }
        }
        
        if (quoteCount != 1) return line

        var isAtStart = true
        for (i in 0 until quoteIndex) {
            val c = line[i]
            if (!c.isWhitespace() && c !in DASHES) {
                isAtStart = false
                break
            }
        }

        if (isAtStart) {
            val sb = if (line is Spanned) SpannableStringBuilder(line) else java.lang.StringBuilder(line)
            sb.delete(quoteIndex, quoteIndex + 1)
            var insertPos = sb.length
            while (insertPos > 0 && (sb[insertPos - 1].isWhitespace() || sb[insertPos - 1] in DASHES)) {
                insertPos--
            }
            sb.insert(insertPos, "\"")
            return sb
        }

        var isAtEnd = true
        for (i in quoteIndex + 1 until line.length) {
            val c = line[i]
            if (!c.isWhitespace() && c !in DASHES) {
                isAtEnd = false
                break
            }
        }

        if (isAtEnd) {
            val sb = if (line is Spanned) SpannableStringBuilder(line) else java.lang.StringBuilder(line)
            sb.delete(quoteIndex, quoteIndex + 1)
            var insertPos = 0
            while (insertPos < sb.length && (sb[insertPos].isWhitespace() || sb[insertPos] in DASHES)) {
                insertPos++
            }
            sb.insert(insertPos, "\"")
            return sb
        }

        return line
    }

    private fun applySmartDirectionalWrapping(text: CharSequence): CharSequence {
        val preserveSpans = text is Spanned
        val lines = text.splitByNewlines()
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else java.lang.StringBuilder(text.length + 16)

        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            val line = lines[i].stripDirectionalWrap()

            if (line.isEmpty() || !containsArabic(line)) {
                builder.append(line)
                continue
            }

            val hasCr = line.isNotEmpty() && line.last() == '\r'
            val cleanCore = if (hasCr) line.subSequence(0, line.length - 1) else line

            if (cleanCore.isEmpty()) {
                if (hasCr) builder.append('\r')
                continue
            }

            val fixedCore = fixStraightQuoteVisualHack(cleanCore)
            val pinnedCore = pinInteriorNeutralMarks(fixedCore)

            builder.append('\u200F')
            builder.append('\u202B')
            builder.append(pinnedCore)
            builder.append('\u202C')
            builder.append('\u200F')

            if (hasCr) builder.append('\r')
        }
        return finishBuilder(builder)
    }

    private fun pinInteriorNeutralMarks(text: CharSequence): CharSequence {
        var found = false
        for (i in text.indices) {
            if (isBoundaryPunctuation(text[i])) {
                found = true
                break
            }
        }
        if (!found) return text

        val sb = if (text is Spanned) SpannableStringBuilder(text) else java.lang.StringBuilder(text)
        var i = sb.length - 1
        while (i >= 0) {
            if (isBoundaryPunctuation(sb[i])) {
                sb.insert(i + 1, "\u200F")
                sb.insert(i, "\u200F")
            }
            i--
        }
        return sb
    }

    private fun containsArabic(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint in 0x0600..0x06FF ||
                codePoint in 0x0750..0x077F ||
                codePoint in 0x0870..0x08FF ||
                codePoint in 0xFB50..0xFDFF ||
                codePoint in 0xFE70..0xFEFF ||
                Character.getDirectionality(codePoint) == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            ) {
                return true
            }
            i += Character.charCount(codePoint)
        }
        return false
    }

    private fun wrapArabicLines(text: CharSequence): CharSequence {
        val preserveSpans = text is Spanned
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else java.lang.StringBuilder(text.length + 8)
        val lines = text.splitByNewlines()
        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            val line = lines[i].stripDirectionalWrap()
            if (line.isEmpty()) {
                builder.append(line)
                continue
            }
            val hasCr = line.isNotEmpty() && line.last() == '\r'
            val core = if (hasCr) line.subSequence(0, line.length - 1) else line
            if (core.isEmpty()) {
                builder.append(line)
                continue
            }

            val pinnedCore = pinInteriorNeutralMarks(core)
            builder.append('\u200F').append('\u202B').append(pinnedCore).append('\u202C').append('\u200F')

            if (hasCr) builder.append('\r')
        }
        return finishBuilder(builder)
    }

    private fun fixHebrewLines(text: CharSequence, isBuiltInSubtitle: Boolean): CharSequence? {
        val preserveSpans = text is Spanned
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else java.lang.StringBuilder(text.length)
        val lines = text.splitByNewlines()
        var changed = false
        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            val line = lines[i]
            val fixed = if (isBuiltInSubtitle) {
                moveLeadingRtlPunctuationToEndForBuiltIn(line, preserveSpans)
            } else {
                fixRtlPunctuationForLtr(line, preserveSpans)
            }
            if (fixed !== line && fixed.toString() != line.toString()) changed = true
            builder.append(fixed)
        }
        if (!changed) return null
        return finishBuilder(builder)
    }

    private fun finishBuilder(builder: Appendable): CharSequence = when (builder) {
        is SpannableStringBuilder -> builder
        is java.lang.StringBuilder -> builder.toString()
        else -> builder.toString()
    }

    private fun mirrorPunctuation(c: Char): Char = BASE_BRACKETS[c] ?: getMatchingSymbol(c)

    private fun appendMirroredReversed(out: Appendable, line: CharSequence, from: Int, toExclusive: Int) {
        if (from >= toExclusive) return
        fun isNumberSeparator(c: Char) = c == ',' || c == ':' || c == '.' || c == '-'
        val chunks = ArrayList<IntRange>()
        var i = from
        while (i < toExclusive) {
            if (line[i].isDigit()) {
                val start = i
                i++
                while (i < toExclusive) {
                    if (line[i].isDigit()) i++
                    else if (isNumberSeparator(line[i]) && i + 1 < toExclusive && line[i + 1].isDigit()) i++
                    else break
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
                if (m != c) out.append(m) else out.append(line.subSequence(range.first, range.first + 1))
            }
        }
    }

    private fun fixRtlPunctuationForLtr(line: CharSequence, preserveSpans: Boolean): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line.isNotEmpty() && line.last() == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var start = 0
        while (start < end0 && isRtlPunctuation(line[start], isEnd = false)) start++

        var end = end0
        while (end > start && isRtlPunctuation(line[end - 1], isEnd = true)) end--

        if (start == 0 && end == end0) return line

        val out: Appendable = if (preserveSpans) SpannableStringBuilder() else java.lang.StringBuilder(end0)
        appendMirroredReversed(out, line, end, end0)
        out.append(line.subSequence(start, end))
        appendMirroredReversed(out, line, 0, start)
        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    private fun moveLeadingRtlPunctuationToEndForBuiltIn(line: CharSequence, preserveSpans: Boolean): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line.isNotEmpty() && line.last() == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var end = 0
        while (end < end0 && line[end] in MOBILE_RTL_PUNCTUATION) end++
        if (end == 0) return line

        val out: Appendable = if (preserveSpans) SpannableStringBuilder() else java.lang.StringBuilder(end0)
        out.append(line.subSequence(end, end0)).append(line.subSequence(0, end))
        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    private fun CharSequence.stripDirectionalWrap(): CharSequence {
        val hasMarker = (0 until length).any { isDirectionalMark(this[it]) }
        if (!hasMarker) return this
        if (this !is Spanned) {
            val sb = java.lang.StringBuilder(length)
            for (ch in this) if (!isDirectionalMark(ch)) sb.append(ch)
            return sb.toString()
        }
        val sb = SpannableStringBuilder(this)
        var k = 0
        while (k < sb.length) {
            if (isDirectionalMark(sb[k])) sb.delete(k, k + 1) else k++
        }
        return sb
    }

    private fun isDirectionalMark(c: Char): Boolean =
        c == '\u202A' || c == '\u202B' || c == '\u202C' || c == '\u200E' || c == '\u200F'

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

    private fun isRtlPunctuation(ch: Char, isEnd: Boolean): Boolean {
        if (isEnd && ch.isDigit()) return false
        return ch in RTL_PUNCTUATION || ch.isWhitespace()
    }

    private val RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(', '\'', '"') + ('0'..'9')
    private val MOBILE_RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(')
}
