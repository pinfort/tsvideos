plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.spring)
    alias(libs.plugins.spring)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
}

group = "me.pinfort"
version = "0.0.1-SNAPSHOT"

tasks.bootJar {
    enabled = false
}

// library が依存してよいプロジェクトは catalog と storage だけ (backend/docs/module-boundaries.md §2)。DB テストだけ database-schema のテストフィクスチャを使う。
dependencies {
    // catalog の公開型 (Program、ProgramSearchQuery など) は library の公開シグネチャにも出る。利用側も catalog へ明示的に依存する
    implementation(project(":catalog"))
    implementation(project(":storage"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(libs.mybatis.spring.boot)

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(module = "mockito-core") // Kotestを使う
    }
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.mockk)
    testImplementation(libs.mybatis.spring.test)
    // Testcontainers の初期化スクリプト ddl/01_create_database.sql とシードデータ (database-schema)
    testImplementation(testFixtures(project(":database-schema")))

    // testcontainers
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.mariadb)

    testImplementation(libs.testcontainers.spring.boot)
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client")
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    jvmToolchain(24)

    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

/**
 * library の境界ルール (module-boundaries.md §11.7)。checkLibraryBoundaries と、その自己検査 checkLibraryBoundaryRules が
 * 同じ判定を使う。
 * - library のプロジェクト依存は catalog と storage だけ。database-schema はテストの設定からだけ使ってよい
 *   (core・processor・manager などの旧モジュールや diagnostics への依存を拒否する)
 * - library のソース (テストを含む) は library・catalog・storage 以外の `me.pinfort.tsvideos` パッケージを参照しない
 * - library 以外のソースと資源 (テスト、MyBatis の XML、YAML、properties を含む) は library の公開パッケージ
 *   (`api`、`domain`) だけを参照し、`persistence` と今後の内部パッケージを参照しない。コメント行は数えない
 * - persistence のトップレベル宣言 (ktlint の整形で行頭から始まる宣言) はすべて internal (または private)。
 *   同じ行の注釈も外して判定する
 *
 * catalog の内部パッケージを参照しないことは catalog の checkCatalogBoundaries が検査する。パッケージ名そのもの
 * (`"me.pinfort.tsvideos.library"`) はコンポーネントスキャン対象として許可する。Mapper の登録先は
 * `LibraryPersistence.MAPPER_PACKAGE` で受け取るので、persistence のパッケージ名を library の外に書く必要はない。
 */
object LibraryBoundaryRules {
    // database-schema の検査は、テストフィクスチャとしての宣言以外にプロジェクトパスを引用符付きで書く行を拒否するので、":" を付けずに持つ
    private const val SCHEMA = "database-schema"
    private val allowedDependencies = setOf(":catalog", ":storage")
    private val foreignPackage = Regex("""\bme\.pinfort\.tsvideos\.(?!library\b|catalog\b|storage\b)\w+""")
    private val internals = Regex("""\bme\.pinfort\.tsvideos\.library\.(?!api\b|domain\b)\w+""")

    // 行頭の注釈 (同じ行の引数を含む) を外してから、修飾子 (グループ 1) と宣言のキーワードを当てる
    private val annotations = Regex("""^(?:@[\w.:]+(?:\([^()]*\))?\s*)*""")
    private val declaration =
        Regex(
            """^((?:(?:public|internal|private|data|enum|sealed|abstract|open|annotation|value|const|lateinit|inline|fun)\s+)*)""" +
                """(?:class|interface|object|fun|val|var|typealias)\s+[\w<`]""",
        )
    private val hidden = Regex("""\b(?:internal|private)\b""")

    // 種類ごとの行コメントの始まり (字下げを除いた行頭で判定する)
    private val sourceComments = listOf("//", "/*", "*")
    private val commentStarts =
        mapOf(
            "kt" to sourceComments,
            "java" to sourceComments,
            "xml" to listOf("<!--"),
            "yaml" to listOf("#"),
            "yml" to listOf("#"),
            "properties" to listOf("#", "!"),
        )

    fun violations(
        projectDependencies: List<String>,
        librarySources: Iterable<File>,
        persistenceSources: Iterable<File>,
        otherSources: Iterable<File>,
        root: File,
    ): List<String> {
        val violations = mutableListOf<String>()
        // "<configuration> -> <project>" の形
        projectDependencies.forEach { dependency ->
            val configuration = dependency.substringBefore(" -> ")
            val path = dependency.substringAfter(" -> ")
            val allowed = path in allowedDependencies || (path == ":$SCHEMA" && configuration.startsWith("test"))
            if (!allowed) violations += "library declares a forbidden project dependency: $dependency"
        }
        // コメントも含めて数える。library のソースが他モジュールのパッケージ名を書く必要はない
        librarySources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                foreignPackage.find(line)?.let {
                    violations += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} references ${it.value} from library"
                }
            }
        }
        persistenceSources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val match = declaration.find(line.replaceFirst(annotations, ""))
                if (match != null && !hidden.containsMatchIn(match.groupValues[1])) {
                    violations += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} must be internal: ${line.trim()}"
                }
            }
        }
        otherSources.forEach { file ->
            val comments = commentStarts[file.extension].orEmpty()
            file.readLines().forEachIndexed { index, line ->
                val text = line.trimStart()
                if (comments.none { text.startsWith(it) } && internals.containsMatchIn(line)) {
                    violations += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} reaches a library internal: ${line.trim()}"
                }
            }
        }
        return violations
    }
}

