package me.pinfort.tsvideos.catalog.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.catalog.TestLoggerConfig
import me.pinfort.tsvideos.catalog.domain.CreatedFile
import me.pinfort.tsvideos.catalog.domain.VideoChapter
import me.pinfort.tsvideos.catalog.domain.VideoChapters
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
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    private fun execute(statement: String) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.createStatement().use { it.executeUpdate(statement) }
        }
    }

    init {
        // This spec's own split: registration refuses a split that does not exist, and other specs sharing the container
        // clear the seeded rows (02_insert_data.sql).
        val splitId = 9201L

        beforeTest {
            execute(
                "INSERT IGNORE INTO splitted_file(id,executed_file_id,file,size,duration,status) " +
                    "VALUES($splitId,9200,'catalog-test-split',1,1,'ENCODE_TASK_ADDED')",
            )
        }
        afterSpec { execute("DELETE FROM splitted_file WHERE id = $splitId") }

        fun uniquePath() = "catalog-test/${UUID.randomUUID()}.mp4"

        fun rowsAt(path: String) = files.selectBySplittedFileId(splitId).filter { it.file == path }

        fun insertRow(
            path: String,
            mime: String?,
            resolution: VideoResolution? = null,
        ): Long {
            val key = GeneratedKeyHolder()
            files.insert(
                splitId,
                path,
                CreatedFilePathKey.hash(path),
                10,
                mime,
                null,
                CreatedFile.Status.FILE_MOVED.name,
                key,
                resolution?.width,
                resolution?.height,
            )
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

        context("path keys") {
            fun pathKeyOf(id: Long): ByteArray? =
                dataSource.connection.use { connection ->
                    connection.prepareStatement("SELECT path_key FROM created_file WHERE id = ?").use { statement ->
                        statement.setLong(1, id)
                        statement.executeQuery().use { result ->
                            check(result.next()) { "row $id missing" }
                            result.getBytes(1)
                        }
                    }
                }

            fun rawInsert(
                id: Long,
                path: String,
            ) = dataSource.connection.use { connection ->
                connection.autoCommit = true
                connection
                    .prepareStatement("INSERT INTO created_file(id,splitted_file_id,file,size,status) VALUES(?,?,?,10,'FILE_MOVED')")
                    .use { statement ->
                        statement.setLong(1, id)
                        statement.setLong(2, splitId)
                        statement.setString(3, path)
                        statement.executeUpdate()
                    }
            }

            expect("register stores the key shared by every alias of its path") {
                val name = UUID.randomUUID().toString()
                val registered = checkNotNull(required.execute { catalog.register(splitId, "catalog-test/$name/file.mp4", 10, null, null) })
                try {
                    val stored = pathKeyOf(registered.id).shouldNotBeNull()
                    val aliases = listOf("CATALOG-TEST\\$name\\FILE.MP4", "catalog-test./$name /file.mp4. .", "Catalog-Test/$name/File.Mp4")
                    for (alias in aliases) {
                        stored.toList() shouldBe CreatedFilePathKey.hash(alias).toList()
                    }
                    (stored.toList() == CreatedFilePathKey.hash("catalog-test/$name/other.mp4").toList()) shouldBe false
                } finally {
                    files.delete(registered.id)
                }
            }

            expect("rows written without a key are filled in by id with a cursor, only inside a transaction") {
                val path = uniquePath()
                rawInsert(990001, path)
                rawInsert(990002, "$path.second")
                try {
                    pathKeyOf(990001) shouldBe null
                    shouldThrow<IllegalTransactionStateException> { catalog.fillMissingPathKeys(990000, 1) }
                    required.execute { catalog.fillMissingPathKeys(990000, 1) } shouldBe 990001L
                    pathKeyOf(990001).shouldNotBeNull().toList() shouldBe CreatedFilePathKey.hash(path).toList()
                    pathKeyOf(990002) shouldBe null
                    required.execute { catalog.fillMissingPathKeys(990001, 10) } shouldBe null
                    pathKeyOf(990002).shouldNotBeNull().toList() shouldBe CreatedFilePathKey.hash("$path.second").toList()
                } finally {
                    files.delete(990001)
                    files.delete(990002)
                }
            }

            expect("migration 076 is idempotent, leaves legacy rows unkeyed and accepts existing aliases") {
                // A scratch copy of created_file in its pre-076 shape, so that the shared tables are never altered.
                val table = "created_file_076_check"
                val statements =
                    checkNotNull(CreatedFileCatalogTest::class.java.classLoader.getResource("db/migrations/076_created_file_path_key.sql"))
                        .readText()
                        .lines()
                        .filterNot { it.trimStart().startsWith("--") }
                        .joinToString("\n")
                        .replace("created_file", table)
                        .split(';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                fun count(query: String): Int =
                    dataSource.connection.use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery(query).use { result ->
                                result.next()
                                result.getInt(1)
                            }
                        }
                    }

                fun drop() = execute("DROP TABLE IF EXISTS $table, ${table}_path_guard")
                drop()
                try {
                    execute("CREATE TABLE $table LIKE created_file")
                    execute("ALTER TABLE $table DROP INDEX path_key, DROP COLUMN path_key")
                    execute(
                        "INSERT INTO $table(id,splitted_file_id,file,size,status) " +
                            "VALUES(1,1,'base/a/alpha/file.mp4',1,'FILE_MOVED'),(2,1,'BASE\\\\A\\\\ALPHA\\\\FILE.MP4',1,'FILE_MOVED')",
                    )
                    statements.size shouldBe 2
                    repeat(2) { statements.forEach { execute(it) } }

                    count(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='$table' " +
                            "AND column_name='path_key' AND column_type='binary(32)' AND is_nullable='YES'",
                    ) shouldBe 1
                    count(
                        "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='$table' " +
                            "AND index_name='path_key' AND non_unique=1",
                    ) shouldBe 1
                    count(
                        "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() " +
                            "AND table_name='${table}_path_guard' AND index_name='PRIMARY' AND column_name='path_key'",
                    ) shouldBe 1
                    // Rows written before the migration keep no key until a bulk preview/apply fills them in.
                    count("SELECT COUNT(*) FROM $table WHERE path_key IS NULL") shouldBe 2
                    // Not unique: an installation that already holds aliases still migrates, and both rows get the same key.
                    val key = HexFormat.of().formatHex(CreatedFilePathKey.hash("base/a/alpha/file.mp4"))
                    execute("UPDATE $table SET path_key = UNHEX('$key')")
                    count("SELECT COUNT(*) FROM $table WHERE path_key = UNHEX('$key')") shouldBe 2
                } finally {
                    drop()
                }
            }

            expect("migration 077 is idempotent and keeps file unique by a plain index instead of USING HASH") {
                // A scratch copy of created_file in its pre-077 shape, so that the shared table is never altered.
                val table = "created_file_077_check"
                val migration = "db/migrations/077_created_file_file_hash_unique.sql"
                val statements =
                    checkNotNull(CreatedFileCatalogTest::class.java.classLoader.getResource(migration))
                        .readText()
                        .lines()
                        .filterNot { it.trimStart().startsWith("--") }
                        .joinToString("\n")
                        .replace("created_file", table)
                        .split(';')
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                fun count(query: String): Int =
                    dataSource.connection.use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery(query).use { result ->
                                result.next()
                                result.getInt(1)
                            }
                        }
                    }

                fun index(name: String) =
                    "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='$table' " +
                        "AND index_name='$name'"

                fun insert(
                    id: Int,
                    file: String,
                ) = execute("INSERT INTO $table(id,splitted_file_id,file,size,status) VALUES($id,1,'$file',1,'FILE_MOVED')")

                execute("DROP TABLE IF EXISTS $table")
                try {
                    execute("CREATE TABLE $table LIKE created_file")
                    execute("ALTER TABLE $table DROP INDEX file_hash, DROP COLUMN file_hash, ADD UNIQUE KEY file (file) USING HASH")
                    insert(1, "base/a/alpha/file.mp4")
                    statements.size shouldBe 1
                    repeat(2) { statements.forEach { execute(it) } }

                    count(index("file")) shouldBe 0
                    count(index("file_hash") + " AND non_unique=0 AND column_name='file_hash'") shouldBe 1
                    count(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='$table' " +
                            "AND column_name='file_hash' AND column_type='binary(32)' AND extra LIKE '%GENERATED%'",
                    ) shouldBe 1
                    // Existing rows get their hash, and the same file (trailing spaces included, as under the old PAD SPACE
                    // key) is still refused while another file, even an alias, is accepted.
                    count("SELECT COUNT(*) FROM $table WHERE file_hash = UNHEX(SHA2('base/a/alpha/file.mp4', 256))") shouldBe 1
                    shouldThrow<java.sql.SQLIntegrityConstraintViolationException> { insert(2, "base/a/alpha/file.mp4") }
                    shouldThrow<java.sql.SQLIntegrityConstraintViolationException> { insert(3, "base/a/alpha/file.mp4  ") }
                    insert(4, "BASE/A/ALPHA/FILE.MP4")
                    count("SELECT COUNT(*) FROM $table") shouldBe 2
                } finally {
                    execute("DROP TABLE IF EXISTS $table")
                }
            }

            expect("a row another transaction holds locked is skipped without waiting and filled in by a later call") {
                val path = uniquePath()
                rawInsert(990011, path)
                rawInsert(990012, "$path.second")
                try {
                    dataSource.connection.use { holder ->
                        holder.autoCommit = false
                        holder.createStatement().use { it.executeQuery("SELECT id FROM created_file WHERE id = 990011 FOR UPDATE").close() }
                        val started = System.nanoTime()
                        required.execute { catalog.fillMissingPathKeys(990010, 10) } shouldBe null
                        // Far below the default lock wait (50 s) it would otherwise sit out.
                        (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000) shouldBe true
                        holder.rollback()
                    }
                    pathKeyOf(990011) shouldBe null
                    pathKeyOf(990012).shouldNotBeNull().toList() shouldBe CreatedFilePathKey.hash("$path.second").toList()

                    required.execute { catalog.fillMissingPathKeys(990010, 10) } shouldBe null
                    pathKeyOf(990011).shouldNotBeNull().toList() shouldBe CreatedFilePathKey.hash(path).toList()
                } finally {
                    files.delete(990011)
                    files.delete(990012)
                }
            }
        }

        context("chapters") {
            val chapters =
                listOf(
                    VideoChapter(0, 0.0, 5.739, "A"),
                    VideoChapter(1, 5.739, 65.0, "CM"),
                    VideoChapter(2, 65.0, 65.0, null),
                    VideoChapter(3, 65.0, 120.5, "A"),
                )

            fun chapterRows(id: Long): Int =
                dataSource.connection.use { connection ->
                    connection.prepareStatement("SELECT COUNT(*) FROM created_file_chapter WHERE created_file_id = ?").use { statement ->
                        statement.setLong(1, id)
                        statement.executeQuery().use { result ->
                            result.next()
                            result.getInt(1)
                        }
                    }
                }

            expect("a new row is not probed and a missing row has no chapters") {
                val id = insertRow(uniquePath(), "video/mp4")
                try {
                    catalog.chaptersOf(id) shouldBe VideoChapters.NOT_PROBED
                    catalog.chaptersOf(Long.MAX_VALUE) shouldBe null
                } finally {
                    files.delete(id)
                }
            }

            expect("recording fails outside a transaction and changes nothing") {
                val id = insertRow(uniquePath(), "video/mp4")
                try {
                    shouldThrow<IllegalTransactionStateException> { catalog.recordChaptersIfMissing(id, chapters) }
                    catalog.chaptersOf(id) shouldBe VideoChapters.NOT_PROBED
                } finally {
                    files.delete(id)
                }
            }

            expect("records the chapters once, titles unchanged and in index order; a second record changes nothing") {
                val id = insertRow(uniquePath(), "video/mp4")
                try {
                    required.execute { catalog.recordChaptersIfMissing(id, chapters) } shouldBe true
                    required.execute { catalog.recordChaptersIfMissing(id, listOf(VideoChapter(0, 1.0, 2.0, "B"))) } shouldBe false
                    catalog.chaptersOf(id) shouldBe VideoChapters(true, chapters)
                } finally {
                    files.delete(id)
                }
            }

            expect("a probe that found no chapters is recorded as probed with none") {
                val id = insertRow(uniquePath(), "video/mp4")
                try {
                    required.execute { catalog.recordChaptersIfMissing(id, emptyList()) } shouldBe true
                    catalog.chaptersOf(id) shouldBe VideoChapters(true, emptyList())
                    required.execute { catalog.recordChaptersIfMissing(id, chapters) } shouldBe false
                    catalog.chaptersOf(id) shouldBe VideoChapters(true, emptyList())
                } finally {
                    files.delete(id)
                }
            }

            expect("the flag and the chapters roll back together with the caller") {
                val id = insertRow(uniquePath(), "video/mp4")
                try {
                    shouldThrow<IllegalStateException> {
                        required.execute {
                            catalog.recordChaptersIfMissing(id, chapters) shouldBe true
                            error("caller failed after recording")
                        }
                    }
                    catalog.chaptersOf(id) shouldBe VideoChapters.NOT_PROBED
                    chapterRows(id) shouldBe 0
                } finally {
                    files.delete(id)
                }
            }

            expect("concurrent recorders store one set: the second waits for the first to commit and records nothing") {
                val id = insertRow(uniquePath(), "video/mp4")
                val pool = Executors.newFixedThreadPool(2)
                try {
                    val firstRecorded = CountDownLatch(1)
                    val releaseFirst = CountDownLatch(1)
                    val first =
                        pool.submit<Boolean?> {
                            requiresNew.execute {
                                catalog.recordChaptersIfMissing(id, chapters).also {
                                    firstRecorded.countDown()
                                    releaseFirst.await(30, TimeUnit.SECONDS)
                                }
                            }
                        }
                    firstRecorded.await(30, TimeUnit.SECONDS) shouldBe true
                    val second =
                        pool.submit<Boolean?> {
                            requiresNew.execute { catalog.recordChaptersIfMissing(id, listOf(VideoChapter(0, 0.0, 1.0, "X"))) }
                        }
                    // The second recorder is blocked on the row lock held by the uncommitted first one.
                    Thread.sleep(500)
                    second.isDone shouldBe false
                    releaseFirst.countDown()

                    first.get(30, TimeUnit.SECONDS) shouldBe true
                    second.get(30, TimeUnit.SECONDS) shouldBe false
                    catalog.chaptersOf(id) shouldBe VideoChapters(true, chapters)
                } finally {
                    pool.shutdownNow()
                    files.delete(id)
                }
            }

            expect("rejects chapters that are not normalized, without marking the row probed") {
                val id = insertRow(uniquePath(), "video/mp4")
                try {
                    listOf(
                        listOf(VideoChapter(1, 0.0, 1.0, null)),
                        listOf(VideoChapter(0, -1.0, 1.0, null)),
                        listOf(VideoChapter(0, 2.0, 1.0, null)),
                        listOf(VideoChapter(0, 0.0, Double.POSITIVE_INFINITY, null)),
                        listOf(VideoChapter(0, Double.NaN, 1.0, null)),
                        listOf(VideoChapter(0, 5.0, 6.0, null), VideoChapter(1, 1.0, 2.0, null)),
                    ).forEach { invalid ->
                        shouldThrow<IllegalArgumentException> { required.execute { catalog.recordChaptersIfMissing(id, invalid) } }
                    }
                    catalog.chaptersOf(id) shouldBe VideoChapters.NOT_PROBED
                } finally {
                    files.delete(id)
                }
            }

            expect("deleting the created file deletes its chapters") {
                val id = insertRow(uniquePath(), "video/mp4")
                required.execute { catalog.recordChaptersIfMissing(id, chapters) }
                chapterRows(id) shouldBe chapters.size

                files.delete(id)

                chapterRows(id) shouldBe 0
            }

            expect("streamable video chapters exist only for streamable videos") {
                val mp4 = insertRow(uniquePath(), "video/mp4")
                val ts = insertRow(uniquePath(), CreatedFile.TS_MIME)
                try {
                    required.execute { catalog.recordChaptersIfMissing(mp4, chapters) }
                    required.execute { catalog.recordChaptersIfMissing(ts, chapters) }
                    catalog.streamableVideoChapters(mp4) shouldBe VideoChapters(true, chapters)
                    catalog.streamableVideoChapters(ts) shouldBe null
                    catalog.streamableVideoChapters(Long.MAX_VALUE) shouldBe null
                } finally {
                    files.delete(mp4)
                    files.delete(ts)
                }
            }

            expect("videos without chapters are unprobed mp4 rows in id order after the cursor") {
                val probed = insertRow(uniquePath(), "video/mp4")
                val firstMissing = insertRow(uniquePath(), "video/mp4")
                val subtitle = insertRow(uniquePath(), "text/x-ssa")
                val secondMissing = insertRow(uniquePath(), "video/mp4")
                val created = listOf(probed, firstMissing, subtitle, secondMissing)
                try {
                    required.execute { catalog.recordChaptersIfMissing(probed, emptyList()) }

                    catalog.videosWithoutChapters(probed - 1, 1000).map { it.id }.filter { it in created } shouldBe
                        listOf(firstMissing, secondMissing)
                    catalog.videosWithoutChapters(firstMissing, 1000).map { it.id }.filter { it in created } shouldBe
                        listOf(secondMissing)
                    catalog.videosWithoutChapters(probed - 1, 1).map { it.id } shouldBe listOf(firstMissing)
                    shouldThrow<IllegalArgumentException> { catalog.videosWithoutChapters(0, 0) }
                } finally {
                    created.forEach { files.delete(it) }
                }
            }
        }

        // library's verification relies on these orders (library's LibraryVerificationCommandTest mocks the catalog).
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
