package me.pinfort.tsvideos.processor.infrastructure.pipeline

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import me.pinfort.tsvideos.core.command.CreatedFileCommand
import me.pinfort.tsvideos.core.command.ExecutedFileCommand
import me.pinfort.tsvideos.core.command.ProcessingHistoryCommand
import me.pinfort.tsvideos.core.command.ProcessingJobClaimCommand
import me.pinfort.tsvideos.core.command.ProcessingJobCommand
import me.pinfort.tsvideos.core.command.ProcessingRecoveryCommand
import me.pinfort.tsvideos.core.command.ProgramCommand
import me.pinfort.tsvideos.core.command.SplittedFileCommand
import me.pinfort.tsvideos.core.command.VerifiedUploadCommand
import me.pinfort.tsvideos.core.component.DirectoryNameComponent
import me.pinfort.tsvideos.core.component.MimeTypeComponent
import me.pinfort.tsvideos.core.component.NasDestinationResolver
import me.pinfort.tsvideos.core.component.NormalizeComponent
import me.pinfort.tsvideos.core.component.ValidateCompletedComponent
import me.pinfort.tsvideos.core.domain.ProcessingIdentity
import me.pinfort.tsvideos.core.external.database.mapper.CreatedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ExecutedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingHistoryMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingJobMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProcessingRecoveryMapper
import me.pinfort.tsvideos.core.external.database.mapper.ProgramMapper
import me.pinfort.tsvideos.core.external.database.mapper.SplittedFileMapper
import me.pinfort.tsvideos.core.external.database.mapper.UploadManifestMapper
import me.pinfort.tsvideos.core.external.samba.NasComponent
import me.pinfort.tsvideos.core.external.samba.SambaClient
import me.pinfort.tsvideos.core.external.samba.VerifiedUploadNas
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@MybatisTest
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecoveryRestartTest : ExpectSpec() {
    @Autowired private lateinit var db: DataSource

    @Autowired private lateinit var tx: PlatformTransactionManager

    @Autowired private lateinit var jobMapper: ProcessingJobMapper

    @Autowired private lateinit var recoveryMapper: ProcessingRecoveryMapper

    @Autowired private lateinit var historyMapper: ProcessingHistoryMapper

    @Autowired private lateinit var executedMapper: ExecutedFileMapper

    @Autowired private lateinit var splitMapper: SplittedFileMapper

    @Autowired private lateinit var programMapper: ProgramMapper

    @Autowired private lateinit var createdMapper: CreatedFileMapper

    @Autowired private lateinit var uploadMapper: UploadManifestMapper

    private fun sql(text: String) {
        db.connection.use { c ->
            c.autoCommit = true
            c.createStatement().use { it.executeUpdate(text) }
        }
    }

    init {
        expect("a new callback runner resumes persisted partial uploads after local output deletion") {
            listOf(
                "processing_checkpoint_invalidation",
                "processing_checkpoint",
                "processing_callback_input",
                "processing_stage_event",
                "processing_attempt",
                "processing_history_identity",
                "upload_manifest",
                "processing_job",
                "created_file",
                "splitted_file",
                "program",
                "executed_file",
            ).forEach {
                sql("DELETE FROM $it")
            }
            val root = Files.createTempDirectory("recovery-restart").toFile()
            val original = File(root, "raw.m2ts").apply { writeText("original") }
            val splitDir = File(root, "tssplitter").apply { mkdirs() }
            val split = File(splitDir, "main.m2ts").apply { writeText("split") }
            val moved = File(File(splitDir, "succeeded").apply { mkdirs() }, "main.m2ts").apply { writeText("split") }
            val outputDir = File(splitDir, "encoded").apply { mkdirs() }
            val one = File(outputDir, "one.mp4").apply { writeText("encoded one") }
            val two = File(outputDir, "two.mp4").apply { writeText("encoded two") }
            val archive = File(splitDir, "main.m2ts.gz").apply { writeText("archive bytes") }

            fun quoted(file: File) = file.canonicalPath.replace("\\", "\\\\").replace("'", "''")
            val identity = ProcessingIdentity.from(original)
            sql(
                "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) VALUES(1,'${quoted(
                    original,
                )}',0,${original.length()},'2026-01-01','1','title','channel',60,'SPLITTED')",
            )
            sql("INSERT INTO program(id,name,executed_file_id,status) VALUES(1,'raw.m2ts',1,'REGISTERED')")
            sql(
                "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(1,1,'${quoted(
                    split,
                )}',${split.length()},60,'COMPRESS_SAVED')",
            )
            sql(
                "INSERT INTO processing_job(id,identity_key,input_path,input_size,modified_millis,state,executed_file_id) " +
                    "VALUES(1,'${identity.key}','${quoted(
                        original,
                    )}',${identity.size},${identity.modifiedMillis},'SUBMITTED',1)",
            )
            val remote = mutableMapOf<String, ByteArray>()
            val nas = mockk<VerifiedUploadNas>()
            every { nas.exists(any(), any()) } answers { remote.containsKey(secondArg()) }
            every { nas.size(any(), any()) } answers { remote.getValue(secondArg()).size.toLong() }
            every { nas.sha256(any(), any()) } answers
                { MessageDigest.getInstance("SHA-256").digest(remote.getValue(secondArg())).joinToString("") { "%02x".format(it) } }
            every { nas.delete(any(), any()) } answers {
                remote.remove(secondArg())
                Unit
            }
            var failSecond = true
            every { nas.transfer(any(), any(), any(), any(), any()) } answers {
                arg<() -> Unit>(3).invoke()
                remote[secondArg()] = thirdArg<File>().readBytes()
                if (failSecond && secondArg<String>().contains("two.mp4")) throw java.io.IOException("offline")
                Unit
            }
            every { nas.finalize(any(), any(), any()) } answers {
                remote[thirdArg()] = remote.remove(secondArg())!!
                Unit
            }
            val samba = mockk<SambaClient>()
            every { samba.resolvePathUnderBaseDir(any(), any()) } answers { "base/${secondArg<String>()}" }
            val resources = mockk<NasComponent>()
            every { resources.resourceExists(any()) } answers { remote.containsKey(firstArg()) }
            val logger = mockk<org.slf4j.Logger>(relaxed = true)
            val files = CreatedFileCommand(createdMapper, samba, resources, logger)
            val executed = ExecutedFileCommand(executedMapper, logger)
            val splits = SplittedFileCommand(splitMapper, logger)
            val programs =
                ProgramCommand(
                    programMapper,
                    createdMapper,
                    executed,
                    files,
                    splitMapper,
                    logger,
                    splits,
                    DirectoryNameComponent(NormalizeComponent()),
                )

            fun uploads(): VerifiedUploadCommand {
                val interceptor =
                    org.springframework.transaction.interceptor
                        .TransactionInterceptor()
                interceptor.transactionManager = tx
                interceptor.transactionAttributeSource =
                    org.springframework.transaction.annotation
                        .AnnotationTransactionAttributeSource()
                return org.springframework.aop.framework
                    .ProxyFactory(
                        VerifiedUploadCommand(uploadMapper, nas, files, createdMapper, tx),
                    ).apply {
                        isProxyTargetClass = true
                        addAdvice(interceptor)
                    }.proxy as VerifiedUploadCommand
            }

            fun recovery() = ProcessingRecoveryCommand(recoveryMapper, jobMapper, executedMapper, splitMapper, programMapper, uploads(), tx)

            fun runner() =
                AfterEncodeRunner(
                    splits,
                    executed,
                    programs,
                    NasDestinationResolver(NormalizeComponent(), samba),
                    MimeTypeComponent(),
                    ValidateCompletedComponent(programs, splits, files, resources, logger),
                    mockk(relaxed = true),
                    logger,
                    ProcessingJobCommand(jobMapper, ProcessingJobClaimCommand(jobMapper)),
                    uploads(),
                    ProcessingHistoryCommand(historyMapper, tx),
                    recovery(),
                )
            try {
                val archiveReceipt = uploads().upload(1, "original", archive, SambaClient.NasType.ORIGINAL_STORE_NAS, "base/main.m2ts.gz")
                uploads().register(archiveReceipt, 1, "video/vnd.dlna.mpeg-tts", "gzip")
                runner().run(AfterEncodeRunner.Input(9, moved.toPath(), listOf(one.toPath(), two.toPath()), true, ""))
                one.exists() shouldBe false
                two.exists() shouldBe true
                original.exists() shouldBe true
                recoveryMapper.active(1) shouldBe 0
                recoveryMapper.callback(1)!!.contains("one.mp4") shouldBe true
                val secondManifest = uploadMapper.find(1, ProcessingIdentity.hash("encoded:${two.canonicalPath}"))!!
                secondManifest.state shouldBe "TRANSFERRING"
                val firstManifest = uploadMapper.find(1, ProcessingIdentity.hash("encoded:${one.canonicalPath}"))!!
                val goodBytes = remote.getValue(firstManifest.finalPath)
                remote[firstManifest.finalPath] = ByteArray(goodBytes.size) { 42 }
                recovery().plan(1).blocked shouldBe true
                remote[firstManifest.finalPath] = goodBytes
                every { nas.exists(any(), firstManifest.finalPath) } throws java.io.IOException("inaccessible")
                recovery()
                    .plan(1)
                    .stages
                    .first { it.stage.contains("one.mp4") }
                    .reason shouldBe "ARTIFACT_INACCESSIBLE"
                every { nas.exists(any(), firstManifest.finalPath) } answers { remote.containsKey(firstManifest.finalPath) }
                val victim = File(root, "victim.m2ts").apply { writeText("do not delete") }
                sql("UPDATE executed_file SET file='${victim.canonicalPath.replace("\\", "\\\\")}' WHERE id=1")
                recovery().plan(1).blocked shouldBe true
                io.kotest.assertions.throwables
                    .shouldThrow<me.pinfort.tsvideos.core.domain.ResumeBlocked> { runner().resume(1) }
                victim.readText() shouldBe "do not delete"
                sql("UPDATE executed_file SET file='${original.canonicalPath.replace("\\", "\\\\")}' WHERE id=1")
                failSecond = false
                val before = recoveryMapper.attempts(1)
                val plan = recovery().plan(1)
                plan.blocked shouldBe false
                plan.stages.first { it.stage.contains("one.mp4") }.action shouldBe "SKIP"
                recoveryMapper.attempts(1) shouldBe before
                runner().resume(1)
                jobMapper.find(identity.key)!!.state shouldBe "COMPLETED"
                original.exists() shouldBe false
                split.exists() shouldBe false
                moved.exists() shouldBe false
                two.exists() shouldBe false
                createdMapper.selectBySplittedFileId(1).size shouldBe 3
                sql("UPDATE created_file SET mime='video/changed' WHERE id=${firstManifest.createdFileId}")
                recovery().plan(1).blocked shouldBe true
                sql("UPDATE created_file SET mime='video/mp4' WHERE id=${firstManifest.createdFileId}")
                recovery().plan(1).blocked shouldBe false
                runner().run(AfterEncodeRunner.Input(9, moved.toPath(), listOf(one.toPath(), two.toPath()), true, ""))
                createdMapper.selectBySplittedFileId(1).size shouldBe 3

                val raw = File(root, "[210708-0030][BSBS13_1][channel]title.m2ts").apply { writeText("new recording") }
                val select = mockk<me.pinfort.tsvideos.processor.infrastructure.external.tsselect.TsSelectClient>()
                every { select.check(any(), any()) } returns 0
                val splitter = mockk<me.pinfort.tsvideos.core.external.tool.TsSplitterClient>()
                every { splitter.split(any(), any(), any()) } answers {
                    val directory = secondArg<File>().apply { mkdirs() }
                    File(directory, "${firstArg<File>().nameWithoutExtension}_1.m2ts").writeText("split content")
                    0
                }
                val probe = mockk<me.pinfort.tsvideos.core.external.tool.DurationProbeClient>()
                every { probe.probe(any(), any()) } returns 100.0
                val encoder = mockk<me.pinfort.tsvideos.core.external.tool.AmatsukazeAddTaskClient>()
                every { encoder.addTask(any(), any(), any()) } returns 0
                val properties =
                    me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties(
                        "split",
                        "encoder",
                        "probe",
                        me.pinfort.tsvideos.core.config.ProcessorToolConfigurationProperties
                            .Amatsukaze("localhost", 1234, "default", "atx"),
                    )

                fun pipeline() =
                    FileProcessingPipeline(
                        executed,
                        splits,
                        programs,
                        select,
                        splitter,
                        encoder,
                        probe,
                        me.pinfort.tsvideos.core.component
                            .MainSplittedFileFinderComponent(),
                        me.pinfort.tsvideos.core.component
                            .CompressComponent(logger),
                        NasDestinationResolver(NormalizeComponent(), samba),
                        properties,
                        logger,
                        ProcessingJobCommand(jobMapper, ProcessingJobClaimCommand(jobMapper)),
                        uploads(),
                        ProcessingHistoryCommand(historyMapper, tx),
                        recovery(),
                    )
                var failArchive = true
                every { nas.transfer(any(), any(), any(), any(), any()) } answers {
                    arg<() -> Unit>(3).invoke()
                    remote[secondArg()] = thirdArg<File>().readBytes()
                    if (failArchive) throw java.io.IOException("archive offline")
                    Unit
                }
                io.kotest.assertions.throwables
                    .shouldThrow<java.io.IOException> { pipeline().processFile(raw) }
                val processing = jobMapper.find(ProcessingIdentity.from(raw).key)!!
                val processPlan = recovery().plan(processing.id)
                processPlan.blocked shouldBe false
                processPlan.stages
                    .filter {
                        it.stage in
                            listOf(
                                "INSPECT",
                                "REGISTER",
                                "SPLIT",
                                "COMPRESS",
                            )
                    }.all { it.action == "SKIP" } shouldBe
                    true
                failArchive = false
                pipeline().resume(processing.id) shouldBe FileProcessingPipeline.Result.PROCESSED
                jobMapper.find(processing.identityKey)!!.state shouldBe "SUBMITTED"
                io.mockk.verify(exactly = 1) { splitter.split(any(), any(), any()) }
                io.mockk.verify(exactly = 1) { select.check(any(), any()) }
                io.mockk.verify(exactly = 1) { encoder.addTask(any(), any(), any()) }
                raw.exists() shouldBe true
                recovery().plan(processing.id).blocked shouldBe true
            } finally {
                root.deleteRecursively()
            }
        }
    }
}
