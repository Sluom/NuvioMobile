@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

internal object AndroidPlayerSubtitleRtlFix {

    fun fixCueText(cue: Cue, isBuiltInSubtitle: Boolean): Cue {
        val text = cue.text ?: return cue

        if (!hasAnyRtlCharacter(text)) {
            return cue
        }

        if (containsArabic(text)) {
            val isMessy = isMessySubtitle(text, isBuiltInSubtitle)

            val fixed = if (isMessy) {
                applyVisualSwapping(text)
            } else {
                wrapArabicLines(text)
            }

            if (fixed.contentEquals(text)) return cue
            return cue.buildUpon()
                .setText(fixed)
                .build()
        }

        if (containsRtlChars(text)) {
            val fixed = fixHebrewLines(text, isBuiltInSubtitle) ?: return cue

            if (fixed.contentEquals(text)) return cue

            return cue.buildUpon()
                .setText(fixed)
                .build()
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

    private fun copyTimedCues(
        entry: CuesWithTiming,
        cues: List<Cue>
    ): CuesWithTiming {
        val durationUs = when {
            entry.durationUs != C.TIME_UNSET -> entry.durationUs

            entry.endTimeUs != C.TIME_UNSET &&
                entry.startTimeUs != C.TIME_UNSET ->
                (entry.endTimeUs - entry.startTimeUs).coerceAtLeast(1L)

            else -> 5_000_000L
        }

        return CuesWithTiming(
            cues,
            entry.startTimeUs,
            durationUs
        )
    }

    // ------------------------------------------------------------------------
    // Messy subtitle detection
    // ------------------------------------------------------------------------

    private fun isMessySubtitle(
        text: CharSequence,
        isBuiltInSubtitle: Boolean
    ): Boolean {
        if (isBuiltInSubtitle) return false

        val lines = text.splitByNewlines()

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed.isEmpty()) continue

            if (
                hasMessyLeadingBoundary(trimmed) ||
                hasMessyTrailingBoundary(trimmed)
            ) {
                return true
            }
        }

        return false
    }

    private fun hasMessyLeadingBoundary(text: CharSequence): Boolean {
        if (text.isEmpty()) return false

        val firstChar = text.first()

        if (firstChar == '-' || firstChar == '—') {
            return false
        }

        return isBoundaryPunctuation(firstChar)
    }

    private fun hasMessyTrailingBoundary(text: CharSequence): Boolean {
        if (text.isEmpty()) return false

        val lastChar = text.last()

        if (
            lastChar == '.' ||
            lastChar == '؟' ||
            lastChar == '?' ||
            lastChar == '!' ||
            lastChar == '،' ||
            lastChar == ',' ||
            lastChar == '…' ||
            lastChar == ':' ||
            lastChar == ';' ||
            lastChar == '؛'
        ) {
            return false
        }

        return isBoundaryPunctuation(lastChar)
    }

    // ------------------------------------------------------------------------
    // Main Arabic visual fix
    // ------------------------------------------------------------------------

