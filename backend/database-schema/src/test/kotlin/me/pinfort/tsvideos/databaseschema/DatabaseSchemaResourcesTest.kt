package me.pinfort.tsvideos.databaseschema

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeSorted
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File

// The test fixtures that DB tests consume (generated DDL and seed data) must come from the migrations, in filename order.
class DatabaseSchemaResourcesTest : FunSpec() {
    private val migrations =
        File(checkNotNull(System.getProperty("databaseSchema.migrationsDir")))
            .listFiles { file -> file.extension == "sql" }
            .orEmpty()
            .sortedBy { it.name }

    private fun resource(path: String) =
        javaClass.classLoader
            .getResource(path)
            .shouldNotBeNull()
            .readText()

    init {
        test("every migration is on the classpath with its original content") {
            migrations.shouldNotBeEmpty()
            migrations.forEach { resource("db/migrations/${it.name}") shouldBe it.readText() }
        }

        test("the Testcontainers init script is the migrations concatenated in filename order") {
            resource("ddl/01_create_database.sql") shouldBe migrations.joinToString("\n") { it.readText() }
        }

        test("migration file names sort in numeric order") {
            migrations.map { it.name.substringBefore('_').toInt() }.shouldBeSorted()
        }

        test("the seed data and the maintenance script are shared with DB tests") {
            resource("ddl/02_insert_data.sql") shouldContain "INSERT INTO `test`.`program`"
            resource("db/maintenance/processing_history_cleanup.sql").shouldNotBeNull()
        }
    }
}
