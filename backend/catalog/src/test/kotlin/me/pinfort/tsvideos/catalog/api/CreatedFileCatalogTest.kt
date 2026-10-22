package me.pinfort.tsvideos.catalog.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.TestLoggerConfig
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.VideoResolution
import me.pinfort.tsvideos.catalog.persistence.CreatedFileMapper
import me.pinfort.tsvideos.catalog.persistence.GeneratedKeyHolder
import me.pinfort.tsvideos.storage.api.NasStore
import org.mybatis.spring.boot.test.autoconfigure.MybatisTest
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.boot.testcontainers.context.ImportTestcontainers
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import javax.sql.DataSource

@ImportTestcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@MybatisTest
@SpringJUnitConfig
@Import(CreatedFileCatalog::class, TestLoggerConfig::class)
@ActiveProfiles("infrastructure")
@ApplyExtension(SpringExtension::class)
class CreatedFileCatalogTest : ExpectSpec() {
    @Autowired private lateinit var catalog: CreatedFileCatalog

    @Autowired private lateinit var files: CreatedFileMapper

    @Autowired private lateinit var transactions: PlatformTransactionManager

    @Autowired private lateinit var dataSource: DataSource

    init {
        // Seeded splitted_file row (02_insert_data.sql).
        val splitId = 1L

        fun uniquePath() = "catalog-test/${UUID.randomUUID()}.mp4"

        fun rowsAt(path: String) = files.selectBySplittedFileId(splitId).filter { it.file == path }

        fun insertRow(
            path: String,
            mime: String?,
            resolution: VideoResolution? = null,
        ): Long {
            val key = GeneratedKeyHolder()
            files.insert(splitId, path, 10, mime, null, CreatedFile.Status.FILE_MOVED.name, key, resolution?.width, resolution?.height)
            return key.id
        }

        // Built on use: the transaction manager is injected only after this spec body has run.
        val required by lazy { TransactionTemplate(transactions) }
        val requiresNew by lazy {
            TransactionTemplate(transactions).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
        }

        expect("the catalog bean is transactional, so MANDATORY is enforced") {
            AopUtils.isAopProxy(catalog) shouldBe true
        }

        context("register") {
            expect("fails outside a transaction and inserts nothing") {
                val path = uniquePath()

                shouldThrow<IllegalTransactionStateException> { catalog.register(splitId, path, 10, "video/mp4", null) }

                rowsAt(path) shouldBe emptyList()
            }

            expect("commits with the caller's transaction and returns the stored row") {
                val path = uniquePath()
                val registered =
                    checkNotNull(
                        required.execute { catalog.register(splitId, path, 10, "video/mp4", "enc", VideoResolution(1920, 1080)) },
                    )
                try {
                    registered shouldBe
                        CreatedFile(
                            id = registered.id,
                            splittedFileId = splitId,
                            file = path,
                            size = 10,
                            mime = "video/mp4",
                            encoding = "enc",
                            status = CreatedFile.Status.FILE_MOVED,
                            width = 1920,
                            height = 1080,
                        )
                    (registered.id > 0) shouldBe true
                    catalog.find(registered.id) shouldBe registered
                } finally {
                    files.delete(registered.id)
                }
            }

            expect("registers optional metadata as missing when none is given") {
                val path = uniquePath()
                val registered = checkNotNull(required.execute { catalog.register(splitId, path, 7, null, null) })
                try {
                    val stored = catalog.find(registered.id).shouldNotBeNull()
                    stored shouldBe registered
                    stored.size shouldBe 7
                    stored.mime shouldBe null
                    stored.encoding shouldBe null
                    stored.width shouldBe null
                    stored.height shouldBe null
                } finally {
                    files.delete(registered.id)
                }
            }

            expect("rolls back with the caller when the caller fails after registering") {
                val path = uniquePath()

                shouldThrow<IllegalStateException> {
                    required.execute {
                        catalog.register(splitId, path, 10, "video/mp4", null)
                        error("caller failed after registering")
                    }
                }

                rowsAt(path) shouldBe emptyList()
            }

            expect("does not commit on its own: an outer rollback undoes a registration made in a nested REQUIRED call") {
                val path = uniquePath()
                required.execute { status ->
                    required.execute { catalog.register(splitId, path, 10, "video/mp4", null) }
                    // Visible inside the caller's transaction, but not committed yet.
                    rowsAt(path).size shouldBe 1
                    status.setRollbackOnly()
                }

                rowsAt(path) shouldBe emptyList()
            }

            expect("a registration in an independent REQUIRES_NEW transaction is not undone by the suspended caller") {
                val path = uniquePath()
                val registered =
                    required
                        .execute { status ->
                            requiresNew
                                .execute { catalog.register(splitId, path, 10, "video/mp4", null) }
                                .also { status.setRollbackOnly() }
                        }.shouldNotBeNull()
                try {
                    rowsAt(path).size shouldBe 1
                } finally {
                    files.delete(registered.id)
                }
            }
        }

        context("metadata fill") {
            expect("fails outside a transaction") {
                val id = insertRow(uniquePath(), null)
                try {
                    shouldThrow<IllegalTransactionStateException> { catalog.fillMimeIfMissing(id, "video/mp4") }
                    shouldThrow<IllegalTransactionStateException> { catalog.fillResolutionIfMissing(id, VideoResolution(1, 1)) }
                    catalog.find(id)?.mime shouldBe null
                } finally {
                    files.delete(id)
                }
            }

            expect("fills only missing values and reports whether it changed the row") {
                val id = insertRow(uniquePath(), null)
                try {
                    required.execute { catalog.fillMimeIfMissing(id, "video/mp4") } shouldBe true
                    required.execute { catalog.fillMimeIfMissing(id, "video/other") } shouldBe false
                    required.execute { catalog.fillResolutionIfMissing(id, VideoResolution(1280, 720)) } shouldBe true
                    required.execute { catalog.fillResolutionIfMissing(id, VideoResolution(1920, 1080)) } shouldBe false
                    val stored = catalog.find(id).shouldNotBeNull()
                    stored.mime shouldBe "video/mp4"
                    stored.width shouldBe 1280
                    stored.height shouldBe 720
                } finally {
                    files.delete(id)
                }
            }

            expect("rolls back with the caller") {
                val id = insertRow(uniquePath(), null)
                try {
                    required.execute { status ->
                        catalog.fillMimeIfMissing(id, "video/mp4") shouldBe true
                        status.setRollbackOnly()
                    }
                    catalog.find(id)?.mime shouldBe null
                } finally {
                    files.delete(id)
                }
            }
        }

        // library's verification relies on these orders (core's LibraryVerificationCommandTest mocks the catalog).
        context("recording reads") {
            fun sql(vararg statements: String) {
                dataSource.connection.use { connection ->
                    connection.autoCommit = true
                    connection.createStatement().use { statement -> statements.forEach { statement.executeUpdate(it) } }
                }
            }

            expect("list a recording's files sorted by ID and group every file by its recording") {
                sql(
                    "INSERT INTO executed_file(id,file,drops,size,recorded_at,channel,title,channelName,duration,status) " +
                        "VALUES(9101,'recording.m2ts',0,1,'2026-01-01','1','title','channel',1,'SPLITTED')",
                    "INSERT INTO splitted_file(id,executed_file_id,file,size,duration,status) VALUES(9102,9101,'split',1,1,'ENCODE_TASK_ADDED')",
                    "INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES(9104,9102,'second',2,'FILE_MOVED'),(9103,9102,'first',1,'REGISTERED')",
                )
                try {
                    catalog.filesOfRecording(9101).map { it.id to it.file } shouldBe listOf(9103L to "first", 9104L to "second")
                    catalog
                        .filesByRecording()[9101L]
                        .orEmpty()
                        .map { it.id }
                        .sorted() shouldBe listOf(9103L, 9104L)
                    catalog.filesOfRecording(9199) shouldBe emptyList()
                } finally {
                    sql(
                        "DELETE FROM created_file WHERE splitted_file_id=9102",
                        "DELETE FROM splitted_file WHERE id=9102",
                        "DELETE FROM executed_file WHERE id=9101",
                    )
                }
            }
        }

        context("findStreamableVideo") {
            expect("an mp4 row is played from VIDEO_STORE_NAS at its recorded path, unchanged") {
                val path = "catalog-test\\${UUID.randomUUID()}.mp4"
                val id = insertRow(path, "video/mp4")
                try {
                    catalog.findStreamableVideo(id) shouldBe StreamableVideo(id, NasStore.VIDEO_STORE_NAS, path)
                } finally {
                    files.delete(id)
                }
            }

            expect("a row that is not video/mp4 and a missing row are not streamable") {
                val id = insertRow(uniquePath(), "video/vnd.dlna.mpeg-tts")
                try {
                    catalog.findStreamableVideo(id) shouldBe null
                    catalog.findStreamableVideo(Long.MAX_VALUE) shouldBe null
                } finally {
                    files.delete(id)
                }
            }
        }
    }
}
