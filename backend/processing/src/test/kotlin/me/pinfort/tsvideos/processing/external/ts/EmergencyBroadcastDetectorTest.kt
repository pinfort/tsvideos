package me.pinfort.tsvideos.processing.external.ts

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.domain.ExecutedFileCheck
import me.pinfort.tsvideos.catalog.domain.ExecutedFileTag
import me.pinfort.tsvideos.processing.domain.TsVideosException
import java.io.ByteArrayOutputStream
import java.io.File

class EmergencyBroadcastDetectorTest :
    ExpectSpec({
        val detector = EmergencyBroadcastDetector()

        val pmtPid = 0x01f0
        val superimposePid = 0x0138
        val captionPid = 0x0130
        val serviceId = 0x0400

        val continuityCounters = HashMap<Int, Int>()

        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

        fun withCrc(body: ByteArray): ByteArray {
            val crc = Crc32Mpeg2.compute(body)
            return body + bytes(crc ushr 24, crc ushr 16, crc ushr 8, crc)
        }

        // ペイロードを 184 バイトずつ TS パケットに分割する。足りない分は 0xFF で埋める
        fun packets(
            pid: Int,
            payload: ByteArray,
        ): ByteArray {
            val out = ByteArrayOutputStream()
            var offset = 0
            var first = true
            while (offset < payload.size) {
                val cc = continuityCounters.getOrDefault(pid, -1).plus(1) and 0x0f
                continuityCounters[pid] = cc
                val chunk = payload.copyOfRange(offset, minOf(offset + 184, payload.size))
                out.write(bytes(0x47, (if (first) 0x40 else 0x00) or (pid shr 8), pid and 0xff, 0x10 or cc))
                out.write(chunk)
                out.write(ByteArray(184 - chunk.size) { 0xff.toByte() })
                offset += 184
                first = false
            }
            return out.toByteArray()
        }

        // PSI セクションは pointer_field (0) を先頭に付けて送る
        fun sectionPackets(
            pid: Int,
            section: ByteArray,
        ) = packets(pid, bytes(0x00) + section)

        fun pat(services: Map<Int, Int> = mapOf(serviceId to pmtPid)): ByteArray {
            val loop =
                services.entries.fold(ByteArray(0)) { acc, (service, pid) ->
                    acc + bytes(service shr 8, service and 0xff, 0xe0 or (pid shr 8), pid and 0xff)
                }
            val length = 5 + loop.size + 4
            return withCrc(bytes(0x00, 0xb0 or (length shr 8), length and 0xff, 0x7f, 0xe1, 0xc1, 0x00, 0x00) + loop)
        }

        fun emergencyDescriptor(active: Boolean): ByteArray {
            // service_id, start_end_flag + signal_level + reserved, area_code_length=2, area_code
            val entry = bytes(serviceId shr 8, serviceId and 0xff, if (active) 0xbf else 0x3f, 0x02, 0x00, 0x0f)
            return bytes(0xfc, entry.size) + entry
        }

        fun pmt(
            programInfo: ByteArray = ByteArray(0),
            withSuperimpose: Boolean = true,
            withCaption: Boolean = false,
            currentNext: Boolean = true,
            captionComponentTag: Int = 0x30,
            superimposeComponentTag: Int = 0x38,
        ): ByteArray {
            val esLoop = ByteArrayOutputStream()
            // 映像 (MPEG-2)
            esLoop.write(bytes(0x02, 0xe1, 0x11, 0xf0, 0x03, 0x52, 0x01, 0x00))
            if (withCaption) {
                // 字幕: stream_type=0x06, stream_identifier_descriptor component_tag=0x30
                esLoop.write(
                    bytes(0x06, 0xe0 or (captionPid shr 8), captionPid and 0xff, 0xf0, 0x03, 0x52, 0x01, captionComponentTag),
                )
            }
            if (withSuperimpose) {
                // 文字スーパー: stream_type=0x06, stream_identifier_descriptor component_tag=0x38
                esLoop.write(
                    bytes(0x06, 0xe0 or (superimposePid shr 8), superimposePid and 0xff, 0xf0, 0x03, 0x52, 0x01, superimposeComponentTag),
                )
            }
            val body =
                bytes(serviceId shr 8, serviceId and 0xff, if (currentNext) 0xc1 else 0xc0, 0x00, 0x00, 0xe1, 0x00) +
                    bytes(0xf0 or (programInfo.size shr 8), programInfo.size and 0xff) +
                    programInfo +
                    esLoop.toByteArray()
            val length = body.size + 4
            return withCrc(bytes(0x02, 0xb0 or (length shr 8), length and 0xff) + body)
        }

        // 文字スーパーの非同期 PES (private_stream_2)。dataGroupId=0 は管理データ、1 以降は字幕文
        fun dataGroup(
            text: ByteArray,
            dataGroupId: Int,
        ): ByteArray {
            val unit = bytes(0x1f, 0x20, text.size shr 16, text.size shr 8, text.size) + text
            val captionData = bytes(0x00, unit.size shr 16, unit.size shr 8, unit.size) + unit
            return bytes(dataGroupId shl 2, 0x00, 0x00, captionData.size shr 8, captionData.size and 0xff) +
                captionData +
                bytes(0x00, 0x00) // CRC16 (未検証)
        }

        fun superimposePes(
            text: ByteArray,
            dataGroupId: Int = 1,
        ): ByteArray {
            val pesData = bytes(0x81, 0xff, 0xf0) + dataGroup(text, dataGroupId)
            return bytes(0x00, 0x00, 0x01, 0xbf, pesData.size shr 8, pesData.size and 0xff) + pesData
        }

        // 字幕の同期 PES (private_stream_1)。PES ヘッダ拡張に PTS を持つ。dataGroupId=0 は管理データ、1 以降は字幕文
        fun captionPes(
            text: ByteArray,
            dataGroupId: Int = 1,
        ): ByteArray {
            // '10' + PTS_DTS_flags=10, PES_header_data_length=5, PTS (marker bit 付き)
            val header = bytes(0x80, 0x80, 0x05, 0x21, 0x00, 0x01, 0x00, 0x01)
            val pesData = header + bytes(0x80, 0xff, 0xf0) + dataGroup(text, dataGroupId)
            return bytes(0x00, 0x00, 0x01, 0xbd, pesData.size shr 8, pesData.size and 0xff) + pesData
        }

        // CS, CSI SWF (書式設定), ひらがな 2 文字
        val visibleText = bytes(0x0c, 0x9b, 0x37, 0x20, 0x53, 0xc6, 0xb9)
        // 画面消去だけ
        val clearOnly = bytes(0x0c)

        fun nullPackets(count: Int) = packets(0x1fff, ByteArray(184 * count))

        fun tempTs(vararg parts: ByteArray): File {
            val file = File.createTempFile("emergency-broadcast-test", ".m2ts")
            file.deleteOnExit()
            file.writeBytes(parts.fold(ByteArray(0)) { acc, part -> acc + part } + nullPackets(16))
            return file
        }

        beforeTest { continuityCounters.clear() }

        context("detect") {
            expect("detects nothing for a plain recording") {
                val file = tempTs(sectionPackets(0, pat()), sectionPackets(pmtPid, pmt(withSuperimpose = false)))

                detector.detect(file) shouldBe
                    EmergencyBroadcastDetector.Result(ewsDetected = false, superimposeDetected = false, captionDetected = false)
            }

            expect("detects an active emergency warning broadcast in the PMT") {
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt()),
                        sectionPackets(pmtPid, pmt(programInfo = emergencyDescriptor(active = true))),
                    )

                detector.detect(file).ewsDetected shouldBe true
            }

            expect("ignores an emergency descriptor that signals the end of the broadcast") {
                val file = tempTs(sectionPackets(0, pat()), sectionPackets(pmtPid, pmt(programInfo = emergencyDescriptor(active = false))))

                detector.detect(file).ewsDetected shouldBe false
            }

            expect("ignores a PMT announced for the next version (current_next_indicator=0)") {
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt()),
                        sectionPackets(pmtPid, pmt(programInfo = emergencyDescriptor(active = true), currentNext = false)),
                    )

                detector.detect(file).ewsDetected shouldBe false
            }

            expect("ignores a PMT whose CRC is broken") {
                val broken = pmt(programInfo = emergencyDescriptor(active = true)).also { it[it.size - 1] = (it.last() + 1).toByte() }
                val file = tempTs(sectionPackets(0, pat()), sectionPackets(pmtPid, broken))

                detector.detect(file).ewsDetected shouldBe false
            }

            expect("detects superimpose text") {
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt()),
                        packets(superimposePid, superimposePes(visibleText)),
                    )

                detector.detect(file) shouldBe
                    EmergencyBroadcastDetector.Result(ewsDetected = false, superimposeDetected = true, captionDetected = false)
            }

            expect("detects superimpose text whose PES spans several packets") {
                // CS + 改行 (APR) を並べて先頭パケットに文字が来ないようにする
                val longText = ByteArray(400) { 0x0d } + visibleText
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt()),
                        packets(superimposePid, superimposePes(longText)),
                    )

                detector.detect(file).superimposeDetected shouldBe true
            }

            expect("ignores a superimpose stream that only clears the screen") {
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt()),
                        packets(superimposePid, superimposePes(clearOnly)),
                    )

                detector.detect(file).superimposeDetected shouldBe false
            }

            expect("ignores superimpose management data") {
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt()),
                        packets(superimposePid, superimposePes(visibleText, dataGroupId = 0)),
                    )

                detector.detect(file).superimposeDetected shouldBe false
            }

            expect("ignores text on a PID the PMT does not mark as superimpose") {
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt(withSuperimpose = false)),
                        packets(superimposePid, superimposePes(visibleText)),
                    )

                detector.detect(file).superimposeDetected shouldBe false
            }

            expect("reparses an identical PMT after its PID leaves and rejoins the PAT") {
                val originalPmt = sectionPackets(pmtPid, pmt())
                val originalPes = packets(superimposePid, superimposePes(clearOnly))
                // Reintroduced streams may restart with the same CC as before removal.
                continuityCounters.remove(superimposePid)
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        originalPmt,
                        originalPes,
                        sectionPackets(0, pat(emptyMap())),
                        sectionPackets(0, pat()),
                        originalPmt,
                        packets(superimposePid, superimposePes(visibleText)),
                    )

                detector.detect(file).superimposeDetected shouldBe true
            }

            expect("inspects every service in the original recording") {
                val otherPmtPid = pmtPid + 1
                val file =
                    tempTs(
                        sectionPackets(0, pat(mapOf(serviceId + 1 to otherPmtPid, serviceId to pmtPid))),
                        sectionPackets(pmtPid, pmt(programInfo = emergencyDescriptor(active = true))),
                        packets(superimposePid, superimposePes(visibleText)),
                    )

                detector.detect(file) shouldBe EmergencyBroadcastDetector.Result(true, true, false)
            }

            expect("discards an unbounded PES at EOF without a following start packet") {
                val unbounded =
                    superimposePes(visibleText).also {
                        it[4] = 0
                        it[5] = 0
                    }
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt()),
                        packets(superimposePid, unbounded),
                    )

                detector.detect(file).superimposeDetected shouldBe false
            }

            expect("handles 192-byte m2ts packets") {
                val ts =
                    sectionPackets(0, pat()) +
                        sectionPackets(pmtPid, pmt(programInfo = emergencyDescriptor(active = true), withCaption = true)) +
                        packets(superimposePid, superimposePes(visibleText)) +
                        packets(captionPid, captionPes(visibleText)) +
                        nullPackets(16)
                val m2ts = ByteArrayOutputStream()
                for (i in 0 until ts.size / 188) {
                    m2ts.write(bytes(0x00, 0x00, 0x00, i))
                    m2ts.write(ts, i * 188, 188)
                }
                val file = File.createTempFile("emergency-broadcast-test", ".m2ts").apply { deleteOnExit() }
                file.writeBytes(m2ts.toByteArray())

                detector.detect(file) shouldBe
                    EmergencyBroadcastDetector.Result(ewsDetected = true, superimposeDetected = true, captionDetected = true)
            }

            expect("handles 204-byte packets with a trailing parity area") {
                val ts =
                    sectionPackets(0, pat()) +
                        sectionPackets(pmtPid, pmt(withSuperimpose = false, withCaption = true)) +
                        packets(captionPid, captionPes(visibleText)) +
                        nullPackets(16)
                val packets204 = ByteArrayOutputStream()
                for (i in 0 until ts.size / 188) {
                    packets204.write(ts, i * 188, 188)
                    packets204.write(ByteArray(16))
                }
                val file = File.createTempFile("emergency-broadcast-test", ".ts").apply { deleteOnExit() }
                file.writeBytes(packets204.toByteArray())

                detector.detect(file).captionDetected shouldBe true
            }

            expect("resyncs after garbage bytes") {
                val file =
                    tempTs(
                        nullPackets(16),
                        bytes(0x00, 0x12, 0x34),
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt(programInfo = emergencyDescriptor(active = true))),
                    )

                detector.detect(file).ewsDetected shouldBe true
            }

            expect("throws when the input is not a transport stream") {
                val file = File.createTempFile("emergency-broadcast-test", ".m2ts").apply { deleteOnExit() }
                file.writeBytes(ByteArray(4096))

                shouldThrow<TsVideosException> { detector.detect(file) }
            }

            expect("stops reading once EWS, superimpose and caption are all found") {
                // 進捗の途中通知は 16MB ごとなので、最後まで読めば途中通知が出る大きさにする
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt(programInfo = emergencyDescriptor(active = true), withCaption = true)),
                        packets(superimposePid, superimposePes(visibleText)),
                        packets(captionPid, captionPes(visibleText)),
                        nullPackets(100_000),
                    )
                val events = mutableListOf<Pair<Long, Long>>()

                val result = detector.detect(file) { processed, total -> events += processed to total }

                result shouldBe EmergencyBroadcastDetector.Result(ewsDetected = true, superimposeDetected = true, captionDetected = true)
                events shouldBe listOf(file.length() to file.length())
            }

            expect("keeps reading for captions after EWS and superimpose are found") {
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt(programInfo = emergencyDescriptor(active = true), withCaption = true)),
                        packets(superimposePid, superimposePes(visibleText)),
                        nullPackets(100_000),
                        packets(captionPid, captionPes(visibleText)),
                    )

                detector.detect(file) shouldBe
                    EmergencyBroadcastDetector.Result(ewsDetected = true, superimposeDetected = true, captionDetected = true)
            }

            expect("reports progress that finishes at the file size") {
                val file = tempTs(sectionPackets(0, pat()), sectionPackets(pmtPid, pmt()))
                val events = mutableListOf<Pair<Long, Long>>()

                detector.detect(file) { processed, total -> events += processed to total }

                events.last() shouldBe (file.length() to file.length())
            }
        }

        context("detect - caption") {
            fun captionTs(vararg pes: ByteArray) =
                tempTs(
                    sectionPackets(0, pat()),
                    sectionPackets(pmtPid, pmt(withSuperimpose = false, withCaption = true)),
                    *pes.map { packets(captionPid, it) }.toTypedArray(),
                )

            expect("detects caption text as a caption, not as superimpose") {
                detector.detect(captionTs(captionPes(visibleText))) shouldBe
                    EmergencyBroadcastDetector.Result(ewsDetected = false, superimposeDetected = false, captionDetected = true)
            }

            expect("detects caption text after management data and a clear-only statement") {
                val file = captionTs(captionPes(visibleText, dataGroupId = 0), captionPes(clearOnly), captionPes(visibleText))

                detector.detect(file).captionDetected shouldBe true
            }

            expect("detects caption text in the second data group set (data_group_id 0x21)") {
                detector.detect(captionTs(captionPes(visibleText, dataGroupId = 0x21))).captionDetected shouldBe true
            }

            expect("detects caption text whose PES spans several packets") {
                val longText = ByteArray(400) { 0x0d } + visibleText

                detector.detect(captionTs(captionPes(longText))).captionDetected shouldBe true
            }

            expect("ignores a caption ES that the PMT only declares") {
                detector.detect(captionTs()).captionDetected shouldBe false
            }

            expect("ignores caption management data") {
                detector.detect(captionTs(captionPes(visibleText, dataGroupId = 0))).captionDetected shouldBe false
            }

            expect("ignores caption management data in the second data group set (data_group_id 0x20)") {
                detector.detect(captionTs(captionPes(visibleText, dataGroupId = 0x20))).captionDetected shouldBe false
            }

            expect("ignores a caption stream that only clears the screen") {
                detector.detect(captionTs(captionPes(clearOnly))).captionDetected shouldBe false
            }

            expect("does not count superimpose text as a caption") {
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt(withCaption = true)),
                        packets(superimposePid, superimposePes(visibleText)),
                    )

                detector.detect(file) shouldBe
                    EmergencyBroadcastDetector.Result(ewsDetected = false, superimposeDetected = true, captionDetected = false)
            }

            expect("ignores caption text on a PID the PMT does not mark as caption") {
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt(withCaption = false)),
                        packets(captionPid, captionPes(visibleText)),
                    )

                detector.detect(file).captionDetected shouldBe false
            }

            expect("stops reading a caption PID once a PMT update removes it") {
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt(withCaption = true)),
                        sectionPackets(pmtPid, pmt(withCaption = false)),
                        packets(captionPid, captionPes(visibleText)),
                    )

                detector.detect(file).captionDetected shouldBe false
            }

            expect("starts reading a caption PID once a PMT update adds it") {
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt(withCaption = false)),
                        sectionPackets(pmtPid, pmt(withCaption = true)),
                        packets(captionPid, captionPes(visibleText)),
                    )

                detector.detect(file).captionDetected shouldBe true
            }

            context("when a PMT update reclassifies a PID in the middle of a PES") {
                // 文字は末尾のパケットにだけあるので、先頭パケットの後で分類が変わると変更前の PES の文字は変更後の分類でしか完成しない
                val longText = ByteArray(400) { 0x0d } + visibleText

                fun superimposeToCaptionTs(vararg after: ByteArray): File {
                    val oldPes = packets(superimposePid, superimposePes(longText))
                    return tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt()),
                        oldPes.copyOfRange(0, 188),
                        sectionPackets(pmtPid, pmt(superimposeComponentTag = 0x30)),
                        oldPes.copyOfRange(188, oldPes.size),
                        *after,
                    )
                }

                fun captionToSuperimposeTs(vararg after: ByteArray): File {
                    val oldPes = packets(captionPid, captionPes(longText))
                    return tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt(withSuperimpose = false, withCaption = true)),
                        oldPes.copyOfRange(0, 188),
                        sectionPackets(pmtPid, pmt(withSuperimpose = false, withCaption = true, captionComponentTag = 0x38)),
                        oldPes.copyOfRange(188, oldPes.size),
                        *after,
                    )
                }

                expect("does not count a superimpose PES begun before the change as a caption") {
                    detector.detect(superimposeToCaptionTs()) shouldBe
                        EmergencyBroadcastDetector.Result(ewsDetected = false, superimposeDetected = false, captionDetected = false)
                }

                expect("detects a fresh caption PES after the PID becomes a caption") {
                    detector.detect(superimposeToCaptionTs(packets(superimposePid, captionPes(visibleText)))) shouldBe
                        EmergencyBroadcastDetector.Result(ewsDetected = false, superimposeDetected = false, captionDetected = true)
                }

                expect("does not count a caption PES begun before the change as superimpose") {
                    detector.detect(captionToSuperimposeTs()) shouldBe
                        EmergencyBroadcastDetector.Result(ewsDetected = false, superimposeDetected = false, captionDetected = false)
                }

                expect("detects a fresh superimpose PES after the PID becomes superimpose") {
                    detector.detect(captionToSuperimposeTs(packets(captionPid, superimposePes(visibleText)))) shouldBe
                        EmergencyBroadcastDetector.Result(ewsDetected = false, superimposeDetected = true, captionDetected = false)
                }
            }

            expect("discards a caption PES broken by a continuity counter gap") {
                val pesPackets = packets(captionPid, captionPes(ByteArray(400) { 0x0d } + visibleText))
                // drop the second of the three packets so the PES is cut in the middle
                val broken = pesPackets.copyOfRange(0, 188) + pesPackets.copyOfRange(376, pesPackets.size)
                val file =
                    tempTs(
                        sectionPackets(0, pat()),
                        sectionPackets(pmtPid, pmt(withSuperimpose = false, withCaption = true)),
                        broken,
                    )

                detector.detect(file).captionDetected shouldBe false
            }
        }

        context("tagsByCheck") {
            expect("records both detectors from the single scan, each with its own tags") {
                val result = EmergencyBroadcastDetector.Result(ewsDetected = true, superimposeDetected = false, captionDetected = true)

                result.tagsByCheck() shouldBe
                    mapOf(
                        ExecutedFileCheck.EMERGENCY_BROADCAST to setOf(ExecutedFileTag.EWS),
                        ExecutedFileCheck.CAPTION to setOf(ExecutedFileTag.CAPTION),
                    )
            }

            expect("records a checked caption detector with no tag when nothing is found") {
                val result = EmergencyBroadcastDetector.Result(ewsDetected = false, superimposeDetected = true, captionDetected = false)

                result.tagsByCheck() shouldBe
                    mapOf(
                        ExecutedFileCheck.EMERGENCY_BROADCAST to setOf(ExecutedFileTag.SUPERIMPOSE),
                        ExecutedFileCheck.CAPTION to emptySet(),
                    )
            }
        }

        context("containsGraphicCharacter") {
            fun check(vararg values: Int): Boolean {
                val buf = bytes(*values)
                return AribCaptionData.containsGraphicCharacter(buf, 0, buf.size)
            }

            expect("skips control codes and their parameters") {
                // APS (0x1C P1 P2), COL (0x90 P1), ESC $ ) B (G1 に漢字を指示), SP
                check(0x1c, 0x41, 0x42, 0x90, 0x48, 0x1b, 0x24, 0x29, 0x42, 0x20) shouldBe false
            }

            expect("finds a character after control codes") {
                check(0x0c, 0x1c, 0x41, 0x42, 0x21) shouldBe true
            }
        }
    })
