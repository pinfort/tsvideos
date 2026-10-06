package me.pinfort.tsvideos.processor.infrastructure.external.ts

import me.pinfort.tsvideos.core.domain.ExecutedFileTag
import me.pinfort.tsvideos.core.exception.TsVideosException
import org.springframework.stereotype.Component
import java.io.File

/**
 * TS を 1 パス読み、緊急警報放送 (EWS) と文字スーパーの有無を検出する。
 *
 * - EWS: PMT 第 1 ループの緊急情報記述子 (emergency_information_descriptor, tag 0xFC) に
 *   start_end_flag=1 (開始/継続中) のエントリがあれば検出とする (ARIB STD-B10)。
 * - 文字スーパー: PMT で stream_type=0x06 かつ component_tag 0x38〜0x3F の ES を文字スーパーとみなし
 *   (ARIB TR-B14)、その PES に可視文字を含む本文 (データユニット 0x20) が流れていれば検出とする。
 *   PMT に ES があるだけの局や、管理データ・画面消去だけの PES は検出しない。
 *
 * 放送局が映像に焼き込んだテロップ・速報は TS のデータとしては存在しないため検出できない。
 */
@Component
class EmergencyBroadcastDetector {
    data class Result(
        val ewsDetected: Boolean,
        val superimposeDetected: Boolean,
    ) {
        /** 録画に付けるタグ ([ExecutedFileTag]) */
        fun tags(): Set<String> =
            buildSet {
                if (ewsDetected) add(ExecutedFileTag.EWS)
                if (superimposeDetected) add(ExecutedFileTag.SUPERIMPOSE)
            }
    }

    fun detect(file: File): Result = detect(file) { _, _ -> }

