@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

internal object AndroidPlayerSubtitleRtlFix {

    // Remembers whether the last cue *with an actual letter* in it was Arabic.
    // Used to infer direction for symbol-only cues (e.g. "* * *" or "» «" scene
    // breaks) that carry no strong character of their own and would otherwise
    // default to LTR. Call resetState() when the player switches media item or
    // subtitle track so context doesn't leak between unrelated videos.
    @Volatile
    private var lastKnownArabicContext: Boolean = false

    fun resetState() {
        lastKnownArabicContext = false
    }

    /**
     * Fixes a single cue's text.
     *
     * @param forceArabic When non-null, overrides self-detection and treats the
     *   cue as Arabic (true) or as "leave to normal detection" (null). Used by
     *   [fixTimedCues] to propagate an entry-wide Arabic context onto sibling
     *   cues that have no letters of their own. Direct callers can leave this
     *   as null to get the original self-contained behavior.
     */
    fun fixCueText(cue: Cue, isBuiltInSubtitle: Boolean, forceArabic: Boolean? = null): Cue {
        val text = cue.text ?: return cue
        if (!hasAnyRtlCharacter(text) && forceArabic != true) {
            return cue
        }

        val isArabic = forceArabic ?: containsArabic(text)
        if (isArabic) {
            val fixed = wrapArabicLines(text)
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
            if (entryCues.isEmpty()) {
                out.add(entry)
                continue
            }

            // Determine Arabic context for the WHOLE entry (all cue objects that
            // are simultaneously visible), not each Cue individually. Some
            // subtitle formats (ASS/SSA, dual-language tracks) split one visual
            // block into multiple Cue objects — e.g. one per line — and a given
            // line may contain nothing but digits, a Latin name, or punctuation
            // like "*" / "„" / "»«". Those letterless lines get no vote of their
            // own; they inherit the entry's (or, failing that, the last known)
            // direction instead of silently defaulting to LTR.
            val combinedText = StringBuilder()
            for (c in entryCues) {
                c.text?.let { combinedText.append(it).append('\n') }
            }
            val entryHasLetter = combinedText.any { Character.isLetter(it) }
            val entryIsArabicContext = if (entryHasLetter) {
                containsArabic(combinedText).also { lastKnownArabicContext = it }
            } else {
                lastKnownArabicContext
            }

            var modified: ArrayList<Cue>? = null
            for (i in entryCues.indices) {
                val original = entryCues[i]
                val ownHasLetter = original.text?.any { Character.isLetter(it) } == true
                // Only letterless cues (pure symbols/digits) get force-wrapped
                // from entry context; cues with their own letters keep the
                // original self-contained detection, so mixed bilingual blocks
                // aren't force-flipped just because a sibling line is Arabic.
                val forceArabic: Boolean? =
                    if (!ownHasLetter && entryIsArabicContext) true else null
                val fixed = fixCueText(original, isBuiltInSubtitle, forceArabic)
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

    // Uses RLI (U+2067) / PDI (U+2069) — Unicode Bidi *isolates* — instead of
    // the older RLE (U+202B) / PDF (U+202C) *embedding* characters. Isolates
    // are the modern, more robust way to force a paragraph's base direction:
    // they don't let neighboring strong characters "leak" into the wrapped
    // run, which is what caused lines starting with a digit, a Latin name, or
    // punctuation to sometimes render LTR despite containing Arabic text.
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
            val hasCr = line[line.length - 1] == '\r'
            val core = if (hasCr) line.subSequence(0, line.length - 1) else line
            if (core.isEmpty()) {
                builder.append(line)
                continue
            }
            builder.append('\u2067').append(core).append('\u2069')
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

    private fun mirrorPunctuation(c: Char): Char = when (c) {
        '(' -> ')'
        ')' -> '('
        else -> c
    }

    private fun appendMirroredReversed(
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

        val out: Appendable =
            if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0)
        appendMirroredReversed(out, line, end, end0)
        out.append(line.subSequence(start, end))
        appendMirroredReversed(out, line, 0, start)
        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    private fun moveLeadingRtlPunctuationToEndForBuiltIn(
        line: CharSequence,
        preserveSpans: Boolean
    ): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line[line.length - 1] == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var end = 0
        while (end < end0 && line[end] in MOBILE_RTL_PUNCTUATION) end++
        if (end == 0) return line

        val out: Appendable =
            if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0)
        out.append(line.subSequence(end, end0))
            .append(line.subSequence(0, end))
        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    private fun CharSequence.stripDirectionalWrap(): CharSequence {
        val hasMarker = (0 until length).any { isDirectionalMark(this[it]) }
        if (!hasMarker) return this
        if (this !is Spanned) {
            val sb = StringBuilder(length)
            for (ch in this) {
                if (!isDirectionalMark(ch)) sb.append(ch)
            }
            return sb.toString()
        }
        val sb = SpannableStringBuilder(this)
        var k = 0
        while (k < sb.length) {
            if (isDirectionalMark(sb[k])) sb.delete(k, k + 1) else k++
        }
        return sb
    }

    // Strips both the legacy embedding marks (LRE/RLE/PDF, LRM/RLM) AND the
    // modern isolate marks (LRI/RLI/FSI/PDI) so re-processing an already-fixed
    // cue (e.g. if the pipeline runs twice) never double-wraps it.
    private fun isDirectionalMark(c: Char): Boolean =
        c == '\u202A' || c == '\u202B' || c == '\u202C' ||
            c == '\u200E' || c == '\u200F' ||
            c == '\u2066' || c == '\u2067' || c == '\u2068' || c == '\u2069'

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