    private fun applyVisualSwapping(
        text: CharSequence
    ): CharSequence {

        val preserveSpans = text is Spanned

        val lines = text.splitByNewlines()

        val builder: Appendable =
            if (preserveSpans) {
                SpannableStringBuilder()
            } else {
                StringBuilder(text.length + 16)
            }

        for (i in lines.indices) {

            if (i > 0) {
                builder.append('\n')
            }

            val line = lines[i].stripDirectionalWrap()

            if (line.isEmpty() || !containsArabic(line)) {
                builder.append(line)
                continue
            }

            val hasCr = line.lastOrNull() == '\r'

            val rawCore =
                if (hasCr) {
                    line.subSequence(0, line.length - 1)
                } else {
                    line
                }

            if (rawCore.isEmpty()) {
                if (hasCr) builder.append('\r')
                continue
            }

            /*
             * IMPORTANT:
             *
             * We first discover protected paired punctuation.
             *
             * Examples:
             *
             * (مارك)
             * [مارك]
             * {مارك}
             * «مارك»
             * “مارك”
             * "مارك"
             *
             * If a pair exists, neither side can become a boundary
             * independently.
             */
            val protected = findProtectedCharacters(rawCore)

            /*
             * Question marks are kept as a separate logical token.
             *
             * This prevents the RTL transformation from creating another
             * visible question mark.
             */
            val cleanCore = StringBuilder(rawCore.length)
            var hasQuestionMark = false

            var k = 0

            while (k < rawCore.length) {

                val ch = rawCore[k]

                if (
                    !protected[k] &&
                    (ch == '؟' || ch == '?')
                ) {
                    hasQuestionMark = true
                } else {
                    cleanCore.append(ch)
                }

                k++
            }

            /*
             * The protected array belongs to rawCore.
             * After removing question marks we need a fresh protection map.
             */
            val cleanProtected =
                findProtectedCharacters(cleanCore)

            var start = 0

            /*
             * Leading punctuation is extracted only when it is NOT part
             * of a protected pair.
             */
            while (start < cleanCore.length) {

                val ch = cleanCore[start]

                if (cleanProtected[start]) {
                    break
                }

                if (!isBoundaryPunctuation(ch)) {
                    break
                }

                /*
                 * Ellipsis at the beginning is legitimate subtitle syntax.
                 *
                 * Do NOT move it through the visual reversal.
                 * Keeping it attached to the beginning prevents:
                 *
                 * … Arabic text
                 *
                 * from becoming:
                 *
                 * …… Arabic text
                 */
                if (ch == '…') {
                    break
                }

                start++
            }

            var end = cleanCore.length

            /*
             * Trailing punctuation.
             */
            while (end > start) {

                val index = end - 1
                val ch = cleanCore[index]

                if (cleanProtected[index]) {
                    break
                }

                if (!isBoundaryPunctuation(ch)) {
                    break
                }

                /*
                 * Ellipsis is kept where it originally occurs.
                 */
                if (ch == '…') {
                    break
                }

                end--
            }

            /*
             * Additional protection:
             *
             * If a bracket pair happens to cross the calculated boundary,
             * restore it to the middle rather than splitting it.
             */
            val repairedBounds =
                repairProtectedBoundary(
                    cleanCore,
                    cleanProtected,
                    start,
                    end
                )

            start = repairedBounds.first
            end = repairedBounds.second

            if (start >= end) {

                builder.append(cleanCore)

                if (hasQuestionMark) {
                    builder.append('؟')
                }

                if (hasCr) {
                    builder.append('\r')
                }

                continue
            }

            val leadingPunc =
                cleanCore.subSequence(0, start)

            val trailingPunc =
                cleanCore.subSequence(end, cleanCore.length)

            val middleText =
                cleanCore.subSequence(start, end)

            /*
             * Trailing punctuation is visually reversed to the beginning.
             *
             * Protected pairs never reach this section because their
             * boundaries stop the extraction.
             */
            appendMirroredReversed(
                builder,
                trailingPunc,
                0,
                trailingPunc.length
            )

            /*
             * The actual Arabic text is explicitly RTL.
             */
            builder
                .append('\u202B')
                .append(middleText)
                .append('\u202C')

            /*
             * Leading punctuation is visually moved to the end.
             */
            appendMirroredReversed(
                builder,
                leadingPunc,
                0,
                leadingPunc.length
            )

            if (hasQuestionMark) {
                builder.append('؟')
            }

            if (hasCr) {
                builder.append('\r')
            }
        }

        return finishBuilder(builder)
    }

    // ------------------------------------------------------------------------
    // Protected punctuation pairs
    // ------------------------------------------------------------------------