/**
 * 境界ルールが違反を見逃さないことを、違反を含むフィクスチャで確かめる (ルールの変更で検査が素通りになるのを防ぐ)。
 * 許可されるもの (catalog・storage への依存、公開パッケージの参照、スキャン用のパッケージ名、Mapper 登録先の定数、
 * コメント、internal の宣言) が違反にならないことも確かめる。
 */
val checkLibraryBoundaryRules by tasks.registering {
    group = "verification"
    description = "Fails when the library boundary rules stop rejecting known violations."
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

        val rejectedDependencies =
            listOf(
                "implementation -> :core",
                "implementation -> :diagnostics",
                "testImplementation -> :processor:infrastructure",
                "api -> :manager:infrastructure",
                "implementation -> :database-schema",
            )
        val allowedDependencies =
            listOf("implementation -> :catalog", "implementation -> :storage", "testImplementation -> :database-schema")
        val libraryRejected =
            listOf(
                fixture(
                    "library/src/main/kotlin/Reverse.kt",
                    "package me.pinfort.tsvideos.library.api",
                    "import me.pinfort.tsvideos.core.domain.BulkPlan",
                ),
                fixture("library/src/test/kotlin/Processing.kt", "// waits for me.pinfort.tsvideos.processor.infrastructure"),
                fixture(
                    "library/src/main/kotlin/Diagnostics.kt",
                    "import me.pinfort.tsvideos.diagnostics.api.EnvironmentDiagnosticCommand",
                ),
            )
        val libraryAllowed =
            fixture(
                "library/src/test/kotlin/Allowed.kt",
                "package me.pinfort.tsvideos.library",
                "import me.pinfort.tsvideos.catalog.api.ProgramCommand",
                "import me.pinfort.tsvideos.catalog.domain.Program",
                "import me.pinfort.tsvideos.storage.api.NasStore",
                "@SpringBootApplication(scanBasePackages = [\"me.pinfort.tsvideos.storage\", \"me.pinfort.tsvideos.catalog\", \"me.pinfort.tsvideos.library\"])",
            )
        val persistenceRejected =
            listOf(
                fixture("library/src/main/kotlin/persistence/LeakedMapper.kt", "@Mapper", "interface LeakedMapper {", "}"),
                fixture("library/src/main/kotlin/persistence/SameLineMapper.kt", "@Mapper interface SameLineMapper {", "}"),
                fixture("library/src/main/kotlin/persistence/LeakedRow.kt", "public data class LeakedRow(", "    val id: Long,", ")"),
                fixture("library/src/main/kotlin/persistence/LeakedSql.kt", "class LeakedSql {", "}"),
                fixture("library/src/main/kotlin/persistence/Annotated.kt", "@Suppress(\"unused\") object LeakedObject"),
                fixture("library/src/main/kotlin/persistence/LeakedFunction.kt", "fun leaked() = Unit"),
            )
        val persistenceAllowed =
            fixture(
                "library/src/main/kotlin/persistence/Hidden.kt",
                "package me.pinfort.tsvideos.library.persistence",
                "",
                "/**",
                " * class InComment is documentation, not a declaration",
                " */",
                "@Mapper",
                "internal interface HiddenMapper {",
                "    @SelectProvider(type = HiddenSql::class, method = \"list\")",
                "    fun list(): List<HiddenRow>",
                "}",
                "",
                "@Mapper internal interface SameLineMapper {",
                "    fun find(id: Long): HiddenRow?",
                "}",
                "",
                "internal data class HiddenRow(",
                "    val id: Long,",
                ") {",
                "    fun toDomain(): Long = id",
                "}",
                "",
                "internal class HiddenSql {",
                "    fun list(parameters: Map<String, Any>) =",
                "        \"\"\"",
                "        SELECT id FROM library_tag",
                "        \"\"\".trimIndent()",
                "}",
                "",
                "@Suppress(\"unused\") private fun helper() = Unit",
                "// class InLineComment",
                "private const val LIMIT = 1000",
            )
        val otherRejected =
            listOf(
                fixture(
                    "core/src/main/kotlin/ImportsMapper.kt",
                    "import me.pinfort.tsvideos.library.persistence.LibraryOrganizationMapper",
                ),
                fixture(
                    "core/src/test/kotlin/NamesRow.kt",
                    "    val type = me.pinfort.tsvideos.library.persistence.LibraryProgramRow::class",
                ),
                fixture("manager/console/src/test/kotlin/Internal.kt", "import me.pinfort.tsvideos.library.application.Anything"),
                fixture(
                    "manager/api/src/main/resources/mapper/LibraryMapper.xml",
                    "<mapper namespace=\"me.pinfort.tsvideos.library.persistence.LibraryOrganizationMapper\">",
                ),
                fixture(
                    "manager/console/src/main/resources/application.yaml",
                    "  type-aliases-package: me.pinfort.tsvideos.library.persistence",
                ),
                fixture("manager/api/src/main/kotlin/Scan.kt", "@MapperScan(\"me.pinfort.tsvideos.library.persistence\")"),
            )
        val otherAllowed =
            listOf(
                fixture(
                    "manager/api/src/main/kotlin/Allowed.kt",
                    "import me.pinfort.tsvideos.library.api.LibraryPersistence",
                    "import me.pinfort.tsvideos.library.api.LibraryOrganizationCommand",
                    "import me.pinfort.tsvideos.library.domain.LibraryKind",
                    "@MapperScan(basePackages = [CatalogPersistence.MAPPER_PACKAGE, LibraryPersistence.MAPPER_PACKAGE])",
                    "@SpringBootApplication(scanBasePackages = [\"me.pinfort.tsvideos.catalog\", \"me.pinfort.tsvideos.library\"])",
                    "// library's mappers (me.pinfort.tsvideos.library.persistence) stay internal",
                    "/**",
                    " * [me.pinfort.tsvideos.library.persistence.LibraryOrganizationMapper] is never named outside library",
                    " */",
                ),
                fixture(
                    "manager/api/src/main/resources/application.yaml",
                    "# me.pinfort.tsvideos.library.persistence is registered through LibraryPersistence.MAPPER_PACKAGE",
                ),
                fixture("manager/api/src/main/resources/mapper/Comment.xml", "<!-- me.pinfort.tsvideos.library.persistence -->"),
            )

        val violations =
            LibraryBoundaryRules.violations(
                projectDependencies = rejectedDependencies + allowedDependencies,
                librarySources = libraryRejected + libraryAllowed,
                persistenceSources = persistenceRejected + persistenceAllowed,
                otherSources = otherRejected + otherAllowed,
                root = fixtureRoot,
            )

        fun reported(file: File) = violations.any { it.startsWith(file.relativeTo(fixtureRoot).invariantSeparatorsPath) }
        val problems = mutableListOf<String>()
        rejectedDependencies.filterNot { dependency -> violations.any { it.endsWith(": $dependency") } }.forEach {
            problems += "the project dependency $it was not reported"
        }
        allowedDependencies.filter { dependency -> violations.any { it.endsWith(": $dependency") } }.forEach {
            problems += "the project dependency $it was reported but is allowed"
        }
        (libraryRejected + persistenceRejected + otherRejected).filterNot(::reported).forEach {
            problems += "${it.relativeTo(fixtureRoot).invariantSeparatorsPath} was not reported"
        }
        (listOf(libraryAllowed, persistenceAllowed) + otherAllowed).filter(::reported).forEach {
            problems += "${it.relativeTo(fixtureRoot).invariantSeparatorsPath} was reported but is allowed"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Library boundary rules are broken:\n" + (problems + "reported: $violations").joinToString("\n") { "  $it" },
            )
        }
    }
}

