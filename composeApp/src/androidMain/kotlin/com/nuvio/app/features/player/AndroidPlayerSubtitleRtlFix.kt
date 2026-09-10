@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

/**
 * Fixes RTL subtitle rendering without manually reversing Arabic/Hebrew text
 * or punctuation.
 *
 * Main rules:
 *  - Keep subtitle content in logical Unicode order.
 *  - Do NOT manually reverse punctuation.
 *  - Do NOT wrap the whole Arabic line with RLE/RLM.
 *  - Use Unicode isolates for mixed LTR/RTL content.
 *  - Keep Spanned text/spans whenever possible.
 */
internal object AndroidPlayerSubtitleRtlFix {

    fun fixCueText(
        cue: Cue,
        isBuiltInSubtitle: Boolean
    ): Cue {
        val text = cue.text ?: return cue

        if (!containsRtl(text)) {
            return cue
        }

        val fixed = fixText(
            text = text,
            isBuiltInSubtitle = isBuiltInSubtitle
        )

        if (fixed.contentEquals(text)) {
            return cue
        }

        return cue.buildUpon()
            .setText(fixed)
            .build()
    }

    fun fixTimedCues(
        cues: List<CuesWithTiming>,
        isBuiltInSubtitle: Boolean = false
    ): List<CuesWithTiming> {
        if (cues.isEmpty()) {
            return cues
        }

        var anyChanged = false

        val result = ArrayList<CuesWithTiming>(cues.size)

        for (entry in cues) {
            val originalCues = entry.cues
            var modifiedCues: ArrayList<Cue>? = null

            for (index in originalCues.indices) {
                val original = originalCues[index]
                val fixed = fixCueText(
                    cue = original,
                    isBuiltInSubtitle = isBuiltInSubtitle
                )

                if (fixed !== original) {
                    if (modifiedCues == null) {
                        modifiedCues = ArrayList(originalCues.size)

                        for (i in 0 until index) {
                            modifiedCues.add(originalCues[i])
                        }
                    }

                    modifiedCues.add(fixed)
                } else {
                    modifiedCues?.add(original)
                }
            }

            if (modifiedCues != null) {
                anyChanged = true
                result.add(
                    copyTimedCues(
                        entry = entry,
                        cues = modifiedCues
                    )
                )
            } else {
                result.add(entry)
            }
        }

        return if (anyChanged) result else cues
    }

    private fun copyTimedCues(
        entry: CuesWithTiming,
        cues: List<Cue>
    ): CuesWithTiming {
        val durationUs = when {
            entry.durationUs != androidx.media3.common.C.TIME_UNSET -> {
                entry.durationUs
            }

            entry.endTimeUs != androidx.media3.common.C.TIME_UNSET &&
                entry.startTimeUs != androidx.media3.common.C.TIME_UNSET -> {
                (entry.endTimeUs - entry.startTimeUs)
                    .coerceAtLeast(1L)
            }

            else -> {
                5_000_000L
            }
        }

        return CuesWithTiming(
            cues,
            entry.startTimeUs,
            durationUs
        )
    }

    // -------------------------------------------------------------------------
    // Text processing
    // -------------------------------------------------------------------------

    private fun fixText(
        text: CharSequence,
        isBuiltInSubtitle: Boolean
    ): CharSequence {
        val lines = splitLines(text)

        val builder: Appendable =
            if (text is Spanned) {
                SpannableStringBuilder()
            } else {
                StringBuilder(text.length + 16)
            }

        for (index in lines.indices) {
            if (index > 0) {
                builder.append('\n')
            }

            val line = lines[index]

            val fixed = fixLine(
                line = line,
                isBuiltInSubtitle = isBuiltInSubtitle
            )

            builder.append(fixed)
        }

        return finish(builder)
    }

