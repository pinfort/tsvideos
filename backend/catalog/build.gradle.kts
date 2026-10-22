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

// catalog が依存してよいプロジェクトは storage だけ (backend/docs/module-boundaries.md §2)。DB テストだけ database-schema のテストフィクスチャを使う。
dependencies {
    // storage の公開型 (NasStore など) は catalog の公開シグネチャにも出る。利用側も storage へ明示的に依存する
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
 * catalog の境界ルール (module-boundaries.md §11.7)。checkCatalogBoundaries と、その自己検査 checkCatalogBoundaryRules が
 * 同じ判定を使う。
 * - catalog のプロジェクト依存は storage だけ。database-schema はテストの設定からだけ使ってよい
 *   (core・processor・manager などの旧モジュールや業務モジュールへの逆向きの依存を拒否する)
 * - catalog のソース (テストを含む) は catalog と storage 以外の `me.pinfort.tsvideos` パッケージを参照しない
 * - catalog 以外のソースと資源 (テスト、MyBatis の XML、YAML、properties を含む) は catalog の公開パッケージ
 *   (`api`、`domain`) だけを参照し、`persistence` と今後の内部パッケージ (`application` など) を参照しない
 * - persistence のトップレベル宣言はすべて internal (または private)。同じ行の注釈や字下げされた宣言も含める
 *
 * Kotlin・Java のコメントと文字列は SourceScanner で区別する (`*` で始まる行を一律にコメントとはみなさない)。
 * パッケージ名そのもの (`"me.pinfort.tsvideos.catalog"`) はコンポーネントスキャン対象として許可する。Mapper の登録先は
 * `CatalogPersistence.MAPPER_PACKAGE` で受け取るので、persistence のパッケージ名を catalog の外に書く必要はない。
 */
object CatalogBoundaryRules {
    // database-schema の検査は、テストフィクスチャとしての宣言以外にプロジェクトパスを引用符付きで書く行を拒否するので、":" を付けずに持つ
    private const val SCHEMA = "database-schema"
    private val allowedDependencies = setOf(":storage")
    private val foreignPackage = Regex("""\bme\.pinfort\.tsvideos\.(?!catalog\b|storage\b)\w+""")
    private val internals = Regex("""\bme\.pinfort\.tsvideos\.catalog\.(?!api\b|domain\b)\w+""")

    // 宣言の前の注釈 (同じ行の引数と、1 段の入れ子の括弧を含む)。文字列は SourceScanner が取り除いてから当てる
    private val annotations = Regex("""^(?:@[\w.:]+(?:\((?:[^()]|\([^()]*\))*\))?\s*)*""")

    // 修飾子 (グループ 1) と宣言のキーワード。名前が続くものだけを宣言とみなす (式の続きの `object : Foo {` などは除く)
    private val declaration =
        Regex(
            """^((?:(?:public|internal|private|data|enum|sealed|abstract|open|annotation|value|const|lateinit|inline|fun|""" +
                """expect|actual|external|operator|infix|suspend|tailrec)\s+)*)""" +
                """(?:class|interface|object|fun|val|var|typealias)\s+[\w<`]""",
        )
    private val hidden = Regex("""\b(?:internal|private)\b""")
    private val sourceExtensions = setOf("kt", "java")

    // Kotlin・Java 以外の資源の行コメント。Kotlin・Java のコメントは SourceScanner が取り除く
    private val resourceCommentStarts =
        mapOf("xml" to listOf("<!--"), "yaml" to listOf("#"), "yml" to listOf("#"), "properties" to listOf("#", "!"))

    /**
     * Kotlin・Java のソースを 1 行ずつ読み、コメントを取り除いた行を返す。複数行のブロックコメントと raw 文字列の状態、
     * 括弧の深さ ([depth]) を行をまたいで持ち越す。構文解析器ではなく、境界検査に足りるだけの字句の区別しかしない
     * (入れ子のブロックコメントや、文字列テンプレートの中の引用符は正しく扱わない)。
     */
    private class SourceScanner(
        private val keepStrings: Boolean,
    ) {
        private var blockComment = false
        private var rawString = false
        var depth = 0
            private set

        fun code(line: String): String {
            val code = StringBuilder()
            var i = 0
            while (i < line.length) {
                when {
                    blockComment -> {
                        if (line.startsWith("*/", i)) blockComment = false
                        i += if (blockComment) 1 else 2
                    }
                    rawString -> {
                        if (line.startsWith("\"\"\"", i)) {
                            rawString = false
                            code.append(' ')
                            i += 3
                        } else {
                            if (keepStrings) code.append(line[i])
                            i++
                        }
                    }
                    line.startsWith("//", i) -> i = line.length
                    line.startsWith("/*", i) -> {
                        blockComment = true
                        i += 2
                    }
                    line.startsWith("\"\"\"", i) -> {
                        rawString = true
                        i += 3
                    }
                    line[i] == '"' || line[i] == '\'' -> {
                        val quote = line[i++]
                        while (i < line.length && line[i] != quote) {
                            if (line[i] == '\\') i++
                            if (keepStrings && i < line.length) code.append(line[i])
                            i++
                        }
                        code.append(' ')
                        i++
                    }
                    else -> {
                        when (line[i]) {
                            '{', '(', '[' -> depth++
                            '}', ')', ']' -> depth--
                        }
                        code.append(line[i++])
                    }
                }
            }
            return code.toString()
        }
    }

    // 注釈を外した先頭が internal・private の付かない宣言なら公開されている
    private fun exposed(code: String): Boolean {
        val match = declaration.find(code.trim().replaceFirst(annotations, "")) ?: return false
        return !hidden.containsMatchIn(match.groupValues[1])
    }

    fun violations(
        projectDependencies: List<String>,
        catalogSources: Iterable<File>,
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
            if (!allowed) violations += "catalog declares a forbidden project dependency: $dependency"
        }
        // コメントも含めて数える。catalog のソースが他モジュールのパッケージ名を書く必要はない
        catalogSources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                foreignPackage.find(line)?.let {
                    violations += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} references ${it.value} from catalog"
                }
            }
        }
        // 括弧の外 (トップレベル) で始まる行だけを見る。internal なクラスのメンバーや引数は対象外
        persistenceSources.forEach { file ->
            val scanner = SourceScanner(keepStrings = false)
            file.readLines().forEachIndexed { index, line ->
                val topLevel = scanner.depth <= 0
                if (exposed(scanner.code(line)) && topLevel) {
                    violations += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} must be internal: ${line.trim()}"
                }
            }
        }
        otherSources.forEach { file ->
            val scanner = if (file.extension in sourceExtensions) SourceScanner(keepStrings = true) else null
            val commentStarts = resourceCommentStarts[file.extension].orEmpty()
            file.readLines().forEachIndexed { index, line ->
                val code =
                    when {
                        scanner != null -> scanner.code(line)
                        commentStarts.any { line.trimStart().startsWith(it) } -> ""
                        else -> line
                    }
                if (internals.containsMatchIn(code)) {
                    violations += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} reaches a catalog internal: ${line.trim()}"
                }
            }
        }
        return violations
    }
}

