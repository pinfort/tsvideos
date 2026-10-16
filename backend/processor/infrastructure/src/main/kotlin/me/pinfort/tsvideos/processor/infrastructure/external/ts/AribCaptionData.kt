package me.pinfort.tsvideos.processor.infrastructure.external.ts

/**
 * ARIB STD-B24 の字幕・文字スーパー PES を解析する。
 * 文字コード (8 単位符号) のデコードはせず、制御符号を読み飛ばした後に図形文字が残るかだけを判定する。
 */
internal object AribCaptionData {
    private const val DATA_UNIT_SEPARATOR = 0x1f
    private const val DATA_UNIT_STATEMENT_BODY = 0x20
    private const val TMD_OFFSET_TIME = 0x02

    /** PES に、可視文字を含む本文データユニットを持つ字幕文データグループが入っていれば true */
    fun hasVisibleText(pes: ByteArray): Boolean {
        val payload = pesPayloadStart(pes) ?: return false
        // PES_data_packet: data_identifier(8) private_stream_id(8) reserved(4) PES_data_packet_header_length(4)
        if (payload + 3 > pes.size) return false
        val dataGroup = payload + 3 + (pes.u8(payload + 2) and 0x0f)
        // data_group: data_group_id(6) version(2) link_number(8) last_link_number(8) data_group_size(16)
        if (dataGroup + 5 > pes.size) return false
        val dataGroupId = pes.u8(dataGroup) shr 2
        if (dataGroupId and 0x0f == 0) return false // 字幕管理データ
        val size = (pes.u8(dataGroup + 3) shl 8) or pes.u8(dataGroup + 4)
        val start = dataGroup + 5
        return statementHasVisibleText(pes, start, (start + size).coerceAtMost(pes.size))
    }

    private fun pesPayloadStart(pes: ByteArray): Int? {
        if (pes.size < 9 || pes.u8(0) != 0 || pes.u8(1) != 0 || pes.u8(2) != 1) return null
        return when (pes.u8(3)) {
            // private_stream_2 等は PES ヘッダ拡張を持たない
            0xbc, 0xbe, 0xbf, 0xf0, 0xf1, 0xf2, 0xf8, 0xff -> 6
            else -> 9 + pes.u8(8)
        }
    }

    // caption_data: TMD(2) reserved(6) [STM(36) reserved(4)] data_unit_loop_length(24) data_unit()...
    private fun statementHasVisibleText(
        buf: ByteArray,
        start: Int,
        end: Int,
    ): Boolean {
        if (start >= end) return false
        var p = start + 1
        if (buf.u8(start) shr 6 == TMD_OFFSET_TIME) p += 5
        if (p + 3 > end) return false
        val loopLength = (buf.u8(p) shl 16) or (buf.u8(p + 1) shl 8) or buf.u8(p + 2)
        p += 3
        val loopEnd = (p + loopLength).coerceAtMost(end)
        // data_unit: unit_separator(8) data_unit_parameter(8) data_unit_size(24) data_unit_data_byte...
        while (p + 5 <= loopEnd) {
            if (buf.u8(p) != DATA_UNIT_SEPARATOR) return false
            val parameter = buf.u8(p + 1)
            val unitSize = (buf.u8(p + 2) shl 16) or (buf.u8(p + 3) shl 8) or buf.u8(p + 4)
            val dataStart = p + 5
            val dataEnd = (dataStart + unitSize).coerceAtMost(loopEnd)
            if (parameter == DATA_UNIT_STATEMENT_BODY && containsGraphicCharacter(buf, dataStart, dataEnd)) return true
            p = dataStart + unitSize
        }
        return false
    }

    /**
     * 8 単位符号の本文から制御符号 (C0/C1 とそのパラメータ、エスケープシーケンス) を読み飛ばし、
     * GL (0x21-0x7E) / GR (0xA1-0xFE) の図形文字が 1 つでもあれば true。空白 (SP) は数えない。
     */
    internal fun containsGraphicCharacter(
        buf: ByteArray,
        start: Int,
        end: Int,
    ): Boolean {
        var i = start
        while (i < end) {
            val b = buf.u8(i)
            if (b in 0x21..0x7e || b in 0xa1..0xfe) return true
            i +=
                when (b) {
                    0x1b -> escapeSequenceLength(buf, i, end)
                    0x16 -> 2 // PAPF P1
                    0x1c -> 3 // APS P1 P2
                    0x8b, 0x91, 0x93, 0x94, 0x97, 0x98 -> 2 // SZX/FLC/POL/WMM/HLC/RPC P1
                    0x90, 0x92 -> if (i + 1 < end && buf.u8(i + 1) == 0x20) 3 else 2 // COL/CDC
                    0x9d -> 3 // TIME
                    0x9b -> controlSequenceLength(buf, i, end)
                    0x95 -> macroLength(buf, i, end)
                    else -> 1
                }
        }
        return false
    }

    // ESC で始まる符号の指示・呼び出し (ARIB STD-B24 第一編 第 2 部 表 7-2)
    private fun escapeSequenceLength(
        buf: ByteArray,
        i: Int,
        end: Int,
    ): Int {
        fun at(k: Int) = if (i + k < end) buf.u8(i + k) else -1
        return when (at(1)) {
            in 0x28..0x2b -> if (at(2) == 0x20) 4 else 3 // 1 バイト G セット (DRCS は 0x20 付き)
            0x24 ->
                when (at(2)) {
                    in 0x29..0x2b -> if (at(3) == 0x20) 5 else 4 // 2 バイト G1-G3 (DRCS は 0x20 付き)
                    0x28 -> if (at(3) == 0x20) 5 else 3 // ESC $ ( SP F は DRCS、ESC $ F は G0
                    else -> 3
                }
            else -> 2 // LS2/LS3/LS1R/LS2R/LS3R
        }
    }

    // CSI P1 ... I F (中間文字 I = 0x20 の次の 1 バイトが終端)
    private fun controlSequenceLength(
        buf: ByteArray,
        i: Int,
        end: Int,
    ): Int {
        var k = i + 1
        while (k < end && buf.u8(k) != 0x20) k++
        return (k + 2 - i).coerceAtMost(end - i)
    }

    // MACRO P1 ... MACRO 0x4F (マクロ定義の終わりまで)
    private fun macroLength(
        buf: ByteArray,
        i: Int,
        end: Int,
    ): Int {
        var k = i + 2
        while (k + 1 < end && !(buf.u8(k) == 0x95 && buf.u8(k + 1) == 0x4f)) k++
        return (k + 2 - i).coerceAtMost(end - i)
    }
}
