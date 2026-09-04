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

    // =========================================================================
    // عالم الترجمات المشوشة: لم يُلمَس أي حرف منه - يبقى كما يعمل بشكل مثالي
    // =========================================================================

    // =========================================================================
    // بوابة التوجيه (Router): تقرر أي عالم يستقبل كل سطر
    // تم تصحيحها فقط عبر دالتين مساعدتين جديدتين ومستقلتين أدناه،
    // دون المساس بـ isBoundaryPunctuation المُستخدمة داخل applyVisualSwapping
    // =========================================================================

    private fun isMessySubtitle(text: CharSequence, isBuiltInSubtitle: Boolean): Boolean {
        if (isBuiltInSubtitle) return false // الترجمة المدمجة تعتبر سليمة دائماً
        
        val lines = text.splitByNewlines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            
            // إذا كان السطر يبدأ أو ينتهي برموز محايدة مكشوفة فعلاً (وليست
            // استخداماً طبيعياً شائعاً كنقطة نهاية الجملة أو شرطة حوار)،
            // نعتبره عشوائياً ويحتاج للتبديل
            if (hasMessyLeadingBoundary(trimmed) || hasMessyTrailingBoundary(trimmed)) {
                return true
            }
        }
        return false
    }

    // تُستخدم حصراً هنا لتصنيف "هل هذا السطر مشوش؟" - لا علاقة لها بمنطق
    // applyVisualSwapping ولا تُستدعى منه
    private fun hasMessyLeadingBoundary(trimmed: CharSequence): Boolean {
        val firstChar = trimmed.first()
        if (!isBoundaryPunctuation(firstChar)) return false

        // شرطة حوار طبيعية في بداية السطر ("- مرحبا") ليست عطلاً
        if ((firstChar == '-' || firstChar == '—') &&
            trimmed.length > 1 &&
            trimmed[1] == ' '
        ) {
            return false
        }

        return true
    }

    private fun hasMessyTrailingBoundary(trimmed: CharSequence): Boolean {
        val lastChar = trimmed.last()
        if (!isBoundaryPunctuation(lastChar)) return false

        // نقطة نهاية جملة طبيعية واحدة (وليست جزءاً من سلسلة رموز مثل "..." أو "،.")
        // ليست عطلاً
        if (lastChar == '.' &&
            (trimmed.length == 1 || !isBoundaryPunctuation(trimmed[trimmed.length - 2]))
        ) {
            return false
        }

        return true
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
            val core = if (hasCr) line.subSequence(0, line.length - 1) else line
            
            if (core.isEmpty()) {
                if (hasCr) builder.append('\r')
                continue
            }
            
            var start = 0
            while (start < core.length && isBoundaryPunctuation(core[start])) start++
            
            var end = core.length
            while (end > start && isBoundaryPunctuation(core[end - 1])) end--
            
            if (start >= end) {
                builder.append(core)
                if (hasCr) builder.append('\r')
                continue
            }
            
            val leadingPunc = core.subSequence(0, start)
            val trailingPunc = core.subSequence(end, core.length)
            val middleText = core.subSequence(start, end)
            
            for (j in trailingPunc.indices.reversed()) {
                builder.append(mirrorArabicPunctuation(trailingPunc[j]))
            }
            
            builder.append('\u202B').append(middleText).append('\u202C')
            
            for (j in leadingPunc.indices.reversed()) {
                builder.append(mirrorArabicPunctuation(leadingPunc[j]))
            }
            
            if (hasCr) builder.append('\r')
        }
        return finishBuilder(builder)
    }

    private fun isBoundaryPunctuation(c: Char): Boolean {
        return c == '"' || c == '\'' || c == '«' || c == '»' || c == '”' || c == '“' ||
               c == '!' || c == '؟' || c == '?' ||
               c == '-' || c == '—' ||
               c == '(' || c == ')' || c == '[' || c == ']' || c == '{' || c == '}' ||
               c == '.' || c == ',' || c == '،' || c == ':' || c == ';' || c == '…' ||
               c.isWhitespace()
    }

    private fun mirrorArabicPunctuation(c: Char): Char = when (c) {
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

    // =========================================================================
    // عالم الترجمات السليمة: تم إصلاحه - الرجوع لتضمين RLE/PDF القوي والمستقر
    // بدل RLM الضعيفة (كانت السبب في المظهر المبعثر أحياناً)
    // =========================================================================

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
            // RLE...PDF: تضمين صريح ومغلق يضمن نتيجة ثابتة بغض النظر عن
            // أي محتوى لاتيني/أرقام مدمجة أو سياق مجاور
            builder.append('\u202B').append(core).append('\u202C')
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

    private fun isDirectionalMark(c: Char): Boolean =
        c == '\u202A' || c == '\u202B' || c == '\u202C' ||
            c == '\u200E' || c == '\u200F'

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