/**
 * 境界ルールが違反を見逃さないことを、違反を含むフィクスチャで確かめる (ルールの変更で検査が素通りになるのを防ぐ)。
 * 許可されるもの (公開パッケージの参照、スキャン用のパッケージ名、Mapper 登録先の定数、コメント、internal の宣言) が
 * 違反にならないことも確かめる。
 */
val checkCatalogBoundaryRules by tasks.registering {
    group = "verification"
    description = "Fails when the catalog boundary rules stop rejecting known violations."
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
                "testImplementation -> :processor:infrastructure",
                "api -> :manager:infrastructure",
                "implementation -> :database-schema",
            )
        val allowedDependencies = listOf("implementation -> :storage", "testImplementation -> :database-schema")
        val catalogRejected =
            listOf(
                fixture(
                    "catalog/src/main/kotlin/Reverse.kt",
                    "package me.pinfort.tsvideos.catalog.api",
                    "import me.pinfort.tsvideos.core.domain.BulkSnapshot",
                ),
                fixture("catalog/src/test/kotlin/Processing.kt", "// waits for me.pinfort.tsvideos.processor.infrastructure"),
            )
        val catalogAllowed =
            fixture(
                "catalog/src/test/kotlin/Allowed.kt",
                "package me.pinfort.tsvideos.catalog",
                "import me.pinfort.tsvideos.storage.api.NasStore",
                "@SpringBootApplication(scanBasePackages = [\"me.pinfort.tsvideos.storage\", \"me.pinfort.tsvideos.catalog\"])",
            )
        val persistenceRejected =
            listOf(
                fixture("catalog/src/main/kotlin/persistence/LeakedMapper.kt", "@Mapper", "interface LeakedMapper {", "}"),
                fixture("catalog/src/main/kotlin/persistence/LeakedDto.kt", "public data class LeakedDto(", "    val id: Long,", ")"),
                fixture("catalog/src/main/kotlin/persistence/SameLineMapper.kt", "@Mapper interface SameLineMapper {", "}"),
                fixture(
                    "catalog/src/main/kotlin/persistence/AnnotatedPublic.kt",
                    "@Suppress(\"unused\") @JvmInline public value class Id(val id: Long)",
                ),
                fixture("catalog/src/main/kotlin/persistence/Indented.kt", "    data class IndentedDto(val id: Long)"),
                fixture(
                    "catalog/src/main/kotlin/persistence/MultilineAnnotation.kt",
                    "@file:Suppress(\"unused\")",
                    "@Deprecated(",
                    "    \"use (another) { mapper\",",
                    ")",
                    "fun interface LeakedQuery {",
                    "    fun run(): Int",
                    "}",
                ),
                // 文字列やコメントの中の括弧で深さがずれない
                fixture(
                    "catalog/src/main/kotlin/persistence/AfterRawString.kt",
                    "internal const val SQL = \"\"\"",
                    "    SELECT '{' FROM x",
                    "\"\"\"",
                    "/* ( */ internal val brace = '{'",
                    "object LeakedObject",
                ),
            )
        val persistenceAllowed =
            fixture(
                "catalog/src/main/kotlin/persistence/Hidden.kt",
                "package me.pinfort.tsvideos.catalog.persistence",
                "",
                "@Mapper",
                "internal interface HiddenMapper {",
                "    fun find(id: Long): HiddenDto?",
                "}",
                "",
                "@Mapper internal interface SameLineMapper {",
                "    @Select(\"SELECT * FROM x WHERE id = #{id}\") fun select(@Param(\"id\") id: Long): Int",
                "",
                "    @Select(",
                "        \"\"\"",
                "        SELECT '}' FROM x",
                "        \"\"\",",
                "    )",
                "    fun raw(): Int",
                "}",
                "",
                "internal data class HiddenDto(",
                "    val id: Long,",
                ")",
                "",
                "@Suppress(\"unused\") private fun helper() = Unit",
                "",
                "@JvmField internal val anonymous =",
                "    object : Runnable {",
                "        override fun run() = Unit",
                "    }",
                "",
                "/*",
                "public class InComment",
                " */",
                "private const val LIMIT = 1000",
            )
        val otherRejected =
            listOf(
                fixture("core/src/main/kotlin/ImportsMapper.kt", "import me.pinfort.tsvideos.catalog.persistence.ProgramMapper"),
                fixture("core/src/test/kotlin/NamesDto.kt", "    val type = me.pinfort.tsvideos.catalog.persistence.ProgramDto::class"),
                fixture("processor/console/src/test/kotlin/Internal.kt", "import me.pinfort.tsvideos.catalog.application.Anything"),
                fixture(
                    "manager/api/src/main/resources/mapper/ProgramMapper.xml",
                    "<mapper namespace=\"me.pinfort.tsvideos.catalog.persistence.ProgramMapper\">",
                ),
                fixture(
                    "manager/console/src/main/resources/application.yaml",
                    "  type-aliases-package: me.pinfort.tsvideos.catalog.persistence",
                ),
                fixture("manager/api/src/main/kotlin/Scan.kt", "@MapperScan(\"me.pinfort.tsvideos.catalog.persistence\")"),
                // `*` で始まっても、ブロックコメントの外ならコードとして読む
                fixture(
                    "core/src/main/kotlin/Multiplied.kt",
                    "val size = base",
                    "    * me.pinfort.tsvideos.catalog.persistence.Limits.FACTOR",
                ),
                fixture("core/src/main/kotlin/AfterComment.kt", "/* ok */ import me.pinfort.tsvideos.catalog.persistence.ProgramMapper"),
                fixture(
                    "core/src/main/java/QuotedMarker.java",
                    "String marker = \"/*\";",
                    "import me.pinfort.tsvideos.catalog.persistence.ProgramMapper;",
                ),
            )
        val otherAllowed =
            listOf(
                fixture(
                    "manager/api/src/main/kotlin/Allowed.kt",
                    "import me.pinfort.tsvideos.catalog.api.CatalogPersistence",
                    "import me.pinfort.tsvideos.catalog.api.ProgramCommand",
                    "import me.pinfort.tsvideos.catalog.domain.Program",
                    "@MapperScan(basePackages = [\"me.pinfort.tsvideos.core.external.database.mapper\", CatalogPersistence.MAPPER_PACKAGE])",
                    "@SpringBootApplication(scanBasePackages = [\"me.pinfort.tsvideos.catalog\", \"me.pinfort.tsvideos.core\"])",
                    "// catalog's mappers (me.pinfort.tsvideos.catalog.persistence) stay internal",
                    "/**",
                    " * [me.pinfort.tsvideos.catalog.persistence.ProgramMapper] is never named outside catalog",
                    "   me.pinfort.tsvideos.catalog.persistence is internal",
                    " */",
                    "val url = \"http://example.com\" // me.pinfort.tsvideos.catalog.persistence",
                ),
                fixture(
                    "manager/api/src/main/resources/application.yaml",
                    "# me.pinfort.tsvideos.catalog.persistence is registered through CatalogPersistence.MAPPER_PACKAGE",
                ),
                fixture("manager/api/src/main/resources/mapper/Comment.xml", "<!-- me.pinfort.tsvideos.catalog.persistence -->"),
            )

        val violations =
            CatalogBoundaryRules.violations(
                projectDependencies = rejectedDependencies + allowedDependencies,
                catalogSources = catalogRejected + catalogAllowed,
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
        (catalogRejected + persistenceRejected + otherRejected).filterNot(::reported).forEach {
            problems += "${it.relativeTo(fixtureRoot).invariantSeparatorsPath} was not reported"
        }
        (listOf(catalogAllowed, persistenceAllowed) + otherAllowed).filter(::reported).forEach {
            problems += "${it.relativeTo(fixtureRoot).invariantSeparatorsPath} was reported but is allowed"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Catalog boundary rules are broken:\n" + (problems + "reported: $violations").joinToString("\n") { "  $it" },
            )
        }
    }
}

