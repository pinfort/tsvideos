package me.pinfort.tsvideos.processing.external.ts

internal fun ByteArray.u8(i: Int): Int = this[i].toInt() and 0xff

/** MPEG-2 PSI の CRC-32 (多項式 0x04C11DB7, 初期値 0xFFFFFFFF, 反転なし) */
internal object Crc32Mpeg2 {
    private val table =
        IntArray(256) { i ->
            var c = i shl 24
            repeat(8) { c = if (c and 0x80000000.toInt() != 0) (c shl 1) xor 0x04c11db7 else c shl 1 }
            c
        }

    fun compute(
        buf: ByteArray,
        start: Int = 0,
        end: Int = buf.size,
    ): Int {
        var crc = -1
        for (i in start until end) {
            crc = (crc shl 8) xor table[((crc ushr 24) xor buf.u8(i)) and 0xff]
        }
        return crc
    }

    /** CRC を含めたセクション全体の CRC が 0 になれば正常 */
    fun isValid(section: ByteArray): Boolean = section.size >= 4 && compute(section) == 0
}
