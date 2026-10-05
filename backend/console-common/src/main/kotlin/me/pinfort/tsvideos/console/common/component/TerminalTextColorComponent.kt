package me.pinfort.tsvideos.console.common.component

import org.springframework.stereotype.Component

/**
 * 文字列をターミナル上で装飾するためのコンポーネント
 */
@Component
class TerminalTextColorComponent {
    companion object {
        private const val ESCAPE = "\u001B"
        private const val TO_BACKGROUND = 10 // 文字色指定用色コードに10を足すと背景用のコードになる。
        private val ANSI_RESET = toAnsiCode(COLOR.RESET.code)

        private fun Int.toBackground(): Int = this + TO_BACKGROUND

        private fun toAnsiCode(colorCode: Int): String = "$ESCAPE[${colorCode}m"
    }

    fun debug(text: String): String = decorateString(text, COLOR.WHITE.code.toBackground())

    fun info(text: String): String = decorateString(text, COLOR.GREEN.code.toBackground())

    fun warn(text: String): String = decorateString(text, COLOR.YELLOW.code.toBackground())

    fun error(text: String): String = decorateString(text, COLOR.RED.code.toBackground())

    private fun decorateString(
        text: String,
        colorCode: Int?,
    ): String {
        if (colorCode == null) {
            return text
        }
        return toAnsiCode(colorCode) + text + ANSI_RESET
    }

    enum class COLOR(
        val code: Int,
    ) {
        RESET(0),
        BLACK(30),
        RED(31),
        GREEN(32),
        YELLOW(33),
        BLUE(34),
        MAGENTA(35),
        CYAN(36),
        LIGHT_GRAY(37),

        DARK_GRAY(90),
        LIGHT_RED(91),
        LIGHT_GREEN(92),
        LIGHT_YELLOW(93),
        LIGHT_BLUE(94),
        LIGHT_MAGENTA(95),
        LIGHT_CYAN(96),
        WHITE(97),
    }
}
