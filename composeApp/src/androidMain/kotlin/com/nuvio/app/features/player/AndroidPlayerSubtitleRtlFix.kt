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
            val fixed = fixArabicLines(text, isBuiltInSubtitle) ?: return cue
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

    // ============================================================
    // Arabic-only punctuation fix path.
    //
    // Deliberately fully DUPLICATED (not shared) from the Hebrew helpers
    // further below. This is intentional: Arabic subtitle sources (esp.
    // fan/community translations) show a much wider and messier variety of
    // leading/trailing neutral punctuation than Hebrew ever does — Arabic
    // punctuation marks (؟ ، ؛), plain Latin punctuation left in by the
    // translator, decorative bracket styles («» / ﴿﴾ / 「」 / 『』 / 【】),
    // and even CJK/Devanagari punctuation that shows up when a translator
    // copy-pasted from another localized track. Keeping this path fully
    // separate means any future tuning for Arabic-specific noise can NEVER
    // regress Hebrew rendering, and vice versa.
    // ============================================================

    private fun fixArabicLines(text: CharSequence, isBuiltInSubtitle: Boolean): CharSequence? {
        val preserveSpans = text is Spanned
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length)
        val lines = text.splitByNewlines()
        var changed = false
        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            val stripped = lines[i].stripDirectionalWrap()
            if (stripped.toString() != lines[i].toString()) changed = true
            val fixed = if (isBuiltInSubtitle) {
                moveLeadingArabicPunctuationToEndForBuiltIn(stripped, preserveSpans)
            } else {
                fixArabicLeadingTrailingPunctuationForLtr(stripped, preserveSpans)
            }
            if (fixed !== stripped && fixed.toString() != stripped.toString()) changed = true
            builder.append(fixed)
        }
        if (!changed) return null
        return finishBuilder(builder)
    }

    private fun fixArabicLeadingTrailingPunctuationForLtr(line: CharSequence, preserveSpans: Boolean): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line[line.length - 1] == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var start = 0
        while (start < end0 && isArabicRtlPunctuation(line[start], isEnd = false)) start++

        var end = end0
        while (end > start && isArabicRtlPunctuation(line[end - 1], isEnd = true)) end--

        if (start == 0 && end == end0) return line

        val out: Appendable =
            if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0)
        appendMirroredReversedArabic(out, line, end, end0)
        out.append(line.subSequence(start, end))
        appendMirroredReversedArabic(out, line, 0, start)
        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    private fun moveLeadingArabicPunctuationToEndForBuiltIn(
        line: CharSequence,
        preserveSpans: Boolean
    ): CharSequence {
        if (line.isEmpty()) return line
        val hasCr = line[line.length - 1] == '\r'
        val end0 = if (hasCr) line.length - 1 else line.length
        if (end0 == 0) return line

        var end = 0
        while (end < end0 && line[end] in ARABIC_MOBILE_RTL_PUNCTUATION) end++
        if (end == 0) return line

        val out: Appendable =
            if (preserveSpans) SpannableStringBuilder() else StringBuilder(end0)
        out.append(line.subSequence(end, end0))
            .append(line.subSequence(0, end))
        if (hasCr) out.append('\r')
        return finishBuilder(out)
    }

    // Mirrors bracket-style punctuation when it gets moved across the line
    // (a "(" that was trailing becomes a leading ")" and vice-versa, etc).
    // Covers ASCII, Arabic ornate parens, angle/guillemet quotes, and the
    // common CJK bracket styles some translators paste in.
    private fun mirrorPunctuationArabic(c: Char): Char = when (c) {
        '(' -> ')'
        ')' -> '('
        '[' -> ']'
        ']' -> '['
        '{' -> '}'
        '}' -> '{'
        '<' -> '>'
        '>' -> '<'
        '«' -> '»'
        '»' -> '«'
        '﴿' -> '﴾'
        '﴾' -> '﴿'
        '「' -> '」'
        '」' -> '「'
        '『' -> '』'
        '』' -> '『'
        '【' -> '】'
        '】' -> '【'
        '（' -> '）'
        '）' -> '（'
        '〈' -> '〉'
        '〉' -> '〈'
        '《' -> '》'
        '》' -> '《'
        else -> c
    }

    private fun appendMirroredReversedArabic(
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
            if (isArabicDigit(line[i])) {
                val start = i
                i++
                while (i < toExclusive) {
                    if (isArabicDigit(line[i])) {
                        i++
                    } else if (
                        isNumberSeparator(line[i]) &&
                        i + 1 < toExclusive &&
                        isArabicDigit(line[i + 1])
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
                val m = mirrorPunctuationArabic(c)
                if (m != c) out.append(m) else out.append(line.subSequence(range.first, range.first + 1))
            }
        }
    }

    private fun isArabicDigit(c: Char): Boolean {
        if (c.isDigit()) return true
        val code = c.code
        // Arabic-Indic digits (٠-٩) and Extended Arabic-Indic / Persian digits (۰-۹)
        return code in 0x0660..0x0669 || code in 0x06F0..0x06F9
    }

    private fun isArabicRtlPunctuation(ch: Char, isEnd: Boolean): Boolean {
        if (isEnd && isArabicDigit(ch)) return false
        return ch in ARABIC_RTL_PUNCTUATION || ch.isWhitespace()
    }

    // Broad, intentionally generous set of "neutral" leading/trailing marks
    // that can show up in Arabic community subtitle tracks:
    //  - Arabic-native punctuation: ؟ ، ؛ ٪ ـ ٫ ٬ ۔
    //  - Ornate Arabic quote/parens: ﴿ ﴾
    //  - Plain ASCII punctuation translators often leave in: . , ? ! - : ; … ' " * # = ^ ~ + | \ / _ @ & %
    //  - Bracket family (ASCII + CJK + guillemets), used as dialogue/scene markers: ( ) [ ] { } < > « » 「 」 『 』 【 】 （ ） 〈 〉 《 》
    //  - CJK punctuation occasionally pasted in from other localized tracks: 。 、 ， ！ ？ ： ； “ ” ‘ ’ ・ ～
    //  - Devanagari/Hindi danda marks: । ॥
    private val ARABIC_RTL_PUNCTUATION = setOf(
        // ASCII punctuation
        '.', ',', '?', '!', '-', ':', ';', '…', ')', '(', '\'', '"', '*',
        '{', '}', '[', ']', '<', '>', '^', '=', '#', '@', '&', '%', '+', '~', '|', '\\', '/', '_',
        // Arabic-native punctuation
        '؟', '،', '؛', '٪', 'ـ', '٫', '٬', '۔',
        // Ornate Arabic parens / guillemets
        '﴿', '﴾', '«', '»',
        // CJK-style brackets
        '「', '」', '『', '』', '【', '】', '（', '）', '〈', '〉', '《', '》',
        // CJK punctuation
        '。', '、', '，', '！', '？', '：', '；', '“', '”', '‘', '’', '・', '～',
        // Devanagari danda
        '।', '॥'
    ) + ('0'..'9')

    // Slightly narrower set used for the built-in-subtitle "move leading
    // marks to the end" pass (mirrors the scope of the shared Hebrew
    // MOBILE_RTL_PUNCTUATION set, but expanded the same way as above).
    private val ARABIC_MOBILE_RTL_PUNCTUATION = setOf(
        '.', ',', '?', '!', '-', ':', ';', '…', ')', '(', '*',
        '{', '}', '[', ']', '<', '>', '^', '#', '@', '&', '%', '+', '~', '|', '/', '_',
        '؟', '،', '؛', '٪', 'ـ', '٫', '٬', '۔',
        '﴿', '﴾', '«', '»',
        '「', '」', '『', '』', '【', '】', '（', '）', '〈', '〉', '《', '》',
        '。', '、', '，', '！', '？', '：', '；', '“', '”', '‘', '’', '・', '～',
        '।', '॥'
    )

    // ============================================================
    // Hebrew path — UNCHANGED. Do not modify this section or anything
    // it depends on (fixRtlPunctuationForLtr, moveLeadingRtlPunctuationToEndForBuiltIn,
    // mirrorPunctuation, appendMirroredReversed, isRtlPunctuation,
    // RTL_PUNCTUATION, MOBILE_RTL_PUNCTUATION).
    // ============================================================

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

    private val RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(', '\'', '"', '*') + ('0'..'9')
    private val MOBILE_RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(', '*')
}
