@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.app.features.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming

// ============================================================
// Arabic subtitle bidi test
// true  = تعطيل RLE/PDF القديم للعربي
// false = يرجع السلوك القديم
// ============================================================
private const val DISABLE_LEGACY_ARABIC_RTL_WRAP = true

internal object AndroidPlayerSubtitleRtlFix {

    fun fixCueText(cue: Cue, isBuiltInSubtitle: Boolean): Cue {
        val text = cue.text ?: return cue
        if (!hasAnyRtlCharacter(text)) {
            return cue
        }

        if (containsArabic(text)) {
            // تعطيل معالجة RLE/PDF القديمة للعربي
            if (DISABLE_LEGACY_ARABIC_RTL_WRAP) {
                return cue
            }

            // السلوك القديم
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
            var modified: ArrayList<Cue>? = null

            for (i in entryCues.indices) {
                val original = entryCues[i]
                val fixed = fixCue