    /**
     * Marks every character that belongs to a valid paired punctuation group.
     *
     * Protected pairs:
     *
     * ( ... )
     * [ ... ]
     * { ... }
     * « ... »
     * “ ... ”
     * " ... "
     *
     * A single/unmatched bracket is NOT protected.
     */
    private fun findProtectedCharacters(
        text: CharSequence
    ): BooleanArray {

        val protected = BooleanArray(text.length)

        if (text.isEmpty()) return protected

        val stack = ArrayDeque<Pair<Char, Int>>()

        var i = 0

        while (i < text.length) {

            val ch = text[i]

            when (ch) {

                '(',
                '[',
                '{',
                '«',
                '“' -> {
                    stack.addLast(ch to i)
                }

                ')',
                ']',
                '}',
                '»',
                '”' -> {

                    val expectedOpen =
                        matchingOpening(ch)

                    /*
                     * Search backward for the most recent matching opening
                     * delimiter.
                     */
                    var foundIndex = -1

                    for (s in stack.size - 1 downTo 0) {
                        val item = stack.elementAt(s)

                        if (item.first == expectedOpen) {
                            foundIndex = s
                            break
                        }
                    }

                    if (foundIndex >= 0) {

                        val opening =
                            stack.elementAt(foundIndex)

                        /*
                         * Remove the matched opening and anything nested
                         * above it from the active stack.
                         */
                        while (stack.size > foundIndex) {
                            stack.removeLast()
                        }

                        for (p in opening.second..i) {
                            protected[p] = true
                        }
                    }
                }

                '"' -> {
                    /*
                     * Straight quotes are special because the same character
                     * acts as opening and closing quote.
                     *
                     * Find the nearest unmatched quote.
                     */
                    var openingIndex = -1

                    for (s in stack.size - 1 downTo 0) {
                        val item = stack.elementAt(s)

                        if (item.first == '"') {
                            openingIndex = s
                            break
                        }
                    }

                    if (openingIndex >= 0) {

                        val opening =
                            stack.elementAt(openingIndex)

                        while (stack.size > openingIndex) {
                            stack.removeLast()
                        }

                        for (p in opening.second..i) {
                            protected[p] = true
                        }

                    } else {
                        stack.addLast('"' to i)
                    }
                }
            }

            i++
        }

        return protected
    }

    private fun matchingOpening(
        closing: Char
    ): Char = when (closing) {
        ')' -> '('
        ']' -> '['
        '}' -> '{'
        '»' -> '«'
        '”' -> '“'
        else -> closing
    }

    /**
     * If boundary extraction accidentally reaches across a protected pair,
     * move the boundary outside that pair.
     */
    private fun repairProtectedBoundary(
        text: CharSequence,
        protected: BooleanArray,
        initialStart: Int,
        initialEnd: Int
    ): Pair<Int, Int> {

        var start = initialStart
        var end = initialEnd

        if (start >= end) {
            return start to end
        }

        /*
         * If start is directly before a protected region, do not extract
         * half of that region.
         */
        if (start < protected.size && protected[start]) {
            start = findProtectedStart(
                protected,
                start
            )
        }

        /*
         * If end cuts through a protected region, move it backward to the
         * beginning of that protected region.
         */
        if (
            end > 0 &&
            end - 1 < protected.size &&
            protected[end - 1]
        ) {
            end = findProtectedStart(
                protected,
                end - 1
            )
        }

        return start to end
    }

    private fun findProtectedStart(
        protected: BooleanArray,
        index: Int
    ): Int {

        var i = index

        while (i > 0 && protected[i - 1]) {
            i--
        }

        return i
    }

    // ------------------------------------------------------------------------
    // Boundary punctuation
    // ------------------------------------------------------------------------

    private fun isBoundaryPunctuation(
        c: Char
    ): Boolean {
        return c == '"' ||
            c == '\'' ||
            c == '«' ||
            c == '»' ||
            c == '“' ||
            c == '”' ||
            c == '!' ||
            c == '؟' ||
            c == '?' ||
            c == '-' ||
            c == '—' ||
            c == '(' ||
            c == ')' ||
            c == '[' ||
            c == ']' ||
            c == '{' ||
            c == '}' ||
            c == '.' ||
            c == ',' ||
            c == '،' ||
            c == ':' ||
            c == ';' ||
            c == '؛' ||
            c == '…' ||
            c.isWhitespace()
    }

    private fun mirrorArabicPunctuation(
        c: Char
    ): Char = when (c) {
        '(' -> ')'
        ')' -> '('
        '[' -> ']'
        ']' -> '['
        '{' -> '}'
        '}' -> '{'
        '«' -> '»'
        '»' -> '«'
        '“' -> '”'
        '”' -> '“'
        else -> c
    }

    // ------------------------------------------------------------------------
    // Arabic normal / sane subtitles
    // ------------------------------------------------------------------------

    private fun wrapArabicLines(
        text: CharSequence
    ): CharSequence {

        val preserveSpans = text is Spanned

        val builder: Appendable =
            if (preserveSpans) {
                SpannableStringBuilder()
            } else {
                StringBuilder(text.length + 8)
            }

        val lines = text.splitByNewlines()

        for (i in lines.indices) {

            if (i > 0) {
                builder.append('\n')
            }

            val line =
                lines[i].stripDirectionalWrap()

            if (line.isEmpty()) {
                builder.append(line)
                continue
            }

            val hasCr =
                line.lastOrNull() == '\r'

            val core =
                if (hasCr) {
                    line.subSequence(
                        0,
                        line.length - 1
                    )
                } else {
                    line
                }

            if (core.isEmpty()) {
                builder.append(line)
                continue
            }

            /*
             * Do not let a paired punctuation mark become isolated from
             * its content.
             *
             * The LRM markers around the complete RTL run stabilize
             * punctuation such as:
             *
             * (مارك)
             * "مارك"
             * «مارك»
             *
             * without changing the actual subtitle characters.
             */
            builder
                .append('\u200F')
                .append('\u202B')
                .append(core)
                .append('\u202C')
                .append('\u200F')

            if (hasCr) {
                builder.append('\r')
            }
        }

        return finishBuilder(builder)
    }

    // ------------------------------------------------------------------------
    // Hebrew / generic RTL
    // ------------------------------------------------------------------------

    private fun fixHebrewLines(
        text: CharSequence,
        isBuiltInSubtitle: Boolean
    ): CharSequence? {

        val preserveSpans = text is Spanned

        val builder: Appendable =
            if (preserveSpans) {
                SpannableStringBuilder()
            } else {
                StringBuilder(text.length)
            }

        val lines = text.splitByNewlines()

        var changed = false

        for (i in lines.indices) {

            if (i > 0) {
                builder.append('\n')
            }

            val line = lines[i]

            val fixed =
                if (isBuiltInSubtitle) {
                    moveLeadingRtlPunctuationToEndForBuiltIn(
                        line,
                        preserveSpans
                    )
                } else {
                    fixRtlPunctuationForLtr(
                        line,
                        preserveSpans
                    )
                }

            if (
                fixed !== line &&
                fixed.toString() != line.toString()
            ) {
                changed = true
            }

            builder.append(fixed)
        }

        if (!changed) return null

        return finishBuilder(builder)
    }

    private fun finishBuilder(
        builder: Appendable
    ): CharSequence =
        when (builder) {
            is SpannableStringBuilder -> builder
            is StringBuilder -> builder.toString()
            else -> builder.toString()
        }

    // ------------------------------------------------------------------------
    // RTL punctuation for Hebrew / non-Arabic RTL
    // ------------------------------------------------------------------------

