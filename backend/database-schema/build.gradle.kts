plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
    `java-test-fixtures`
}

group = "me.pinfort"
version = "0.0.1-SNAPSHOT"

tasks.bootJar {
    enabled = false
}

// database-schema はスキーマの単一の情報源で、データ操作 API を持たない (backend/docs/module-boundaries.md §11.6)。
// プロジェクト依存は追加しない。利用側は DB テストの testImplementation(testFixtures(project(":database-schema"))) だけで参照する。
dependencies {
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(24)

    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

val migrationsDir = layout.projectDirectory.dir("src/main/resources/db/migrations")

// DDLの単一の情報源は db/migrations。Testcontainers(TC_INITSCRIPT=ddl/01_create_database.sql)用に連結して生成する
val generateTestDdl by tasks.registering {
    val migrations = fileTree(migrationsDir) { include("*.sql") }
    val output = layout.buildDirectory.file("generated/test-ddl/ddl/01_create_database.sql")
    inputs.files(migrations)
    outputs.file(output)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(migrations.sortedBy { it.name }.joinToString("\n") { it.readText() })
    }
}

// 生成 DDL とシードデータ (ddl/02_insert_data.sql) はテストフィクスチャとしてだけ配布する
sourceSets.named("testFixtures") {
    resources.srcDir(generateTestDdl.map { layout.buildDirectory.dir("generated/test-ddl") })
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("databaseSchema.migrationsDir", migrationsDir.asFile.absolutePath)
}

/**
 * database-schema の境界ルール (module-boundaries.md §11.6、§11.7)。checkDatabaseSchemaBoundaries と、その自己検査
 * checkDatabaseSchemaBoundaryRules が同じ判定を使う。
 * - database-schema はどのプロジェクトにも依存せず、Kotlin/Java のソース (データ操作 API) を main とテストフィクスチャに持たない
 * - 他のプロジェクトは database-schema を test で始まる設定からだけ、テストフィクスチャとして参照する
 *   (本番の実行時クラスパスに migration とテスト資源を入れない)
 * - 実行アプリ (bootJar を作るプロジェクト) はすべて runtime-isolation.gradle.kts を適用し、解決後の実行時クラスパスを検査する
 * - migration は `NNN_name.sql` の形で番号が重複せず、他のモジュールに migration ディレクトリと DB テスト用 DDL を置かない
 * - docker-compose.test.yml は migration をファイル名順に同名でマウントし、最後にシードデータをマウントする
 */
object DatabaseSchemaRules {
    const val PATH = ":database-schema"
    const val COMPOSE_SOURCE = "./backend/database-schema/src/main/resources/db/migrations/"
    const val COMPOSE_SEED =
        "./backend/database-schema/src/testFixtures/resources/ddl/02_insert_data.sql:/docker-entrypoint-initdb.d/999_insert_data.sql:ro"
    private val migrationName = Regex("""^(\d{3})_[a-z0-9_]+\.sql$""")
    private val fixtureReference = Regex("""^\s*test\w*\(testFixtures\(project\("${Regex.escape(PATH)}"\)\)\)\s*$""")
    private val composeMount = Regex("""^\s*-\s*(\S+:/docker-entrypoint-initdb\.d/\S+)\s*$""")

    fun violations(
        ownProjectDependencies: List<String>,
        ownCodeSources: Iterable<File>,
        consumerDependencies: List<String>,
        unguardedApps: List<String>,
        otherBuildScripts: Iterable<File>,
        otherSchemaFiles: Iterable<File>,
        migrationNames: List<String>,
        composeFile: File,
        root: File,
    ): List<String> {
        val violations = mutableListOf<String>()
        ownProjectDependencies.forEach { violations += "database-schema declares a project dependency: $it" }
        ownCodeSources.forEach {
            violations += "${it.relativeTo(root).invariantSeparatorsPath} is code; database-schema provides no data access API"
        }
        // "<project> <configuration>" の形。本番の設定 (implementation, runtimeOnly, api など) からの依存を拒否する
        consumerDependencies.filterNot { it.substringAfterLast(' ').startsWith("test") }.forEach {
            violations += "$it depends on $PATH outside of tests"
        }
        unguardedApps.forEach {
            violations += "$it builds an executable jar without applying database-schema/runtime-isolation.gradle.kts"
        }
        otherBuildScripts.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (line.contains("\"$PATH\"") && !fixtureReference.matches(line)) {
                    violations +=
                        "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} must use " +
                        "testImplementation(testFixtures(project(\"$PATH\"))): ${line.trim()}"
                }
            }
        }
        otherSchemaFiles.forEach {
            violations += "${it.relativeTo(root).invariantSeparatorsPath} duplicates database-schema; keep migrations and test DDL there"
        }
        val sorted = migrationNames.sorted()
        if (sorted.isEmpty()) violations += "no migrations found"
        sorted.filterNot { migrationName.matches(it) }.forEach { violations += "migration $it is not named NNN_name.sql" }
        sorted
            .mapNotNull { migrationName.find(it)?.groupValues?.get(1) }
            .groupBy { it }
            .filterValues { it.size > 1 }
            .keys
            .forEach { violations += "migration number $it is used more than once" }
        val expected = sorted.map { "$COMPOSE_SOURCE$it:/docker-entrypoint-initdb.d/$it:ro" } + COMPOSE_SEED
        val mounted = composeFile.readLines().mapNotNull { composeMount.find(it)?.groupValues?.get(1) }
        if (mounted != expected) {
            violations +=
                "${composeFile.relativeTo(root).invariantSeparatorsPath} must mount every migration in filename order, " +
                "then the seed data:\n    expected: $expected\n    actual:   $mounted"
        }
        return violations
    }
}

