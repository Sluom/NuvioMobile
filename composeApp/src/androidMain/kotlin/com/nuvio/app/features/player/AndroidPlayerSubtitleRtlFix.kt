@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

private const val DISABLE_LEGACY_ARABIC_RTL_WRAP = true

internal object AndroidPlayerSubtitleRtlFix {

    fun fixCueText(cue: Cue, isBuiltInSubtitle: Boolean): Cue {
        val text = cue.text ?: return cue
        if (!hasAnyRtlCharacter(text)) {
            return cue
        }

        if (containsArabic(text)) {
            if (DISABLE_LEGACY_ARABIC_RTL_WRAP) {
                return cue
            }

            val fixed = wrapArabicLines(text)
            if (fixed.contentEquals(text)) {
                return cue
            }

            return cue.buildUpon()
                .setText(fixed)
                .build()
        }

        if (containsRtlChars(text)) {
            val fixed = fixHebrewLines(
                text,
                isBuiltInSubtitle
            ) ?: return cue

            if (fixed.contentEquals(text)) {
                return cue
            }

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
        if (cues.isEmpty()) {
            return cues
        }

        var anyChanged = false
        val out = ArrayList<CuesWithTiming>(cues.size)

        for (entry in cues) {
            val entryCues = entry.cues
            var modified: ArrayList<Cue>? = null

            for (i in entryCues.indices) {
                val original = entryCues[i]
                val fixed = fixCueText(
                    original,
                    isBuiltInSubtitle
                )

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
                out.add(
                    copyTimedCues(
                        entry,
                        modified
                    )
                )
            } else {
                out.add(entry)
            }
        }

        return if (anyChanged) {
            out
        } else {
            cues
        }
    }

    private fun copyTimedCues(
        entry: CuesWithTiming,
        cues: List<Cue>
    ): CuesWithTiming {
        val durationUs = when {
            entry.durationUs != C.TIME_UNSET -> {
                entry.durationUs
            }

            entry.endTimeUs != C.TIME_UNSET &&
                entry.startTimeUs != C.TIME_UNSET -> {
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

            val line = lines[i].stripDirectionalWrap()

            if (line.isEmpty()) {
                builder.append(line)
                continue
            }

            val hasCr = line[line.length - 1] == '\r'

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

            builder
                .append('\u202B')
                .append(core)
                .append('\u202C')

            if (hasCr) {
                builder.append('\r')
            }
        }

        return finishBuilder(builder)
    }

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

        if (!changed) {
            return null
        }

        return finishBuilder(builder)
    }

    private fun finishBuilder(
        builder: Appendable
    ): CharSequence {
        return when (builder) {
            is SpannableStringBuilder -> builder
            is StringBuilder -> builder.toString()
            else -> builder.toString()
        }
    }

    private fun containsArabic(
        text: CharSequence
    ): Boolean {
        var i = 0

        while (i < text.length) {
            val codePoint = Character.codePointAt(
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

    private fun mirrorPunctuation(
        c: Char
    ): Char {
        return when (c) {
            '(' -> ')'
            ')' -> '('
            else -> c
        }
    }

    private fun appendMirroredReversed(
        out: Appendable,
        line: CharSequence,
        from:
