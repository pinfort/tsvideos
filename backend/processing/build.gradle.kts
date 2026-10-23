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

// processing が依存してよいプロジェクトは catalog・storage・diagnostics だけ (backend/docs/module-boundaries.md §2)。
// DB テストだけ database-schema のテストフィクスチャを使う。core・library・manager・processor には依存しない
// (#165 で逆向きの core → processing もなくなった。manager の実行アプリは processing を実行時クラスパスに含めない)。
// #166 で旧 processor:infrastructure の pipeline・reset・after-encode・TS 検出・Slack 通知をここへ統合した。
dependencies {
    // catalog の公開型 (ExecutedFile、VideoResolution など) は processing の公開シグネチャにも出る。利用側も catalog へ明示的に依存する
    implementation(project(":catalog"))
    implementation(project(":storage"))
    // ProcessingPreflightCommand が diagnostics の EnvironmentDiagnosticCommand と診断結果の型を使う
    implementation(project(":diagnostics"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.core:jackson-databind")
    implementation(libs.mybatis.spring.boot)
    // ドロップ検査 (TsSelectClient) が JVM 内で TS を解析する
    implementation("me.pinfort:tsselect:1.0.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(module = "mockito-core") // Kotestを使う
    }
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.mockk)
    testImplementation(libs.mybatis.spring.test)
    // Testcontainers の初期化スクリプト ddl/01_create_database.sql、シードデータ、保守用 SQL (database-schema)
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
 * processing の境界ルール (module-boundaries.md §11.7)。checkProcessingBoundaries と、その自己検査
 * checkProcessingBoundaryRules が同じ判定を使う。
 * - processing のプロジェクト依存は catalog・storage・diagnostics だけ。database-schema はテストの設定からだけ使ってよい
 *   (core・library・console-common・processor・manager への依存を拒否する)
 * - processing のソース (テストを含む) は processing・catalog・storage・diagnostics 以外の `me.pinfort.tsvideos`
 *   パッケージを参照しない
 * - processing 以外のソースと資源 (テスト、MyBatis の XML、YAML、properties を含む) は processing の公開パッケージ
 *   (`api` とその下の `api.config` / `api.shell` / `api.tool`、`domain`) だけを参照し、`persistence` と今後の内部パッケージを
 *   参照しない。コメント行は数えない
 * - persistence のトップレベル宣言 (ktlint の整形で行頭から始まる宣言) はすべて internal (または private)。
 *   同じ行の注釈も外して判定する
 *
 * catalog・storage・diagnostics の内部パッケージを参照しないことは、それぞれの境界チェックが検査する。パッケージ名そのもの
 * (`"me.pinfort.tsvideos.processing"`) はコンポーネントスキャン対象として許可する。Mapper の登録先は
 * `ProcessingPersistence.MAPPER_PACKAGE` で受け取るので、persistence のパッケージ名を processing の外に書く必要はない。
 * #165 で core と manager の実行アプリは processing を使わなくなった。manager の実行アプリの実行時クラスパスに processing が
 * 入らないことは、各アプリが適用する checkProcessingRuntimeIsolation (processing/runtime-isolation.gradle.kts) が検査し、
 * checkProcessingBoundaries はその適用漏れを拒否する。
 */
object ProcessingBoundaryRules {
    // database-schema の検査は、テストフィクスチャとしての宣言以外にプロジェクトパスを引用符付きで書く行を拒否するので、":" を付けずに持つ
    private const val SCHEMA = "database-schema"
    private val allowedDependencies = setOf(":catalog", ":storage", ":diagnostics")
    private val foreignPackage = Regex("""\bme\.pinfort\.tsvideos\.(?!processing\b|catalog\b|storage\b|diagnostics\b)\w+""")
    private val internals = Regex("""\bme\.pinfort\.tsvideos\.processing\.(?!api\b|domain\b)\w+""")

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
        processingSources: Iterable<File>,
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
            if (!allowed) violations += "processing declares a forbidden project dependency: $dependency"
        }
        // コメントも含めて数える。processing のソースが旧モジュールや library のパッケージ名を書く必要はない
        processingSources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                foreignPackage.find(line)?.let {
                    violations += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} references ${it.value} from processing"
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
                    violations +=
                        "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} reaches a processing internal: ${line.trim()}"
                }
            }
        }
        return violations
    }
}

/**
 * 境界ルールが違反を見逃さないことを、違反を含むフィクスチャで確かめる (ルールの変更で検査が素通りになるのを防ぐ)。
 * 許可されるもの (catalog・storage・diagnostics への依存、公開パッケージとその下位パッケージの参照、スキャン用の
 * パッケージ名、Mapper 登録先の定数、コメント、internal の宣言) が違反にならないことも確かめる。
 */
val checkProcessingBoundaryRules by tasks.registering {
    group = "verification"
    description = "Fails when the processing boundary rules stop rejecting known violations."
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
                "implementation -> :library",
                "implementation -> :console-common",
                "testImplementation -> :processor:infrastructure",
                "implementation -> :processor:console",
                "api -> :manager:infrastructure",
                "implementation -> :manager:api",
                "implementation -> :database-schema",
            )
        val allowedDependencies =
            listOf(
                "implementation -> :catalog",
                "implementation -> :storage",
                "implementation -> :diagnostics",
                "testImplementation -> :database-schema",
            )
        val processingRejected =
            listOf(
                fixture(
                    "processing/src/main/kotlin/Reverse.kt",
                    "package me.pinfort.tsvideos.processing.api",
                    "import me.pinfort.tsvideos.core.config.LoggerConfig",
                ),
                fixture("processing/src/test/kotlin/Pipeline.kt", "// waits for me.pinfort.tsvideos.processor.infrastructure"),
                fixture(
                    "processing/src/main/kotlin/Library.kt",
                    "import me.pinfort.tsvideos.library.api.BulkLibraryCommand",
                ),
                fixture(
                    "processing/src/main/kotlin/Manager.kt",
                    "@SpringBootApplication(scanBasePackages = [\"me.pinfort.tsvideos.manager\"])",
                ),
                fixture(
                    "processing/src/test/kotlin/Console.kt",
                    "import me.pinfort.tsvideos.console.common.component.UserQuestionComponent",
                ),
            )
        val processingAllowed =
            fixture(
                "processing/src/test/kotlin/Allowed.kt",
                "package me.pinfort.tsvideos.processing",
                "import me.pinfort.tsvideos.catalog.api.ProgramCommand",
                "import me.pinfort.tsvideos.catalog.domain.Program",
                "import me.pinfort.tsvideos.diagnostics.api.EnvironmentDiagnosticCommand",
                "import me.pinfort.tsvideos.storage.api.NasStore",
                "import me.pinfort.tsvideos.processing.persistence.ProcessingJobMapper",
                "@SpringBootApplication(scanBasePackages = [\"me.pinfort.tsvideos.storage\", \"me.pinfort.tsvideos.processing\"])",
            )
        val persistenceRejected =
            listOf(
                fixture("processing/src/main/kotlin/persistence/LeakedMapper.kt", "@Mapper", "interface LeakedMapper {", "}"),
                fixture("processing/src/main/kotlin/persistence/SameLineMapper.kt", "@Mapper interface SameLineMapper {", "}"),
                fixture("processing/src/main/kotlin/persistence/LeakedHolder.kt", "class GeneratedKeyHolder {", "}"),
                fixture("processing/src/main/kotlin/persistence/LeakedRow.kt", "public data class LeakedRow(", "    val id: Long,", ")"),
                fixture("processing/src/main/kotlin/persistence/Annotated.kt", "@Suppress(\"unused\") object LeakedObject"),
                fixture("processing/src/main/kotlin/persistence/LeakedFunction.kt", "fun leaked() = Unit"),
            )
        val persistenceAllowed =
            fixture(
                "processing/src/main/kotlin/persistence/Hidden.kt",
                "package me.pinfort.tsvideos.processing.persistence",
                "",
                "private const val COLUMNS =",
                "    \"id,job_id\"",
                "",
                "/**",
                " * class InComment is documentation, not a declaration",
                " */",
                "@Mapper",
                "internal interface HiddenMapper {",
                "    @Select(\"SELECT \$COLUMNS FROM processing_attempt WHERE id=#{id}\")",
                "    fun find(id: Long): HiddenRow?",
                "}",
                "",
                "@Mapper internal interface SameLineMapper {",
                "    fun find(id: Long): HiddenRow?",
                "}",
                "",
                "internal class GeneratedKeyHolder {",
                "    var id: Long = 0",
                "}",
                "",
                "internal data class HiddenRow(",
                "    val id: Long,",
                ")",
                "// class InLineComment",
            )
        val otherRejected =
            listOf(
                fixture(
                    "core/src/main/kotlin/ImportsMapper.kt",
                    "import me.pinfort.tsvideos.processing.persistence.ProcessingJobMapper",
                ),
                fixture(
                    "processor/infrastructure/src/test/kotlin/NamesMapper.kt",
                    "    val type = me.pinfort.tsvideos.processing.persistence.ProcessingHistoryMapper::class",
                ),
                fixture("processor/console/src/main/kotlin/Internal.kt", "import me.pinfort.tsvideos.processing.application.Anything"),
                fixture(
                    "processor/console/src/main/resources/mapper/ProcessingMapper.xml",
                    "<mapper namespace=\"me.pinfort.tsvideos.processing.persistence.ProcessingJobMapper\">",
                ),
                fixture(
                    "manager/console/src/main/resources/application.yaml",
                    "  type-aliases-package: me.pinfort.tsvideos.processing.persistence",
                ),
                fixture("manager/api/src/main/kotlin/Scan.kt", "@MapperScan(\"me.pinfort.tsvideos.processing.persistence\")"),
            )
        val otherAllowed =
            listOf(
                fixture(
                    "processor/console/src/main/kotlin/Allowed.kt",
                    "import me.pinfort.tsvideos.processing.api.ProcessingPersistence",
                    "import me.pinfort.tsvideos.processing.api.ProcessingJobCommand",
                    "import me.pinfort.tsvideos.processing.api.config.ProcessorToolConfigurationProperties",
                    "import me.pinfort.tsvideos.processing.api.shell.ShellClient",
                    "import me.pinfort.tsvideos.processing.api.tool.TsSplitterClient",
                    "import me.pinfort.tsvideos.processing.domain.ProcessingJob",
                    "@MapperScan(basePackages = [CatalogPersistence.MAPPER_PACKAGE, ProcessingPersistence.MAPPER_PACKAGE])",
                    "@SpringBootApplication(scanBasePackages = [\"me.pinfort.tsvideos.catalog\", \"me.pinfort.tsvideos.processing\"])",
                    "@ConfigurationPropertiesScan(basePackages = [\"me.pinfort.tsvideos.processing.api.config\"])",
                    "// processing's mappers (me.pinfort.tsvideos.processing.persistence) stay internal",
                    "/**",
                    " * [me.pinfort.tsvideos.processing.persistence.ProcessingJobMapper] is never named outside processing",
                    " */",
                ),
                fixture(
                    "processor/console/src/main/resources/application.yaml",
                    "# me.pinfort.tsvideos.processing.persistence is registered through ProcessingPersistence.MAPPER_PACKAGE",
                ),
                fixture("processor/console/src/main/resources/mapper/Comment.xml", "<!-- me.pinfort.tsvideos.processing.persistence -->"),
            )

        val violations =
            ProcessingBoundaryRules.violations(
                projectDependencies = rejectedDependencies + allowedDependencies,
                processingSources = processingRejected + processingAllowed,
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
        (processingRejected + persistenceRejected + otherRejected).filterNot(::reported).forEach {
            problems += "${it.relativeTo(fixtureRoot).invariantSeparatorsPath} was not reported"
        }
        (listOf(processingAllowed, persistenceAllowed) + otherAllowed).filter(::reported).forEach {
            problems += "${it.relativeTo(fixtureRoot).invariantSeparatorsPath} was reported but is allowed"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Processing boundary rules are broken:\n" + (problems + "reported: $violations").joinToString("\n") { "  $it" },
            )
        }
    }
}

val checkProcessingBoundaries by tasks.registering {
    group = "verification"
    description = "Fails when processing depends on a forbidden project or other modules reach processing internals."
    dependsOn(checkProcessingBoundaryRules)
    val projectDependencies =
        provider {
            configurations.flatMap { configuration ->
                configuration.dependencies.withType<ProjectDependency>().map { "${configuration.name} -> ${it.path}" }
            }
        }
    val ownSources = fileTree("src") { include("**/*.kt") }
    val persistenceSources = fileTree("src/main/kotlin/me/pinfort/tsvideos/processing/persistence") { include("**/*.kt") }
    val otherSources =
        fileTree(rootDir) {
            include("**/src/**/*.kt", "**/src/**/*.java", "**/src/**/*.xml")
            include("**/src/**/*.yaml", "**/src/**/*.yml", "**/src/**/*.properties")
            exclude("processing/**", "**/build/**", ".gradle/**")
        }
    // manager の実行アプリのうち、processing の実行時隔離の検査 (runtime-isolation.gradle.kts) を適用していないもの
    val unguardedManagerApps =
        provider {
            listOf(":manager:api", ":manager:console").filter { path ->
                rootProject.findProject(path)?.tasks?.findByName("checkProcessingRuntimeIsolation") == null
            }
        }
    val rootPath = rootDir
    inputs.property("projectDependencies", projectDependencies)
    inputs.property("unguardedManagerApps", unguardedManagerApps)
    inputs.files(ownSources, otherSources)
    doLast {
        if (persistenceSources.isEmpty) {
            throw GradleException("processing persistence sources not found; update checkProcessingBoundaries")
        }
        val violations =
            ProcessingBoundaryRules.violations(projectDependencies.get(), ownSources, persistenceSources, otherSources, rootPath) +
                unguardedManagerApps.get().map { "$it does not apply processing/runtime-isolation.gradle.kts" }
        if (violations.isNotEmpty()) {
            throw GradleException("Processing boundary violations:\n" + violations.joinToString("\n") { "  $it" })
        }
    }
}

tasks.check {
    dependsOn(checkProcessingBoundaries)
}
