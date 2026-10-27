package me.pinfort.tsvideos.processing.pipeline

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.catalog.api.CreatedFileCatalog
import me.pinfort.tsvideos.catalog.api.CreatedFileCommand
import me.pinfort.tsvideos.catalog.api.DirectoryNameComponent
import me.pinfort.tsvideos.catalog.api.ExecutedFileCommand
import me.pinfort.tsvideos.catalog.api.ExecutedFileTagCommand
import me.pinfort.tsvideos.catalog.api.LedgerLocks
import me.pinfort.tsvideos.catalog.api.NormalizeComponent
import me.pinfort.tsvideos.catalog.api.ProgramCommand
import me.pinfort.tsvideos.catalog.api.SplittedFileCommand
import me.pinfort.tsvideos.processing.TestLoggerConfig
import me.pinfort.tsvideos.processing.api.CompressComponent
import me.pinfort.tsvideos.processing.api.MainSplittedFileFinderComponent
import me.pinfort.tsvideos.processing.api.NasDestinationResolver
import me.pinfort.tsvideos.processing.api.ProcessingHistoryCommand
import me.pinfort.tsvideos.processing.api.ProcessingJobClaimCommand
import me.pinfort.tsvideos.processing.api.ProcessingJobCommand
import me.pinfort.tsvideos.processing.api.RecordingQualityCommand
import me.pinfort.tsvideos.processing.api.RecordingQualityInspector
import me.pinfort.tsvideos.processing.api.VerifiedUploadCommand
import me.pinfort.tsvideos.processing.api.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.processing.api.tool.AmatsukazeAddTaskClient
import me.pinfort.tsvideos.processing.api.tool.DurationProbeClient
import me.pinfort.tsvideos.processing.api.tool.TsSplitterClient
import me.pinfort.tsvideos.processing.domain.ProcessingIdentity
import me.pinfort.tsvideos.processing.domain.ProcessingJob
import me.pinfort.tsvideos.processing.domain.UploadManifest
import me.pinfort.tsvideos.processing.external.ts.EmergencyBroadcastDetector
import me.pinfort.tsvideos.processing.external.tsselect.TsSelectClient
import me.pinfort.tsvideos.processing.persistence.ProcessingHistoryMapper
import me.pinfort.tsvideos.processing.persistence.UploadManifestMapper
import me.pinfort.tsvideos.storage.api.NasComponent
import me.pinfort.tsvideos.storage.api.NasPaths
import me.pinfort.tsvideos.storage.api.VerifiedUploadNas
import me.pinfort.tsvideos.storage.api.sha256Hex
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.slf4j.Logger
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.sql.DataSource

/**
 * The pipeline against the real ledger, job, history and upload-manifest tables (#108): once the original-store upload
 * has started, a failure must leave the registration, split, created_file and upload evidence in the database and the
 * local files in place, and the job blocked for reconciliation rather than released for retry.
 * Only the external tools and the NAS are faked.
 */