    /**
     * Fix one subtitle line.
     *
     * IMPORTANT:
     * We intentionally do not:
     *
     *  - reverse Arabic
     *  - reverse punctuation
     *  - mirror parentheses
     *  - move ? / ! / .
     *
     * Unicode BiDi is responsible for that.
     */
    private fun fixLine(
        line: CharSequence,
        isBuiltInSubtitle: Boolean
    ): CharSequence {
        if (line.isEmpty()) {
            return line
        }

        val hasCr = line.lastOrNull() == '\r'
        val end = if (hasCr) line.length - 1 else line.length

        if (end <= 0) {
            return line
        }

        val cleanLine = stripOurDirectionalMarks(
            line.subSequence(0, end)
        )

        if (!containsRtl(cleanLine)) {
            return appendCrIfNeeded(
                cleanLine,
                hasCr
            )
        }

        /*
         * Dialogue lines need special treatment.
         *
         * Example:
         *
         * - هل ما رأيته خيال؟
         *
         * Without isolation, the '-' is a neutral character and can
         * participate in the RTL paragraph resolution.
         *
         * We therefore make the dialogue marker LTR and isolate the
         * Arabic body as RTL.
         */
        val dialogue = extractDialoguePrefix(cleanLine)

        val result = if (dialogue != null) {
            fixDialogueLine(
                prefix = dialogue.first,
                body = dialogue.second
            )
        } else {
            /*
             * Normal RTL line.
             *
             * RLI/PDI isolates the Arabic paragraph from surrounding
             * neutral/LTR content without reversing anything.
             */
            isolateRtlLine(cleanLine)
        }

        return appendCrIfNeeded(
            result,
            hasCr
        )
    }

    // -------------------------------------------------------------------------
    // Dialogue
    // -------------------------------------------------------------------------

    private fun extractDialoguePrefix(
        line: CharSequence
    ): Pair<CharSequence, CharSequence>? {
        var index = 0

        while (
            index < line.length &&
            line[index].isWhitespace()
        ) {
            index++
        }

        val prefixStart = 0

        if (
            index >= line.length ||
            (line[index] != '-' && line[index] != '—')
        ) {
            return null
        }

        index++

        /*
         * Support:
         *
         * -
         * - text
         * — text
         * -  text
         */
        while (
            index < line.length &&
            line[index].isWhitespace()
        ) {
            index++
        }

        if (index >= line.length) {
            return Pair(
                line.subSequence(prefixStart, index),
                ""
            )
        }

        return Pair(
            line.subSequence(prefixStart, index),
            line.subSequence(index, line.length)
        )
    }

    private fun fixDialogueLine(
        prefix: CharSequence,
        body: CharSequence
    ): CharSequence {
        val builder =
            if (prefix is Spanned || body is Spanned) {
                SpannableStringBuilder()
            } else {
                StringBuilder(prefix.length + body.length + 8)
            }

        /*
         * LRI
         *
         * The dialogue marker stays in LTR visual order.
         */
        builder.append('\u2066')

        builder.append(prefix)

        /*
         * RLI
         *
         * Arabic/Hebrew body is isolated from the marker.
         */
        if (body.isNotEmpty()) {
            builder.append('\u2067')
            builder.append(body)
            builder.append('\u2069')
        }

        /*
         * PDI for the outer LTR isolate.
         */
        builder.append('\u2069')

        return finish(builder)
    }

    // -------------------------------------------------------------------------
    // RTL isolation
    // -------------------------------------------------------------------------

    private fun isolateRtlLine(
        line: CharSequence
    ): CharSequence {
        /*
         * If the line is already wrapped by an isolate, don't keep
         * stacking directional controls.
         */
        if (
            line.firstOrNull() == '\u2067' &&
            line.lastOrNull() == '\u2069'
        ) {
            return line
        }

        val builder =
            if (line is Spanned) {
                SpannableStringBuilder()
            } else {
                StringBuilder(line.length + 2)
            }

        /*
         * RLI = Right-to-Left Isolate
         *
         * PDI = Pop Directional Isolate
         */
        builder.append('\u2067')
        builder.append(line)
        builder.append('\u2069')

        return finish(builder)
    }