val checkCatalogBoundaries by tasks.registering {
    group = "verification"
    description = "Fails when catalog depends on a forbidden project or other modules reach catalog internals."
    dependsOn(checkCatalogBoundaryRules)
    val projectDependencies =
        provider {
            configurations.flatMap { configuration ->
                configuration.dependencies.withType<ProjectDependency>().map { "${configuration.name} -> ${it.path}" }
            }
        }
    val ownSources = fileTree("src") { include("**/*.kt") }
    val persistenceSources = fileTree("src/main/kotlin/me/pinfort/tsvideos/catalog/persistence") { include("**/*.kt") }
    val otherSources =
        fileTree(rootDir) {
            include("**/src/**/*.kt", "**/src/**/*.java", "**/src/**/*.xml")
            include("**/src/**/*.yaml", "**/src/**/*.yml", "**/src/**/*.properties")
            exclude("catalog/**", "**/build/**", ".gradle/**")
        }
    val rootPath = rootDir
    inputs.property("projectDependencies", projectDependencies)
    inputs.files(ownSources, otherSources)
    doLast {
        if (persistenceSources.isEmpty) throw GradleException("catalog persistence sources not found; update checkCatalogBoundaries")
        val violations =
            CatalogBoundaryRules.violations(projectDependencies.get(), ownSources, persistenceSources, otherSources, rootPath)
        if (violations.isNotEmpty()) {
            throw GradleException("Catalog boundary violations:\n" + violations.joinToString("\n") { "  $it" })
        }
    }
}

tasks.check {
    dependsOn(checkCatalogBoundaries)
}