    /**
     * 読み込んだバイト数 / 全体バイト数を [onProgress] に通知しながら検出する。
     * 両方検出した時点で読み込みを打ち切り、最後に必ず (全体, 全体) を通知する。
     *
     * @throws TsVideosException 188/192/204 バイトの TS パケット列でない場合
     */
    fun detect(
        file: File,
        onProgress: (bytesProcessed: Long, totalBytes: Long) -> Unit,
    ): Result {
        val total = file.length()
        val state = ParserState()
        file.inputStream().buffered(BUFFER_SIZE).use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            var len = readFully(input, buf, 0, buf.size)
            val unit = selectUnitSize(buf, len) ?: throw TsVideosException("not a transport stream, file=$file")
            // 192 バイト (m2ts) はタイムスタンプ 4 バイトの後ろに 188 バイトパケットが続く
            val headOffset = unit.size - TS_PACKET_SIZE - unit.trailer
            var pos = unit.syncOffset - headOffset
            var consumed = 0L
            var lastReport = 0L
            while (!state.isComplete()) {
                if (pos + unit.size > len) {
                    // 残りを先頭へ寄せて次を読む
                    val remain = (len - pos).coerceAtLeast(0)
                    if (remain > 0) System.arraycopy(buf, pos, buf, 0, remain)
                    consumed += pos
                    pos = 0
                    val read = readFully(input, buf, remain, buf.size - remain)
                    len = remain + read
                    if (read <= 0 || len < unit.size) break
                    if (consumed - lastReport >= PROGRESS_INTERVAL) {
                        lastReport = consumed
                        onProgress(consumed, total)
                    }
                }
                val packet = pos + headOffset
                if (buf[packet] != SYNC_BYTE) {
                    // 同期が外れたら 1 バイトずつずらして再同期する
                    pos += 1
                    continue
                }
                state.onPacket(buf, packet)
                pos += unit.size
            }
        }
        onProgress(total, total)
        return Result(state.ewsDetected, state.superimposeDetected)
    }

    private data class UnitSize(
        val size: Int,
        val trailer: Int,
        val syncOffset: Int,
    )

    private fun selectUnitSize(
        buf: ByteArray,
        len: Int,
    ): UnitSize? {
        for ((size, trailer) in listOf(188 to 0, 192 to 0, 204 to 16)) {
            val head = size - TS_PACKET_SIZE - trailer
            for (off in head until head + size) {
                if (off + size * (SYNC_CHECK_COUNT - 1) >= len) break
                if ((0 until SYNC_CHECK_COUNT).all { buf[off + it * size] == SYNC_BYTE }) {
                    return UnitSize(size, trailer, off)
                }
            }
        }
        return null
    }

    private fun readFully(
        input: java.io.InputStream,
        buf: ByteArray,
        off: Int,
        len: Int,
    ): Int {
        var total = 0
        while (total < len) {
            val n = input.read(buf, off + total, len - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private class ParserState {
        var ewsDetected = false
        var superimposeDetected = false

        private val sectionAssemblers = HashMap<Int, SectionAssembler>()
        private val pesAssemblers = HashMap<Int, PesAssembler>()
        private val lastContinuityCounter = IntArray(8192) { -1 }
        private val lastSectionCrc = HashMap<Int, Int>()
        private var pmtPids = emptySet<Int>()

        // PMT ごとに見つけた文字スーパー PID。PMT が更新されたら差し替える
        private val superimposePidsByPmt = HashMap<Int, Set<Int>>()

        fun isComplete(): Boolean = ewsDetected && superimposeDetected

        fun onPacket(
            buf: ByteArray,
            off: Int,
        ) {
            val b1 = buf.u8(off + 1)
            val pid = ((b1 and 0x1f) shl 8) or buf.u8(off + 2)
            val isPat = pid == PAT_PID
            val isPmt = pid in pmtPids
            val isSuperimpose = !isPat && !isPmt && pesAssemblers.containsKey(pid)
            if (!isPat && !isPmt && !isSuperimpose) return

            val transportError = (b1 and 0x80) != 0
            val payloadUnitStart = (b1 and 0x40) != 0
            val b3 = buf.u8(off + 3)
            val adaptationFieldControl = (b3 shr 4) and 0x03
            val continuityCounter = b3 and 0x0f

            if (transportError) {
                reset(pid)
                return
            }
            if (adaptationFieldControl and 0x01 == 0) return // ペイロードなし

            val lastCc = lastContinuityCounter[pid]
            lastContinuityCounter[pid] = continuityCounter
            if (lastCc == continuityCounter) return // 重複パケット
            if (lastCc >= 0 && ((lastCc + 1) and 0x0f) != continuityCounter) reset(pid)

            var payloadStart = off + 4
            if (adaptationFieldControl == 0x03) payloadStart += 1 + buf.u8(off + 4)
            val payloadEnd = off + TS_PACKET_SIZE
            if (payloadStart >= payloadEnd) return

            if (isSuperimpose) {
                pesAssemblers.getValue(pid).push(buf, payloadStart, payloadEnd, payloadUnitStart).forEach(::onSuperimposePes)
            } else {
                sectionAssemblers
                    .getOrPut(pid) { SectionAssembler() }
                    .push(buf, payloadStart, payloadEnd, payloadUnitStart)
                    .forEach { onSection(pid, it) }
            }
        }

        private fun reset(pid: Int) {
            sectionAssemblers[pid]?.reset()
            pesAssemblers[pid]?.reset()
        }

        private fun onSection(
            pid: Int,
            section: ByteArray,
        ) {
            if (!Crc32Mpeg2.isValid(section)) return
            val crc = section.crcTail()
            if (lastSectionCrc[pid] == crc) return // 前回と同じ内容なら解析を省略
            lastSectionCrc[pid] = crc
            when {
                pid == PAT_PID && section.u8(0) == TABLE_ID_PAT -> onPat(section)
                pid in pmtPids && section.u8(0) == TABLE_ID_PMT -> onPmt(pid, section)
            }
        }

        private fun onPat(section: ByteArray) {
            val newPmtPids = HashSet<Int>()
            var p = SECTION_HEADER_LENGTH
            val end = section.size - CRC_LENGTH
            while (p + 4 <= end) {
                val programNumber = (section.u8(p) shl 8) or section.u8(p + 1)
                val pid = ((section.u8(p + 2) and 0x1f) shl 8) or section.u8(p + 3)
                if (programNumber != 0) newPmtPids += pid // 0 は NIT
                p += 4
            }
            (pmtPids - newPmtPids).forEach { removed ->
                superimposePidsByPmt.remove(removed)
                sectionAssemblers.remove(removed)
                lastSectionCrc.remove(removed)
            }
            pmtPids = newPmtPids
            syncPesAssemblers()
        }

        private fun onPmt(
            pmtPid: Int,
            section: ByteArray,
        ) {
            if (section.size < PMT_MIN_LENGTH) return
            val end = section.size - CRC_LENGTH
            val programInfoLength = ((section.u8(10) and 0x0f) shl 8) or section.u8(11)
            var p = 12
            val programInfoEnd = (p + programInfoLength).coerceAtMost(end)
            forEachDescriptor(section, p, programInfoEnd) { tag, start, length ->
                if (tag == TAG_EMERGENCY_INFORMATION && hasActiveEmergency(section, start, start + length)) {
                    ewsDetected = true
                }
            }
            p = programInfoEnd

            val superimposePids = HashSet<Int>()
            while (p + 5 <= end) {
                val streamType = section.u8(p)
                val elementaryPid = ((section.u8(p + 1) and 0x1f) shl 8) or section.u8(p + 2)
                val esInfoLength = ((section.u8(p + 3) and 0x0f) shl 8) or section.u8(p + 4)
                val esInfoEnd = (p + 5 + esInfoLength).coerceAtMost(end)
                if (streamType == STREAM_TYPE_PRIVATE_PES) {
                    forEachDescriptor(section, p + 5, esInfoEnd) { tag, start, length ->
                        if (tag == TAG_STREAM_IDENTIFIER && length >= 1 && section.u8(start) in SUPERIMPOSE_COMPONENT_TAGS) {
                            superimposePids += elementaryPid
                        }
                    }
                }
                p = esInfoEnd
            }
            superimposePidsByPmt[pmtPid] = superimposePids
            syncPesAssemblers()
        }

        private fun syncPesAssemblers() {
            val wanted = superimposePidsByPmt.values.flatten().toSet()
            pesAssemblers.keys.retainAll(wanted)
            wanted.forEach { pesAssemblers.getOrPut(it) { PesAssembler() } }
        }

        // emergency_information_descriptor: { service_id(16) start_end_flag(1) signal_level(1) reserved(6)
        //   area_code_length(8) area_code(12)+reserved(4) ... } の繰り返し
        private fun hasActiveEmergency(
            buf: ByteArray,
            start: Int,
            end: Int,
        ): Boolean {
            var p = start
            while (p + 4 <= end) {
                if (buf.u8(p + 2) and 0x80 != 0) return true
                p += 4 + buf.u8(p + 3)
            }
            return false
        }

        private fun onSuperimposePes(pes: ByteArray) {
            if (AribCaptionData.hasVisibleText(pes)) superimposeDetected = true
        }
    }

    /** PSI セクションを TS パケットのペイロードから組み立てる */
    private class SectionAssembler {
        private val buffer = java.io.ByteArrayOutputStream()
        private var assembling = false

        fun reset() {
            buffer.reset()
            assembling = false
        }

        fun push(
            buf: ByteArray,
            start: Int,
            end: Int,
            payloadUnitStart: Boolean,
        ): List<ByteArray> {
            var p = start
            if (payloadUnitStart) {
                val pointer = buf.u8(p)
                p += 1
                if (assembling) buffer.write(buf, p, (pointer).coerceAtMost(end - p))
                val sections = drain()
                buffer.reset()
                p += pointer
                if (p >= end) {
                    assembling = false
                    return sections
                }
                assembling = true
                buffer.write(buf, p, end - p)
                return sections + drain()
            }
            if (!assembling) return emptyList()
            buffer.write(buf, p, end - p)
            return drain()
        }

        private fun drain(): List<ByteArray> {
            val sections = mutableListOf<ByteArray>()
            var data = buffer.toByteArray()
            while (data.size >= 3) {
                if (data.u8(0) == 0xff) {
                    // スタッフィング以降にセクションはない
                    data = ByteArray(0)
                    assembling = false
                    break
                }
                val length = 3 + (((data.u8(1) and 0x0f) shl 8) or data.u8(2))
                if (data.size < length) break
                sections += data.copyOfRange(0, length)
                data = data.copyOfRange(length, data.size)
            }
            buffer.reset()
            buffer.write(data)
            return sections
        }
    }

    /** PES を TS パケットのペイロードから組み立てる。完成した PES を返す (途中で切れたものも含む) */
    private class PesAssembler {
        private val buffer = java.io.ByteArrayOutputStream()
        private var assembling = false

        fun reset() {
            buffer.reset()
            assembling = false
        }

        fun push(
            buf: ByteArray,
            start: Int,
            end: Int,
            payloadUnitStart: Boolean,
        ): List<ByteArray> {
            val completed = mutableListOf<ByteArray>()
            if (payloadUnitStart) {
                if (assembling) completed += buffer.toByteArray()
                buffer.reset()
                assembling = true
            }
            if (!assembling) return completed
            buffer.write(buf, start, end - start)
            if (buffer.size() > MAX_PES_SIZE) {
                reset()
                return completed
            }
            val current = buffer.toByteArray()
            if (current.size >= 6) {
                val pesLength = (current.u8(4) shl 8) or current.u8(5)
                if (pesLength != 0 && current.size >= 6 + pesLength) {
                    reset()
                    completed += current.copyOf(6 + pesLength)
                }
            }
            return completed
        }
    }

    private companion object {
        const val SYNC_BYTE: Byte = 0x47
        const val TS_PACKET_SIZE = 188
        const val SYNC_CHECK_COUNT = 8
        const val BUFFER_SIZE = 1 shl 20
        const val PROGRESS_INTERVAL = 16L shl 20
        const val MAX_PES_SIZE = 1 shl 16

        const val PAT_PID = 0x0000
        const val TABLE_ID_PAT = 0x00
        const val TABLE_ID_PMT = 0x02
        const val SECTION_HEADER_LENGTH = 8
        const val CRC_LENGTH = 4
        const val PMT_MIN_LENGTH = 16

        const val STREAM_TYPE_PRIVATE_PES = 0x06
        const val TAG_STREAM_IDENTIFIER = 0x52
        const val TAG_EMERGENCY_INFORMATION = 0xfc
        val SUPERIMPOSE_COMPONENT_TAGS = 0x38..0x3f

        fun forEachDescriptor(
            buf: ByteArray,
            start: Int,
            end: Int,
            action: (tag: Int, dataStart: Int, length: Int) -> Unit,
        ) {
            var p = start
            while (p + 2 <= end) {
                val tag = buf.u8(p)
                val length = buf.u8(p + 1)
                if (p + 2 + length > end) return
                action(tag, p + 2, length)
                p += 2 + length
            }
        }

        fun ByteArray.crcTail(): Int = (u8(size - 4) shl 24) or (u8(size - 3) shl 16) or (u8(size - 2) shl 8) or u8(size - 1)
    }
}

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
