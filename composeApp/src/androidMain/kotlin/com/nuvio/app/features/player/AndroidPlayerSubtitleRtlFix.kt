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
        if (!PlayerSubtitleRtlFix.isRtlEnabled) return cue
        val text = cue.text ?: return cue
        if (!hasAnyRtlCharacter(text)) {
            return cue
        }

        val fixed = fixRtlTextLines(text) ?: return cue
        if (fixed.contentEquals(text)) return cue
        return cue.buildUpon().setText(fixed).build()
    }

    fun fixTimedCues(
        cues: List<CuesWithTiming>,
        isBuiltInSubtitle: Boolean = false
    ): List<CuesWithTiming> {
        if (!PlayerSubtitleRtlFix.isRtlEnabled || cues.isEmpty()) return cues
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

    private fun fixRtlTextLines(text: CharSequence): CharSequence? {
        val preserveSpans = text is Spanned
        val builder: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(text.length + 16)
        val lines = text.splitByNewlines()
        var changed = false

        for (i in lines.indices) {
            if (i > 0) builder.append('\n')
            val rawLine = lines[i].stripDirectionalWrap()
            if (rawLine.isEmpty()) {
                builder.append(rawLine)
                continue
            }
            val hasCr = rawLine[rawLine.length - 1] == '\r'
            val core = if (hasCr) rawLine.subSequence(0, rawLine.length - 1) else rawLine
            if (core.isEmpty()) {
                builder.append(rawLine)
                continue
            }

            val fixed = balanceLinePunctuation(core, preserveSpans)
            if (fixed.toString() != core.toString()) {
                changed = true
            }

            builder.append('\u2067').append('\u200F').append(fixed).append('\u200F').append('\u2069')
            if (hasCr) builder.append('\r')
        }

        if (!changed && text.startsWith("\u2067")) return null
        return finishBuilder(builder)
    }

    private fun finishBuilder(builder: Appendable): CharSequence = when (builder) {
        is SpannableStringBuilder -> builder
        is StringBuilder -> builder.toString()
        else -> builder.toString()
    }

    private fun balanceLinePunctuation(line: CharSequence, preserveSpans: Boolean): CharSequence {
        val len = line.length
        if (len == 0) return line

        var leadPunctEnd = 0
        while (leadPunctEnd < len && isLeadingPunctuation(line[leadPunctEnd])) {
            leadPunctEnd++
        }

        if (leadPunctEnd in 1 until len) {
            val out: Appendable = if (preserveSpans) SpannableStringBuilder() else StringBuilder(len)
            out.append(line.subSequence(leadPunctEnd, len))
            for (idx in 0 until leadPunctEnd) {
                out.append(mirrorPunctuation(line[idx]))
            }
            return finishBuilder(out)
        }

        return line
    }

    private fun isLeadingPunctuation(c: Char): Boolean {
        return c == '.' || c == '?' || c == '!' || c == ':' || c == ';' || c == '…' || c == '-'
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

    private fun isDirectionalMark(c: Char): Boolean =
        c == '\u202A' || c == '\u202B' || c == '\u202C' ||
            c == '\u200E' || c == '\u200F' || c == '\u2067' || c == '\u2069'

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
            if (codePoint in 0x0600..0x06FF ||
                codePoint in 0x0750..0x077F ||
                codePoint in 0x0870..0x08FF ||
                codePoint in 0xFB50..0xFDFF ||
                codePoint in 0xFE70..0xFEFF ||
                codePoint in 0x0590..0x05FF
            ) {
                return true
            }
            i += Character.charCount(codePoint)
        }
        return false
    }
}
