@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

internal object AndroidPlayerSubtitleRtlFix {

    private val OPEN_TO_CLOSE = mapOf(
        '(' to ')', '[' to ']', '{' to '}', '<' to '>',
        '«' to '»', '»' to '«', '“' to '”', '”' to '“',
        '‘' to '’', '’' to '‘', '„' to '“', '‚' to '‘',
        '‹' to '›', '›' to '‹', '「' to '」', '『' to '』',
        '【' to '】', '〔' to '〕', '〖' to '〗', '《' to '》',
        '〈' to '〉', '〘' to '〙', '〚' to '〛', '⟦' to '⟧',
        '⟨' to '⟩', '⟪' to '⟫', '⟬' to '⟭', '⟮' to '⟯',
        '⦃' to '⦄', '⦅' to '⦆', '⸢' to '⸣', '⸤' to '⸥',
        '〝' to '〞', '〞' to '〝', '〟' to '〝'
    )

    private val CLOSE_TO_OPEN = OPEN_TO_CLOSE.entries.associate { (k, v) -> v to k }

    private val SYMMETRICAL_SYMBOLS = setOf(
        '"', '\'', '＂', '＇', '′', '″', '‵', '‶',
        '♪', '♫', '♬', '♩', '*', '_', '|', '~', '^', '`',
        '#', '=', '+', '%', '•', '°', '؟', '?', '!', '.',
        '،', ',', ':', ';', '؛'
    )

    private fun getMatchingSymbol(c: Char): Char {
        return OPEN_TO_CLOSE[c] ?: CLOSE_TO_OPEN[c] ?: c
    }

    private fun isBoundaryPunctuation(c: Char): Boolean {
        if (c.isLetterOrDigit()) return false
        return true
    }

