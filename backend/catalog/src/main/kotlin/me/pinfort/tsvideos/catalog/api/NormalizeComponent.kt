package me.pinfort.tsvideos.catalog.api

import org.springframework.stereotype.Component

@Component
class NormalizeComponent {
    private val specialCharactersMap =
        mapOf(
            "\\" to "￥",
            "/" to "／",
            ":" to "：",
            "*" to "＊",
            "?" to "？",
            "\"" to "”",
            "<" to "＜",
            ">" to "＞",
            "|" to "｜",
            "~" to "～",
            "‼" to "!!",
        )
    private val specialCharactersRegex = Regex("[\\\\/:*?\"<>|~‼]")

    private val alphaNumericCharacters =
        mapOf(
            "０" to "0",
            "１" to "1",
            "２" to "2",
            "３" to "3",
            "４" to "4",
            "５" to "5",
            "６" to "6",
            "７" to "7",
            "８" to "8",
            "９" to "9",
            "Ａ" to "A",
            "Ｂ" to "B",
            "Ｃ" to "C",
            "Ｄ" to "D",
            "Ｅ" to "E",
            "Ｆ" to "F",
            "Ｇ" to "G",
            "Ｈ" to "H",
            "Ｉ" to "I",
            "Ｊ" to "J",
            "Ｋ" to "K",
            "Ｌ" to "L",
            "Ｍ" to "M",
            "Ｎ" to "N",
            "Ｏ" to "O",
            "Ｐ" to "P",
            "Ｑ" to "Q",
            "Ｒ" to "R",
            "Ｓ" to "S",
            "Ｔ" to "T",
            "Ｕ" to "U",
            "Ｖ" to "V",
            "Ｗ" to "W",
            "Ｘ" to "X",
            "Ｙ" to "Y",
            "Ｚ" to "Z",
            "ａ" to "a",
            "ｂ" to "b",
            "ｃ" to "c",
            "ｄ" to "d",
            "ｅ" to "e",
            "ｆ" to "f",
            "ｇ" to "g",
            "ｈ" to "h",
            "ｉ" to "i",
            "ｊ" to "j",
            "ｋ" to "k",
            "ｌ" to "l",
            "ｍ" to "m",
            "ｎ" to "n",
            "ｏ" to "o",
            "ｐ" to "p",
            "ｑ" to "q",
            "ｒ" to "r",
            "ｓ" to "s",
            "ｔ" to "t",
            "ｕ" to "u",
            "ｖ" to "v",
            "ｗ" to "w",
            "ｘ" to "x",
            "ｙ" to "y",
            "ｚ" to "z",
        )

    private val alphaNumericRegex = Regex("[０-９Ａ-Ｚａ-ｚ]")

    fun normalize(
        regex: Regex,
        replaceMap: Map<String, String>,
        input: String,
    ): String =
        input.replace(regex) {
            replaceMap[it.value] ?: it.value
        }

    fun normalizeSpecialCharacters(input: String): String = normalize(specialCharactersRegex, specialCharactersMap, input)

    fun normalizeAlphaNumeric(input: String): String = normalize(alphaNumericRegex, alphaNumericCharacters, input)

    fun normalize(input: String): String = normalizeAlphaNumeric(normalizeSpecialCharacters(input))
}