    private fun appendMirroredReversed(
        out: Appendable,
        line: CharSequence,
        from: Int,
        toExclusive: Int
    ) {
        if (from >= toExclusive) return

        /*
         * Numbers are atomic.
         *
         * Examples:
         *
         * 10:30
         * 1.5
         * 10,000
         * 12-14
         *
         * should never be reversed character-by-character.
         */
        fun isNumberSeparator(
            c: Char
        ): Boolean {
            return c == ',' ||
                c == ':' ||
                c == '.' ||
                c == '-'
        }

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

                /*
                 * Ellipsis is one logical punctuation token.
                 */
                if (
                    line[i] == '…'
                ) {
                    chunks.add(i until i + 1)
                    i++
                    continue
                }

                chunks.add(i until i + 1)
                i++
            }
        }

        for (idx in chunks.indices.reversed()) {

            val range = chunks[idx]

            if (
                range.last - range.first + 1 > 1
            ) {
                out.append(
                    line.subSequence(
                        range.first,
                        range.last + 1
                    )
                )
            } else {

                val c = line[range.first]

                val mirrored =
                    mirrorArabicPunctuation(c)

                if (mirrored != c) {
                    out.append(mirrored)
                } else {
                    out.append(
                        line.subSequence(
                            range.first,
                            range.first + 1
                        )
                    )
                }
            }
        }
    }

    private fun fixRtlPunctuationForLtr(
        line: CharSequence,
        preserveSpans: Boolean
    ): CharSequence {

        if (line.isEmpty()) return line

        val hasCr =
            line[line.length - 1] == '\r'

        val end0 =
            if (hasCr) {
                line.length - 1
            } else {
                line.length
            }

        if (end0 == 0) return line

        val protected =
            findProtectedCharacters(
                line.subSequence(0, end0)
            )

        var start = 0

        while (start < end0) {

            if (protected[start]) break

            if (
                !isRtlPunctuation(
                    line[start],
                    isEnd = false
                )
            ) {
                break
            }

            /*
             * Do not extract ellipsis from a legitimate beginning.
             */
            if (line[start] == '…') break

            start++
        }

        var end = end0

        while (end > start) {

            val index = end - 1

            if (protected[index]) break

            if (
                !isRtlPunctuation(
                    line[index],
                    isEnd = true
                )
            ) {
                break
            }

            if (line[index] == '…') break

            end--
        }

        val repaired =
            repairProtectedBoundary(
                line,
                protected,
                start,
                end
            )

        start = repaired.first
        end = repaired.second

        if (
            start == 0 &&
            end == end0
        ) {
            return line
        }

        val out: Appendable =
            if (preserveSpans) {
                SpannableStringBuilder()
            } else {
                StringBuilder(end0)
            }

        appendMirroredReversed(
            out,
            line,
            end,
            end0
        )

        out.append(
            line.subSequence(
                start,
                end
            )
        )

        appendMirroredReversed(
            out,
            line,
            0,
            start
        )

        if (hasCr) {
            out.append('\r')
        }

        return finishBuilder(out)
    }

    private fun moveLeadingRtlPunctuationToEndForBuiltIn(
        line: CharSequence,
        preserveSpans: Boolean
    ): CharSequence {

        if (line.isEmpty()) return line

        val hasCr =
            line[line.length - 1] == '\r'

        val end0 =
            if (hasCr) {
                line.length - 1
            } else {
                line.length
            }

        if (end0 == 0) return line

        var end = 0

        while (
            end < end0 &&
            line[end] in MOBILE_RTL_PUNCTUATION
        ) {
            /*
             * Ellipsis at the beginning is valid.
             * Do not relocate it.
             */
            if (line[end] == '…') break

            end++
        }

        if (end == 0) return line

        val out: Appendable =
            if (preserveSpans) {
                SpannableStringBuilder()
            } else {
                StringBuilder(end0)
            }

        out.append(
            line.subSequence(
                end,
                end0
            )
        )

        out.append(
            line.subSequence(
                0,
                end
            )
        )

        if (hasCr) {
            out.append('\r')
        }

        return finishBuilder(out)
    }

    // ------------------------------------------------------------------------
    // Directional marks
    // ------------------------------------------------------------------------

    private fun CharSequence.stripDirectionalWrap():
        CharSequence {

        val hasMarker =
            (0 until length)
                .any {
                    isDirectionalMark(this[it])
                }

        if (!hasMarker) return this

        if (this !is Spanned) {

            val sb =
                StringBuilder(length)

            for (ch in this) {

                if (!isDirectionalMark(ch)) {
                    sb.append(ch)
                }
            }

            return sb.toString()
        }

        val sb =
            SpannableStringBuilder(this)

        var k = 0

        while (k < sb.length) {

            if (isDirectionalMark(sb[k])) {
                sb.delete(k, k + 1)
            } else {
                k++
            }
        }

        return sb
    }

    private fun isDirectionalMark(
        c: Char
    ): Boolean {
        return c == '\u202A' ||
            c == '\u202B' ||
            c == '\u202C' ||
            c == '\u200E' ||
            c == '\u200F'
    }

    // ------------------------------------------------------------------------
    // Line splitting
    // ------------------------------------------------------------------------

    private fun CharSequence.splitByNewlines():
        List<CharSequence> {

        val result =
            mutableListOf<CharSequence>()

        var start = 0
        var i = 0

        while (i < length) {

            if (this[i] == '\n') {

                result.add(
                    subSequence(
                        start,
                        i
                    )
                )

                start = i + 1
            }

            i++
        }

        result.add(
            subSequence(
                start,
                length
            )
        )

        return result
    }

    // ------------------------------------------------------------------------
    // RTL punctuation
    // ------------------------------------------------------------------------

    private fun isRtlPunctuation(
        ch: Char,
        isEnd: Boolean
    ): Boolean {

        /*
         * Digits are not punctuation.
         */
        if (ch.isDigit()) {
            return false
        }

        /*
         * Semicolons and colons are legitimate boundary characters,
         * but only when actually located at a boundary.
         *
         * Internal:
         *
         * الاسم:جين
         * جين; قالت
         *
         * never reach this function because boundary scanning stops
         * as soon as ordinary text is encountered.
         */
        return ch in RTL_PUNCTUATION ||
            ch.isWhitespace()
    }

    // ------------------------------------------------------------------------
    // Character detection
    // ------------------------------------------------------------------------

    private fun containsArabic(
        text: CharSequence
    ): Boolean {

        var i = 0

        while (i < text.length) {

            val codePoint =
                Character.codePointAt(
                    text,
                    i
                )

            if (
                codePoint in 0x0600..0x06FF ||
                codePoint in 0x0750..0x077F ||
                codePoint in 0x0870..0x08FF ||
                codePoint in 0xFB50..0xFDFF ||
                codePoint in 0xFE70..0xFEFF ||
                Character.getDirectionality(codePoint) ==
                Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
            ) {
                return true
            }

            i += Character.charCount(codePoint)
        }

        return false
    }

    private fun containsRtlChars(
        text: CharSequence
    ): Boolean {

        var i = 0

        while (i < text.length) {

            val codePoint =
                Character.codePointAt(
                    text,
                    i
                )

            if (
                codePoint in 0x0590..0x05FF ||
                codePoint in 0xFB1D..0xFB4F ||
                codePoint in 0x0600..0x06FF ||
                codePoint in 0x0750..0x077F ||
                codePoint in 0x0870..0x08FF ||
                codePoint in 0xFB50..0xFDFF ||
                codePoint in 0xFE70..0xFEFF
            ) {
                return true
            }

            val d =
                Character.getDirectionality(
                    codePoint
                )

            if (
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC ||
                d == Character.DIRECTIONALITY_ARABIC_NUMBER
            ) {
                return true
            }

            i += Character.charCount(codePoint)
        }

        return false
    }

    private fun hasAnyRtlCharacter(
        text: CharSequence
    ): Boolean {

        var i = 0

        val len = text.length

        while (i < len) {

            val codePoint =
                Character.codePointAt(
                    text,
                    i
                )

            if (codePoint >= 0x0590) {

                if (
                    codePoint in 0x0590..0x08FF ||
                    codePoint in 0xFB1D..0xFEFF
                ) {
                    return true
                }

                val d =
                    Character.getDirectionality(
                        codePoint
                    )

                if (
                    d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
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

    // ------------------------------------------------------------------------
    // Sets
    // ------------------------------------------------------------------------

    /*
     * IMPORTANT:
     *
     * Digits have deliberately been removed.
     *
     * Also:
     * ; and ؛ are supported on BOTH boundaries.
     * : is supported on boundaries but remains untouched in the middle.
     */
    private val RTL_PUNCTUATION =
        setOf(
            '.',
            ',',
            '،',
            '?',
            '؟',
            '!',
            '-',
            '—',
            ':',
            ';',
            '؛',
            '…',
            ')',
            '(',
            ']',
            '[',
            '}',
            '{',
            '\'',
            '"',
            '«',
            '»',
            '“',
            '”'
        )

    private val MOBILE_RTL_PUNCTUATION =
        setOf(
            '.',
            ',',
            '،',
            '?',
            '؟',
            '!',
            '-',
            '—',
            ':',
            ';',
            '؛',
            '…',
            ')',
            '(',
            ']',
            '[',
            '}',
            '{',
            '«',
            '»',
            '“',
            '”'
        )
}
