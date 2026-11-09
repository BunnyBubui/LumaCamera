package th.ogay.luma.ocr

import java.text.Normalizer

/**
 * Text normalisation and error rates used to score OCR output against ground truth.
 *
 * Normalisation: Unicode NFC, SARA AM written as NIKHAHIT + SARA AA (U+0E4D U+0E32) folded into
 * U+0E33, zero-width characters removed, runs of whitespace collapsed to one space.
 *
 * CER ignores spaces because Thai does not separate words with spaces and the detector decides where
 * phrase gaps fall. WER splits on whitespace, so it is meaningful for English and phrase-level for Thai.
 */
object OcrMetrics {
    private val zeroWidth = Regex("[​‌‍﻿]")
    private val spaces = Regex("\\s+")

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
            .replace("ํา", "ำ")
            .replace(zeroWidth, "")
            .replace(spaces, " ")
            .trim()

    /** Character errors (insertions + deletions + substitutions) ignoring spaces. */
    fun characterErrors(truth: String, actual: String): Int =
        editDistance(normalize(truth).replace(" ", "").toList(), normalize(actual).replace(" ", "").toList())

    fun characterCount(truth: String): Int = normalize(truth).replace(" ", "").length

    fun cer(truth: String, actual: String): Double =
        characterErrors(truth, actual).toDouble() / maxOf(1, characterCount(truth))

    fun wer(truth: String, actual: String): Double {
        val t = normalize(truth).split(" ").filter { it.isNotEmpty() }
        val a = normalize(actual).split(" ").filter { it.isNotEmpty() }
        return editDistance(t, a).toDouble() / maxOf(1, t.size)
    }

    fun <T> editDistance(a: List<T>, b: List<T>): Int {
        var previous = IntArray(b.size + 1) { it }
        for (i in 1..a.size) {
            val current = IntArray(b.size + 1)
            current[0] = i
            for (j in 1..b.size) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            previous = current
        }
        return previous[b.size]
    }
}