val checkLibraryBoundaries by tasks.registering {
    group = "verification"
    description = "Fails when library depends on a forbidden project or other modules reach library internals."
    dependsOn(checkLibraryBoundaryRules)
    val projectDependencies =
        provider {
            configurations.flatMap { configuration ->
                configuration.dependencies.withType<ProjectDependency>().map { "${configuration.name} -> ${it.path}" }
            }
        }
    val ownSources = fileTree("src") { include("**/*.kt") }
    val persistenceSources = fileTree("src/main/kotlin/me/pinfort/tsvideos/library/persistence") { include("**/*.kt") }
    val otherSources =
        fileTree(rootDir) {
            include("**/src/**/*.kt", "**/src/**/*.java", "**/src/**/*.xml")
            include("**/src/**/*.yaml", "**/src/**/*.yml", "**/src/**/*.properties")
            exclude("library/**", "**/build/**", ".gradle/**")
        }
    val rootPath = rootDir
    inputs.property("projectDependencies", projectDependencies)
    inputs.files(ownSources, otherSources)
    doLast {
        if (persistenceSources.isEmpty) throw GradleException("library persistence sources not found; update checkLibraryBoundaries")
        val violations =
            LibraryBoundaryRules.violations(projectDependencies.get(), ownSources, persistenceSources, otherSources, rootPath)
        if (violations.isNotEmpty()) {
            throw GradleException("Library boundary violations:\n" + violations.joinToString("\n") { "  $it" })
        }
    }
}

tasks.check {
    dependsOn(checkLibraryBoundaries)
}
