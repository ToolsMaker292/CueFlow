package com.cueflow.app.data

import kotlin.math.ceil
import kotlin.math.max

/**
 * Estimates speaking time for mixed Chinese/English scripts.
 * Chinese characters and Latin words are weighted at roughly 220 units/minute,
 * which is a comfortable presentation pace rather than a rushed one.
 */
object ReadingTime {
    private const val UNITS_PER_MINUTE = 220.0
    private const val LATIN_WORD_WEIGHT = 1.6

    fun estimateMinutes(text: String): Int {
        val units = units(text)
        if (units <= 0.0) return 0
        return max(1, ceil(units / UNITS_PER_MINUTE).toInt())
    }

    fun units(text: String): Double {
        var cjk = 0
        var latinWords = 0
        var insideWord = false
        for (character in text) {
            when {
                character.isCjk() -> {
                    cjk++
                    insideWord = false
                }
                character.isLetterOrDigit() -> {
                    if (!insideWord) {
                        latinWords++
                        insideWord = true
                    }
                }
                else -> insideWord = false
            }
        }
        return cjk + latinWords * LATIN_WORD_WEIGHT
    }

    private fun Char.isCjk(): Boolean {
        val code = code
        return code in 0x3400..0x4DBF ||
            code in 0x4E00..0x9FFF ||
            code in 0xF900..0xFAFF ||
            code in 0x3040..0x30FF ||
            code in 0xAC00..0xD7AF
    }

    fun countCharacters(text: String): Int = text.length
}