/**
 * 境界ルールが違反を見逃さないことを、違反を含むフィクスチャで確かめる (ルールの変更で検査が素通りになるのを防ぐ)。
 * 許可されるもの (テストフィクスチャとしての参照、正しい compose のマウント) が違反にならないことも確かめる。
 */
val checkDatabaseSchemaBoundaryRules by tasks.registering {
    group = "verification"
    description = "Fails when the database-schema boundary rules stop rejecting known violations."
    val fixtureRoot = temporaryDir
    doLast {
        fixtureRoot.deleteRecursively()

        fun fixture(
            path: String,
            vararg lines: String,
        ): File {
            val file = File(fixtureRoot, path)
            file.parentFile.mkdirs()
            file.writeText(lines.joinToString("\n"))
            return file
        }

        val migrations = listOf("001_base.sql", "002_next.sql")

        fun compose(
            name: String,
            vararg mounts: String,
        ) = fixture("compose/$name.yml", "    volumes:", *mounts.map { "      - $it" }.toTypedArray())

        fun mount(name: String) = "${DatabaseSchemaRules.COMPOSE_SOURCE}$name:/docker-entrypoint-initdb.d/$name:ro"
        val goodCompose = compose("good", mount("001_base.sql"), mount("002_next.sql"), DatabaseSchemaRules.COMPOSE_SEED)
        val allowedScript = fixture("core/build.gradle.kts", "    testImplementation(testFixtures(project(\":database-schema\")))")
        val code = fixture("database-schema/src/main/kotlin/SchemaApi.kt", "package me.pinfort.tsvideos.schema")
        val rejectedScripts =
            listOf(
                fixture("manager/api/build.gradle.kts", "    implementation(project(\":database-schema\"))"),
                fixture("processor/console/build.gradle.kts", "    testImplementation(project(\":database-schema\"))"),
                fixture("manager/console/build.gradle.kts", "    runtimeOnly(testFixtures(project(\":database-schema\")))"),
            )
        val duplicate = fixture("core/src/main/resources/db/migrations/003_copy.sql", "CREATE TABLE copy (id INT);")

        val clean =
            DatabaseSchemaRules.violations(
                ownProjectDependencies = emptyList(),
                ownCodeSources = emptyList(),
                consumerDependencies = listOf(":core testImplementation", ":manager:api testRuntimeOnly"),
                unguardedApps = emptyList(),
                otherBuildScripts = listOf(allowedScript),
                otherSchemaFiles = emptyList(),
                migrationNames = migrations.reversed(),
                composeFile = goodCompose,
                root = fixtureRoot,
            )
        val broken =
            DatabaseSchemaRules.violations(
                ownProjectDependencies = listOf("implementation -> :core"),
                ownCodeSources = listOf(code),
                consumerDependencies = listOf(":manager:api implementation", ":core testImplementation"),
                unguardedApps = listOf(":processor:console"),
                otherBuildScripts = rejectedScripts + allowedScript,
                otherSchemaFiles = listOf(duplicate),
                migrationNames = listOf("001_base.sql", "001_again.sql", "3_short.sql"),
                composeFile = goodCompose,
                root = fixtureRoot,
            )
        val wrongOrder =
            DatabaseSchemaRules.violations(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                migrations,
                compose("wrong-order", mount("002_next.sql"), mount("001_base.sql"), DatabaseSchemaRules.COMPOSE_SEED),
                fixtureRoot,
            )
        val missingSeed =
            DatabaseSchemaRules.violations(
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                emptyList(),
                migrations,
                compose("missing-seed", mount("001_base.sql"), mount("002_next.sql")),
                fixtureRoot,
            )

        fun reported(file: File) = broken.any { it.startsWith(file.relativeTo(fixtureRoot).invariantSeparatorsPath) }
        val problems = mutableListOf<String>()
        if (clean.isNotEmpty()) problems += "allowed fixtures were reported: $clean"
        mapOf(
            "a project dependency of database-schema" to "project dependency: implementation -> :core",
            "a production dependency on database-schema" to ":manager:api implementation depends on",
            "an executable app without the runtime classpath check" to ":processor:console builds an executable jar",
            "a duplicate migration number" to "migration number 001",
            "a malformed migration name" to "migration 3_short.sql",
        ).forEach { (what, message) -> if (broken.none { it.contains(message) }) problems += "$what was not reported" }
        if (broken.any { it.contains(":core testImplementation") }) problems += "a test fixture dependency was reported"
        (rejectedScripts + code + duplicate).filterNot(::reported).forEach {
            problems += "${it.relativeTo(fixtureRoot).invariantSeparatorsPath} was not reported"
        }
        if (reported(allowedScript)) problems += "the test fixture reference was reported"
        if (wrongOrder.isEmpty()) problems += "compose mounts out of filename order were not reported"
        if (missingSeed.isEmpty()) problems += "a compose file without the seed mount was not reported"
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Database schema boundary rules are broken:\n" + (problems + "reported: $broken").joinToString("\n") { "  $it" },
            )
        }
    }
}

