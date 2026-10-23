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

// diagnostics が依存してよいプロジェクトは storage だけ (backend/docs/module-boundaries.md §2)。
dependencies {
    // NAS の診断用接続 (DiagnosticNasClient) は storage の公開型。利用側も storage へ明示的に依存する
    implementation(project(":storage"))
    implementation("org.springframework.boot:spring-boot-starter")

    // DatabaseDiagnosticProbe が DriverManager で jdbc:mariadb:// に接続する
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(module = "mockito-core") // Kotestを使う
    }
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    // DiagnosticsContractTest が公開シグネチャをリフレクションで調べる
    testImplementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.mariadb)
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
 * diagnostics の境界ルール (module-boundaries.md §11.7)。checkDiagnosticsBoundaries と、その自己検査
 * checkDiagnosticsBoundaryRules が同じ判定を使う。
 * - diagnostics のプロジェクト依存は storage だけ (core・catalog・processor・manager などへの逆向きの依存を拒否する)
 * - diagnostics のソース (テストを含む) は diagnostics と storage 以外の `me.pinfort.tsvideos` パッケージを参照しない
 * - diagnostics 以外のソースは公開パッケージ (`api`、`domain`) だけを参照し、今後の内部パッケージを参照しない
 *
 * パッケージ名そのもの (`"me.pinfort.tsvideos.diagnostics"`) はコンポーネントスキャン対象として許可する。
 * storage の内部パッケージを参照しないことは storage の checkStorageBoundaries が検査する。
 */
object DiagnosticsBoundaryRules {
    private val foreignPackage = Regex("""\bme\.pinfort\.tsvideos\.(?!diagnostics\b|storage\b)\w+""")
    private val internals = Regex("""\bme\.pinfort\.tsvideos\.diagnostics\.(?!api\b|domain\b)\w+""")

    fun violations(
        projectDependencies: List<String>,
        diagnosticsSources: Iterable<File>,
        otherSources: Iterable<File>,
        root: File,
    ): List<String> {
        val violations = mutableListOf<String>()
        // "<configuration> -> <project>" の形
        projectDependencies.filterNot { it.substringAfter(" -> ") == ":storage" }.forEach {
            violations += "diagnostics declares a forbidden project dependency: $it"
        }
        // コメントも含めて数える。diagnostics のソースが他モジュールのパッケージ名を書く必要はない
        diagnosticsSources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                foreignPackage.find(line)?.let {
                    violations += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} references ${it.value} from diagnostics"
                }
            }
        }
        otherSources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val text = line.trimStart()
                if (!text.startsWith("//") && !text.startsWith("*") && internals.containsMatchIn(line)) {
                    violations +=
                        "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} reaches a diagnostics internal: ${line.trim()}"
                }
            }
        }
        return violations
    }
}

/**
 * 境界ルールが違反を見逃さないことを、違反を含むフィクスチャで確かめる (ルールの変更で検査が素通りになるのを防ぐ)。
 * 許可されるもの (storage 依存、公開パッケージの参照、スキャン用のパッケージ名、コメント) が違反にならないことも確かめる。
 */
val checkDiagnosticsBoundaryRules by tasks.registering {
    group = "verification"
    description = "Fails when the diagnostics boundary rules stop rejecting known violations."
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
                "implementation -> :catalog",
                "testImplementation -> :processor:infrastructure",
                "api -> :manager:infrastructure",
            )
        val allowedDependencies = listOf("implementation -> :storage")
        val diagnosticsRejected =
            listOf(
                fixture(
                    "diagnostics/src/main/kotlin/Reverse.kt",
                    "package me.pinfort.tsvideos.diagnostics.api",
                    "import me.pinfort.tsvideos.core.component.LocalPreflightProbe",
                ),
                fixture("diagnostics/src/test/kotlin/Catalog.kt", "import me.pinfort.tsvideos.catalog.api.ProgramCommand"),
            )
        val diagnosticsAllowed =
            fixture(
                "diagnostics/src/main/kotlin/Allowed.kt",
                "package me.pinfort.tsvideos.diagnostics.api",
                "import me.pinfort.tsvideos.diagnostics.domain.DiagnosticReport",
                "import me.pinfort.tsvideos.storage.api.DiagnosticNasClient",
            )
        val otherRejected =
            listOf(
                fixture("core/src/main/kotlin/Internal.kt", "import me.pinfort.tsvideos.diagnostics.probe.Anything"),
                fixture("processor/console/src/test/kotlin/Named.kt", "val type = me.pinfort.tsvideos.diagnostics.internal.Probe::class"),
            )
        val otherAllowed =
            fixture(
                "manager/console/src/main/kotlin/Allowed.kt",
                "import me.pinfort.tsvideos.diagnostics.api.EnvironmentDiagnosticCommand",
                "import me.pinfort.tsvideos.diagnostics.domain.DiagnosticCheck",
                "@SpringBootApplication(scanBasePackages = [\"me.pinfort.tsvideos.diagnostics\", \"me.pinfort.tsvideos.core\"])",
                "// a future me.pinfort.tsvideos.diagnostics.internal package stays hidden",
                " * me.pinfort.tsvideos.diagnostics.internal is never named outside diagnostics",
            )

        val violations =
            DiagnosticsBoundaryRules.violations(
                projectDependencies = rejectedDependencies + allowedDependencies,
                diagnosticsSources = diagnosticsRejected + diagnosticsAllowed,
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
        (diagnosticsRejected + otherRejected).filterNot(::reported).forEach {
            problems += "${it.relativeTo(fixtureRoot).invariantSeparatorsPath} was not reported"
        }
        listOf(diagnosticsAllowed, otherAllowed).filter(::reported).forEach {
            problems += "${it.relativeTo(fixtureRoot).invariantSeparatorsPath} was reported but is allowed"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Diagnostics boundary rules are broken:\n" + (problems + "reported: $violations").joinToString("\n") { "  $it" },
            )
        }
    }
}

val checkDiagnosticsBoundaries by tasks.registering {
    group = "verification"
    description = "Fails when diagnostics depends on a forbidden project or other modules reach diagnostics internals."
    dependsOn(checkDiagnosticsBoundaryRules)
    val projectDependencies =
        provider {
            configurations.flatMap { configuration ->
                configuration.dependencies.withType<ProjectDependency>().map { "${configuration.name} -> ${it.path}" }
            }
        }
    val ownSources = fileTree("src") { include("**/*.kt") }
    val otherSources =
        fileTree(rootDir) {
            include("**/src/**/*.kt")
            exclude("diagnostics/**", "**/build/**", ".gradle/**")
        }
    val rootPath = rootDir
    inputs.property("projectDependencies", projectDependencies)
    inputs.files(ownSources, otherSources)
    doLast {
        val violations = DiagnosticsBoundaryRules.violations(projectDependencies.get(), ownSources, otherSources, rootPath)
        if (violations.isNotEmpty()) {
            throw GradleException("Diagnostics boundary violations:\n" + violations.joinToString("\n") { "  $it" })
        }
    }
}

tasks.check {
    dependsOn(checkDiagnosticsBoundaries)
}