    fun fixCueText(cue: Cue, isBuiltInSubtitle: Boolean): Cue {
        val text = cue.text ?: return cue
        if (!hasAnyRtlCharacter(text)) return cue

        if (containsArabic(text)) {
            val isMessy = isMessySubtitle(text, isBuiltInSubtitle)
            val fixed = if (isMessy) applyVisualSwapping(text) else wrapArabicLines(text)
            if (fixed.contentEquals(text)) return cue
            return cue.buildUpon().setText(fixed).build()
        }

        if (containsRtlChars(text)) {
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
            else -> 5_000_000L
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
        if (line.isEmpty()) return false
        
        var start = 0
        while (start < line.length) {
            val c = line[start]
            if (c.isWhitespace() || c == '-' || c == '—' || c == '–' || c == '‐' || c == '‒' || c == '¬') {
                start++
            } else {
                break
            }
        }
        
        if (start >= line.length) return false
        val firstChar = line[start]

        if (!isBoundaryPunctuation(firstChar)) return false

        if (firstChar == '؟' || firstChar == '?' || firstChar == '!' || firstChar == '.' ||
            firstChar == '،' || firstChar == ',' || firstChar == ':' || firstChar == '؛' || firstChar == ';') {
            return true
        }

        if (firstChar == '…' || (firstChar == '.' && start + 2 < line.length && line[start+1] == '.' && line[start+2] == '.')) {
             return true
        }

        if (CLOSE_TO_OPEN.containsKey(firstChar)) return true

        val matchChar = getMatchingSymbol(firstChar)
        var foundAttachedMatch = false
        for (i in start + 1 until line.length) {
            if (line[i] == matchChar) {
                val cNotFollowedBySpace = start + 1 < line.length && !line[start + 1].isWhitespace()
                val mNotPrecededBySpace = i > 0 && !line[i - 1].isWhitespace()
                if (cNotFollowedBySpace && mNotPrecededBySpace) {
                    foundAttachedMatch = true
                    break
                }
            }
        }
        return !foundAttachedMatch
    }

    private fun hasMessyTrailingBoundary(line: CharSequence): Boolean {
        if (line.isEmpty()) return false
        
        var end = line.length
        while (end > 0) {
            val c = line[end - 1]
            if (c.isWhitespace() || c == '-' || c == '—' || c == '–' || c == '‐' || c == '‒' || c == '¬') {
                end--
            } else {
                break
            }
        }
        
        if (end <= 0) return false
        val lastChar = line[end - 1]

        val isEndingPunc = lastChar == '.' || lastChar == '؟' || lastChar == '?' ||
                           lastChar == '!' || lastChar == '،' || lastChar == ',' ||
                           lastChar == ':' || lastChar == '؛' || lastChar == ';' ||
                           lastChar == '…'
        if (isEndingPunc) return false

        if (OPEN_TO_CLOSE.containsKey(lastChar)) return true

        if (!isBoundaryPunctuation(lastChar)) return false

        val matchChar = getMatchingSymbol(lastChar)
        var foundAttachedMatch = false
        for (i in 0 until end - 1) {
            if (line[i] == matchChar) {
                val mNotFollowedBySpace = i + 1 < line.length && !line[i + 1].isWhitespace()
                val cNotPrecededBySpace = end - 2 >= 0 && !line[end - 2].isWhitespace()
                if (mNotFollowedBySpace && cNotPrecededBySpace) {
                    foundAttachedMatch = true
                    break
                }
            }
        }
        return !foundAttachedMatch
    }

    private fun applyVisualSwapping(text: CharSequence): CharSequence {
        val preserveSpans = text is Spanned
        val lines = text.splitByNewlines()
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length + 16)

        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            val line = lines[i].stripDirectionalWrap()

            if (line.isEmpty() || !containsArabic(line)) {
                builder.append(line)
                continue
            }

            val hasCr = line.lastOrNull() == '\r'
            val cleanCore = if (hasCr) line.subSequence(0, line.length - 1) else line

            if (cleanCore.isEmpty()) {
                if (hasCr) builder.append('\r')
                continue
            }

            var preserveStart = 0
            while (preserveStart < cleanCore.length) {
                val c = cleanCore[preserveStart]
                if (c.isWhitespace() || c == '-' || c == '—' || c == '–' || c == '‐' || c == '‒' || c == '¬') {
                    preserveStart++
                } else {
                    break
                }
            }

            var preserveEnd = cleanCore.length
            while (preserveEnd > preserveStart) {
                val c = cleanCore[preserveEnd - 1]
                if (c.isWhitespace() || c == '-' || c == '—' || c == '–' || c == '‐' || c == '‒' || c == '¬') {
                    preserveEnd--
                } else {
                    break
                }
            }

            var start = preserveStart
            while (start < preserveEnd) {
                val c = cleanCore[start]
                if (!isBoundaryPunctuation(c)) break

                if (!c.isWhitespace()) {
                    val m = getMatchingSymbol(c)
                    var isAttachedPair = false
                    for (j in start + 1 until preserveEnd) {
                        if (cleanCore[j] == m) {
                            val cNotFollowedBySpace = start + 1 < cleanCore.length && !cleanCore[start + 1].isWhitespace()
                            val mNotPrecededBySpace = j > 0 && !cleanCore[j - 1].isWhitespace()
                            if (cNotFollowedBySpace && mNotPrecededBySpace) {
                                isAttachedPair = true
                                break
                            }
                        }
                    }
                    if (isAttachedPair) break
                }
                start++
            }

            var end = preserveEnd
            while (end > start) {
                val c = cleanCore[end - 1]
                if (!isBoundaryPunctuation(c)) break

                if (!c.isWhitespace()) {
                    val m = getMatchingSymbol(c)
                    var isAttachedPair = false
                    for (j in preserveStart until end - 1) {
                        if (cleanCore[j] == m) {
                            val mNotFollowedBySpace = j + 1 < cleanCore.length && !cleanCore[j + 1].isWhitespace()
                            val cNotPrecededBySpace = end - 2 >= 0 && !cleanCore[end - 2].isWhitespace()
                            if (mNotFollowedBySpace && cNotPrecededBySpace) {
                                isAttachedPair = true
                                break
                            }
                        }
                    }
                    if (isAttachedPair) break
                }
                end--
            }

            val structuralStart = cleanCore.subSequence(0, preserveStart)
            val structuralEnd = cleanCore.subSequence(preserveEnd, cleanCore.length)
            val misplacedStart = cleanCore.subSequence(preserveStart, start)
            val misplacedEnd = cleanCore.subSequence(end, preserveEnd)
            val middleText = cleanCore.subSequence(start, end)

            builder.append('\u202B')
            for (j in misplacedEnd.indices.reversed()) {
                builder.append(mirrorArabicPunctuation(misplacedEnd[j]))
            }
            builder.append(structuralStart)
            builder.append(pinInteriorNeutralMarks(middleText))
            builder.append(structuralEnd)
            for (j in misplacedStart.indices.reversed()) {
                builder.append(mirrorArabicPunctuation(misplacedStart[j]))
            }
            builder.append('\u202C')

            if (hasCr) builder.append('\r')
        }
        return finishBuilder(builder)
    }

    private fun pinInteriorNeutralMarks(text: CharSequence): CharSequence {
        var found = false
        for (i in text.indices) {
            val ch = text[i]
            if (isBoundaryPunctuation(ch)) {
                found = true
                break
            }
        }
        if (!found) return text

        val sb = StringBuilder(text.length + 16)
        for (i in text.indices) {
            val ch = text[i]
            if (isBoundaryPunctuation(ch)) {
                sb.append('\u200F').append(ch).append('\u200F')
            } else {
                sb.append(ch)
            }
        }
        return sb
    }

    private fun mirrorArabicPunctuation(c: Char): Char = OPEN_TO_CLOSE[c] ?: CLOSE_TO_OPEN[c] ?: c

    private fun wrapArabicLines(text: CharSequence): CharSequence {
        val preserveSpans = text is Spanned
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length + 8)
        val lines = text.splitByNewlines()
        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            val line = lines[i].stripDirectionalWrap()
            if (line.isEmpty()) {
                builder.append(line)
                continue
            }
            val hasCr = line.lastOrNull() == '\r'
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
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length)
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
        is StringBuilder -> builder.toString()
        else -> builder.toString()
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

    private fun mirrorPunctuation(c: Char): Char = OPEN_TO_CLOSE[c] ?: CLOSE_TO_OPEN[c] ?: c

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
        val hasCr = line[line.length - 1] == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var start = 0
        while (start < end0 && isRtlPunctuation(line[start], isEnd = false)) start++

        var end = end0
        while (end > start && isRtlPunctuation(line[end - 1], isEnd = true)) end--

        if (start == 0 && end == end0) return line

        val out: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0)
        appendMirroredReversed(out, line, end, end0)
        out.append(line.subSequence(start, end))
        appendMirroredReversed(out, line, 0, start)
        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    private fun moveLeadingRtlPunctuationToEndForBuiltIn(line: CharSequence, preserveSpans: Boolean): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line[line.length - 1] == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var end = 0
        while (end < end0 && line[end] in MOBILE_RTL_PUNCTUATION) end++
        if (end == 0) return line

        val out: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0)
        out.append(line.subSequence(end, end0)).append(line.subSequence(0, end))
        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    private fun CharSequence.stripDirectionalWrap(): CharSequence {
        val hasMarker = (0 until length).any { isDirectionalMark(this[it]) }
        if (!hasMarker) return this
        if (this !is Spanned) {
            val sb = StringBuilder(length)
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

    private fun containsRtlChars(text: CharSequence): Boolean {
        var i = 0
        while (i < text.length) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint in 0x0590..0x05FF ||
                codePoint in 0xFB1D..0xFB4F ||
                codePoint in 0x0600..0x06FF ||
                codePoint in 0x0750..0x077F ||
                codePoint in 0x0870..0x08FF ||
                codePoint in 0xFB50..0xFDFF ||
                codePoint in 0xFE70..0xFEFF
            ) {
                return true
            }

            val d = Character.getDirectionality(codePoint)
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
                d == Character.DIRECTIONALITY_ARABIC_NUMBER
            ) {
                return true
            }
            i += Character.charCount(codePoint)
        }
        return false
    }

    private fun hasAnyRtlCharacter(text: CharSequence): Boolean {
        var i = 0
        val len = text.length
        while (i < len) {
            val codePoint = Character.codePointAt(text, i)
            if (codePoint >= 0x0590) {
                if (codePoint in 0x0590..0x08FF ||
                    codePoint in 0xFB1D..0xFEFF
                ) {
                    return true
                }
                val d = Character.getDirectionality(codePoint)
                if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                    d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
                    d == Character.DIRECTIONALITY_ARABIC_NUMBER
                ) {
                    return true
                }
            }
            i += Character.charCount(codePoint)
        }
        return false
    }

    private val RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(', '\'', '"') + ('0'..'9')
    private val MOBILE_RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(')
}