val checkDatabaseSchemaBoundaries by tasks.registering {
    group = "verification"
    description = "Fails when database-schema gains dependencies or code, or migrations, consumers and compose mounts drift apart."
    dependsOn(checkDatabaseSchemaBoundaryRules)
    val ownPath = project.path
    val ownProjectDependencies =
        provider {
            configurations.flatMap { configuration ->
                configuration.dependencies
                    .withType<ProjectDependency>()
                    // java-test-fixtures は自分自身への依存 (testFixturesApi / testImplementation) を追加する
                    .filterNot { it.path == ownPath }
                    .map { "${configuration.name} -> ${it.path}" }
            }
        }
    val consumerDependencies =
        provider {
            rootProject.allprojects.filter { it.path != ownPath }.flatMap { other ->
                other.configurations.flatMap { configuration ->
                    configuration.dependencies
                        .withType<ProjectDependency>()
                        .filter { it.path == ownPath }
                        .map { "${other.path} ${configuration.name}" }
                }
            }
        }
    // 実行アプリ (bootJar が有効) のうち、解決後の実行時クラスパスの検査 (runtime-isolation.gradle.kts) を適用していないもの
    val unguardedApps =
        provider {
            rootProject.allprojects
                .filter { other -> other.tasks.findByName("bootJar")?.enabled == true }
                .filter { other -> other.tasks.findByName("checkDatabaseSchemaRuntimeIsolation") == null }
                .map { it.path }
        }
    val ownCodeSources = fileTree("src") { include("main/**/*.kt", "main/**/*.java", "testFixtures/**/*.kt", "testFixtures/**/*.java") }
    val otherBuildScripts =
        fileTree(rootDir) {
            include("**/build.gradle.kts")
            exclude("database-schema/**", "**/build/**", ".gradle/**")
        }
    val otherSchemaFiles =
        fileTree(rootDir) {
            include("**/src/**/db/migrations/**", "**/src/**/ddl/*.sql")
            exclude("database-schema/**", "**/build/**", ".gradle/**")
        }
    val migrations = fileTree(migrationsDir)
    val composeFile = rootDir.resolve("../docker-compose.test.yml").normalize()
    val rootPath = rootDir.parentFile
    inputs.property("ownProjectDependencies", ownProjectDependencies)
    inputs.property("consumerDependencies", consumerDependencies)
    inputs.property("unguardedApps", unguardedApps)
    inputs.files(ownCodeSources, otherBuildScripts, otherSchemaFiles, migrations, composeFile)
    doLast {
        val violations =
            DatabaseSchemaRules.violations(
                ownProjectDependencies.get(),
                ownCodeSources,
                consumerDependencies.get(),
                unguardedApps.get(),
                otherBuildScripts,
                otherSchemaFiles,
                migrations.map { it.name },
                composeFile,
                rootPath,
            )
        if (violations.isNotEmpty()) {
            throw GradleException("Database schema boundary violations:\n" + violations.joinToString("\n") { "  $it" })
        }
    }
}

tasks.check {
    dependsOn(checkDatabaseSchemaBoundaries)
}