@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@MybatisTest
@Import(PipelineEvidenceConfiguration::class)
@SpringJUnitConfig
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class FileProcessingPipelineEvidenceTest : ExpectSpec() {
    companion object {
        /**
         * A private database: other specs leave rows behind in the shared one (e.g. created_file rows for split ids
         * that do not exist yet), which this spec's ledger counts would pick up once its own split takes such an id.
         * Testcontainers starts one container per distinct JDBC URL, so the same URL as application-infrastructure.yml
         * (DATABASE_CONNECTION or its default) with another database name gets a fresh, migrated database.
         */
        private const val SHARED_DATABASE = "jdbc:tc:mariadb:11.8:///?TC_INITSCRIPT=ddl/01_create_database.sql"

        @JvmStatic
        @DynamicPropertySource
        fun isolatedDatabase(registry: DynamicPropertyRegistry) {
            val shared = System.getenv("DATABASE_CONNECTION") ?: SHARED_DATABASE
            registry.add("spring.datasource.url") { shared.replace(Regex(":///[^?]*"), ":///pipeline_evidence") }
        }
    }

    @Autowired private lateinit var executedFiles: ExecutedFileCommand

    @Autowired private lateinit var executedFileTags: ExecutedFileTagCommand

    @Autowired private lateinit var splittedFiles: SplittedFileCommand

    @Autowired private lateinit var programs: ProgramCommand

    @Autowired private lateinit var createdFiles: CreatedFileCatalog

    @Autowired private lateinit var jobs: ProcessingJobCommand

    @Autowired private lateinit var histories: ProcessingHistoryMapper

    @Autowired private lateinit var manifests: UploadManifestMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var dataSource: DataSource

    private fun count(
        sql: String,
        id: Long,
    ): Long =
        dataSource.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use {
                    it.next()
                    it.getLong(1)
                }
            }
        }

    private data class Fixture(
        val pipeline: FileProcessingPipeline,
        val original: File,
        val nas: VerifiedUploadNas,
        val encoder: AmatsukazeAddTaskClient,
        val remote: MutableMap<String, ByteArray>,
    ) {
        val splitFile get() = File(File(original.parentFile, "tssplitter"), "${original.nameWithoutExtension}_1.m2ts")
        val archive get() = File(splitFile.parentFile, "${splitFile.name}.gz")
    }

    private fun ByteArray.sha256() = inputStream().sha256Hex()

    // Only the directories fixture() created; removed after the spec.
    private val directories = mutableListOf<File>()

    private fun fixture(): Fixture {
        val directory = Files.createTempDirectory("pipeline-evidence").toFile().also { directories += it }
        val original = File(directory, "[210708-0030][BSBS13_1][channel]evidence-${UUID.randomUUID()}.m2ts")
        original.writeText("original ts content")
        val remote = mutableMapOf<String, ByteArray>()
        val nas = mockk<VerifiedUploadNas>()
        every { nas.exists(any(), any()) } answers { remote.containsKey(secondArg()) }
        every { nas.size(any(), any()) } answers { remote.getValue(secondArg()).size.toLong() }
        every { nas.sha256(any(), any()) } answers { remote.getValue(secondArg()).sha256() }
        every { nas.transfer(any(), any(), any(), any(), any()) } answers {
            val path = secondArg<String>()
            check(!remote.containsKey(path))
            arg<() -> Unit>(3)()
            arg<File>(2).readBytes().also { remote[path] = it }.sha256()
        }
        every { nas.finalize(any(), any(), any()) } answers {
            check(!remote.containsKey(thirdArg()))
            remote[thirdArg()] = checkNotNull(remote.remove(secondArg()))
        }
        every { nas.delete(any(), any()) } answers { remote.remove(secondArg<String>()) }
        val tsSelect = mockk<TsSelectClient>()
        every { tsSelect.check(any(), any()) } returns 0
        val emergency = mockk<EmergencyBroadcastDetector>()
        every { emergency.detect(any(), any()) } returns EmergencyBroadcastDetector.Result(ewsDetected = false, superimposeDetected = false)
        val duration = mockk<DurationProbeClient>()
        every { duration.probe(any(), any()) } returns 100.0
        val splitter = mockk<TsSplitterClient>()
        every { splitter.split(any(), any(), any()) } answers {
            val outDir = secondArg<File>().apply { mkdirs() }
            File(outDir, "${original.nameWithoutExtension}_1.m2ts").writeText("split content")
            0
        }
        val encoder = mockk<AmatsukazeAddTaskClient>()
        val nasPaths = mockk<NasPaths>()
        every { nasPaths.resolvePathUnderBaseDir(any(), any()) } answers { "evidence/${secondArg<String>()}" }
        val logger = mockk<Logger>(relaxed = true)
        val quality = mockk<RecordingQualityInspector>()
        every { quality.enabled() } returns false
        val pipeline =
            FileProcessingPipeline(
                executedFileCommand = executedFiles,
                executedFileTagCommand = executedFileTags,
                splittedFileCommand = splittedFiles,
                programCommand = programs,
                tsSelectClient = tsSelect,
                emergencyBroadcastDetector = emergency,
                tsSplitterClient = splitter,
                amatsukazeAddTaskClient = encoder,
                durationProbeClient = duration,
                mainSplittedFileFinderComponent = MainSplittedFileFinderComponent(),
                compressComponent = CompressComponent(logger),
                nasDestinationResolver = NasDestinationResolver(NormalizeComponent(), nasPaths),
                processorToolConfigurationProperties =
                    ProcessorToolConfigurationProperties(
                        tsSplitterPath = "tsSplitter",
                        amatsukazeAddTaskPath = "amatsukaze",
                        ffprobePath = "ffprobe",
                        amatsukaze =
                            ProcessorToolConfigurationProperties.Amatsukaze(
                                host = "localhost",
                                port = 32768,
                                defaultProfile = "30fps_light",
                                atxDivProfile = "30fps_light_atx_div",
                            ),
                    ),
                logger = logger,
                processingJobCommand = jobs,
                verifiedUploadCommand = VerifiedUploadCommand(manifests, nas, createdFiles, transactions),
                history = ProcessingHistoryCommand(histories, transactions, logger),
                quality = quality,
                qualityReports = mockk<RecordingQualityCommand>(relaxed = true),
            )
        return Fixture(pipeline, original, nas, encoder, remote)
    }

    private fun Fixture.job(): ProcessingJob = checkNotNull(jobs.findByPath(original.canonicalPath))

    private fun Fixture.executedFileId(): Long = checkNotNull(executedFiles.findByFile(original.canonicalPath)).id

    private fun Fixture.originalUpload(): UploadManifest? = manifests.find(job().id, ProcessingIdentity.hash("original"))

    // The registration and split rows, the job link and the rerun guard that every post-upload failure must leave behind.
    private fun Fixture.shouldKeepRegistrationAndSplit() {
        val executedId = executedFileId()
        programs.findByExecutedFileId(executedId).shouldNotBeNull()
        splittedFiles.selectByExecutedFileId(executedId).map { File(it.file).canonicalFile } shouldBe listOf(splitFile.canonicalFile)
        job().executedFileId shouldBe executedId
        original.readText() shouldBe "original ts content"
        splitFile.readText() shouldBe "split content"
        // The next scan is blocked by the job instead of registering, splitting or uploading again.
        shouldThrow<IllegalStateException> { pipeline.processFile(original) }.message shouldContain "Job ${job().id} is ${job().state}"
        executedFileId() shouldBe executedId
    }

    private fun Fixture.createdFileRows(): Long =
        count(
            "SELECT COUNT(*) FROM created_file c JOIN splitted_file s ON s.id = c.splitted_file_id WHERE s.executed_file_id = ?",
            executedFileId(),
        )

    init {
        afterSpec { directories.forEach { it.deleteRecursively() } }

        expect("runs against its private database rather than the one shared with other specs") {
            dataSource.connection.use { it.catalog } shouldBe "pipeline_evidence"
        }

        expect("a NAS failure during the original upload keeps every DB record, the local archive and the upload manifest") {
            val f = fixture()
            every { f.nas.finalize(any(), any(), any()) } throws IllegalStateException("NAS rename failed")

            shouldThrow<IllegalStateException> { f.pipeline.processFile(f.original) }.message shouldBe "NAS rename failed"

            f.job().state shouldBe ProcessingJob.State.CLAIMED
            f.shouldKeepRegistrationAndSplit()
            f.originalUpload().shouldNotBeNull().state shouldBe UploadManifest.State.FINALIZING
            f.createdFileRows() shouldBe 0
            f.archive.exists() shouldBe true
            verify(exactly = 0) { f.encoder.addTask(any(), any(), any()) }
        }

        expect("an encoder failure after the upload was registered keeps the created_file row and the encoder input") {
            val f = fixture()
            every { f.encoder.addTask(any(), any(), any()) } throws RuntimeException("encoder unreachable")

            shouldThrow<RuntimeException> { f.pipeline.processFile(f.original) }.message shouldBe "encoder unreachable"

            f.job().state shouldBe ProcessingJob.State.SUBMISSION_UNKNOWN
            f.shouldKeepRegistrationAndSplit()
            f.originalUpload().shouldNotBeNull().state shouldBe UploadManifest.State.REGISTERED
            f.createdFileRows() shouldBe 1
            // The registered, verified NAS copy replaced the local archive; the encoder is not asked again.
            f.archive.exists() shouldBe false
            f.remote.keys.single() shouldContain f.archive.name
            verify(exactly = 1) { f.encoder.addTask(any(), any(), any()) }
        }
    }
}

// The catalog's commands are imported as beans, so their transactions behave as in production. Only the NAS is mocked.
@TestConfiguration
@Import(
    ExecutedFileCommand::class,
    ExecutedFileTagCommand::class,
    SplittedFileCommand::class,
    CreatedFileCommand::class,
    ProgramCommand::class,
    LedgerLocks::class,
    DirectoryNameComponent::class,
    NormalizeComponent::class,
    CreatedFileCatalog::class,
    ProcessingJobCommand::class,
    ProcessingJobClaimCommand::class,
    TestLoggerConfig::class,
)
internal class PipelineEvidenceConfiguration {
    @Bean
    fun nasComponent(): NasComponent = mockk()
}