    // -------------------------------------------------------------------------
    // Directional marks
    // -------------------------------------------------------------------------

    /**
     * Remove directional controls that this fixer may have inserted
     * previously.
     *
     * This also prevents:
     *
     * RLM + RLE + text + PDF + RLM
     *
     * from accumulating every time the same cue is processed.
     */
    private fun stripOurDirectionalMarks(
        text: CharSequence
    ): CharSequence {
        var hasMarks = false

        for (i in text.indices) {
            if (isDirectionalControl(text[i])) {
                hasMarks = true
                break
            }
        }

        if (!hasMarks) {
            return text
        }

        if (text is Spanned) {
            val builder = SpannableStringBuilder(text)

            var i = 0

            while (i < builder.length) {
                if (isDirectionalControl(builder[i])) {
                    builder.delete(i, i + 1)
                } else {
                    i++
                }
            }

            return builder
        }

        val builder = StringBuilder(text.length)

        for (i in text.indices) {
            val ch = text[i]

            if (!isDirectionalControl(ch)) {
                builder.append(ch)
            }
        }

        return builder.toString()
    }

    private fun isDirectionalControl(
        c: Char
    ): Boolean {
        return when (c) {
            '\u202A', // LRE
            '\u202B', // RLE
            '\u202C', // PDF
            '\u202D', // LRO
            '\u202E', // RLO
            '\u2066', // LRI
            '\u2067', // RLI
            '\u2068', // FSI
            '\u2069', // PDI
            '\u200E', // LRM
            '\u200F'  // RLM
            -> true

            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // RTL detection
    // -------------------------------------------------------------------------

    private fun containsRtl(
        text: CharSequence
    ): Boolean {
        var index = 0

        while (index < text.length) {
            val codePoint =
                Character.codePointAt(text, index)

            if (isRtlCodePoint(codePoint)) {
                return true
            }

            index += Character.charCount(codePoint)
        }

        return false
    }

    private fun isRtlCodePoint(
        codePoint: Int
    ): Boolean {
        if (
            codePoint in 0x0590..0x05FF ||   // Hebrew
            codePoint in 0x0600..0x06FF ||   // Arabic
            codePoint in 0x0700..0x074F ||
            codePoint in 0x0750..0x077F ||
            codePoint in 0x0780..0x07BF ||
            codePoint in 0x07C0..0x07FF ||
            codePoint in 0x0800..0x083F ||
            codePoint in 0x0840..0x085F ||
            codePoint in 0x0860..0x086F ||
            codePoint in 0x0870..0x089F ||
            codePoint in 0x08A0..0x08FF ||
            codePoint in 0xFB1D..0xFB4F ||   // Hebrew presentation
            codePoint in 0xFB50..0xFDFF ||   // Arabic presentation
            codePoint in 0xFE70..0xFEFF
        ) {
            return true
        }

        return when (
            Character.getDirectionality(codePoint)
        ) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT,
            Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
            Character.DIRECTIONALITY_ARABIC_NUMBER -> true

            else -> false
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun appendCrIfNeeded(
        text: CharSequence,
        hasCr: Boolean
    ): CharSequence {
        if (!hasCr) {
            return text
        }

        val builder =
            if (text is Spanned) {
                SpannableStringBuilder(text)
            } else {
                StringBuilder(text.length + 1)
                    .append(text)
            }

        builder.append('\r')

        return finish(builder)
    }

    private fun splitLines(
        text: CharSequence
    ): List<CharSequence> {
        val result = ArrayList<CharSequence>()

        var start = 0

        for (i in text.indices) {
            if (text[i] == '\n') {
                result.add(
                    text.subSequence(start, i)
                )
                start = i + 1
            }
        }

        result.add(
            text.subSequence(start, text.length)
        )

        return result
    }

    private fun finish(
        builder: Appendable
    ): CharSequence {
        return when (builder) {
            is SpannableStringBuilder -> builder
            is StringBuilder -> builder.toString()
            else -> builder.toString()
        }
    }
}
