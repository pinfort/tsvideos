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

// storage は業務モジュールに依存しない (backend/docs/module-boundaries.md §2)。プロジェクト依存は追加しない。
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    // Spring の Kotlin 対応 (samba.* の data class へのコンストラクタバインド) に必要
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(libs.samba)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.core)
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
 * storage の境界ルール (module-boundaries.md §11.7)。checkStorageBoundaries と、その自己検査
 * checkStorageBoundaryRules が同じ判定を使う。
 * - storage はどのプロジェクトにも依存しない (Gradle の依存と、ソースの参照の両方)
 * - storage 以外のソースは公開パッケージ `me.pinfort.tsvideos.storage.api` だけを参照し、それ以外の
 *   storage パッケージ (Samba 実装など、今後増えるものも含む) と jcifs を参照しない
 * - storage 以外のビルドスクリプトは jcifs を直接宣言しない
 *
 * パッケージ名そのもの (`"me.pinfort.tsvideos.storage"`) はアプリケーションとテストのコンポーネントスキャン
 * 対象として意図的に許可する。内部クラスはスキャン経由で組み立てられ、名前では参照されない。
 */
object StorageBoundaryRules {
    private val foreignPackage = Regex("""\bme\.pinfort\.tsvideos\.(?!storage\b)\w+""")
    private val internals = Regex("""\bme\.pinfort\.tsvideos\.storage\.(?!api\b)\w+|^\s*import\s+jcifs\.|\bjcifs\.\w+\.""")

    fun violations(
        projectDependencies: List<String>,
        storageSources: Iterable<File>,
        otherSources: Iterable<File>,
        otherBuildScripts: Iterable<File>,
        root: File,
    ): List<String> {
        val violations = mutableListOf<String>()
        projectDependencies.forEach { violations += "storage declares a project dependency: $it" }
        // コメントも含めて数える。storage のソースが他モジュールのパッケージ名を書く必要はない
        storageSources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                foreignPackage.find(line)?.let {
                    violations += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} references ${it.value} from storage"
                }
            }
        }
        otherSources.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val text = line.trimStart()
                if (!text.startsWith("//") && !text.startsWith("*") && internals.containsMatchIn(line)) {
                    violations += "${file.relativeTo(root).invariantSeparatorsPath}:${index + 1} reaches a storage internal: ${line.trim()}"
                }
            }
        }
        otherBuildScripts.forEach { file ->
            if (file.readLines().any { it.contains("libs.samba") || it.contains("jcifs") }) {
                violations += "${file.relativeTo(root).invariantSeparatorsPath} declares jcifs; depend on project(\":storage\") instead"
            }
        }
        return violations
    }
}

/**
 * 境界ルールが違反を見逃さないことを、違反を含むフィクスチャで確かめる (ルールの変更で検査が素通りになるのを防ぐ)。
 * 許可されるもの (api の参照、スキャン用のパッケージ名、コメント) が違反にならないことも確かめる。
 */
val checkStorageBoundaryRules by tasks.registering {
    group = "verification"
    description = "Fails when the storage boundary rules stop rejecting known violations."
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

        val storageReverse =
            fixture(
                "storage/src/main/kotlin/Reverse.kt",
                "package me.pinfort.tsvideos.storage.api",
                "import me.pinfort.tsvideos.core.domain.UploadManifest",
            )
        val storageAllowed =
            fixture(
                "storage/src/main/kotlin/Allowed.kt",
                "package me.pinfort.tsvideos.storage.samba",
                "import jcifs.SmbResource",
            )
        val rejected =
            listOf(
                fixture("core/src/main/kotlin/ImportsSamba.kt", "import me.pinfort.tsvideos.storage.samba.SambaClient"),
                fixture("core/src/test/kotlin/NamesSamba.kt", "    val client = me.pinfort.tsvideos.storage.samba.SambaClient::class"),
                fixture("manager/api/src/main/kotlin/OtherInternal.kt", "import me.pinfort.tsvideos.storage.impl.Anything"),
                fixture("processor/console/src/test/kotlin/Jcifs.kt", "import jcifs.SmbResource"),
                fixture("core/src/main/kotlin/JcifsName.kt", "    val type = jcifs.smb.SmbFile::class"),
            )
        val allowed =
            fixture(
                "core/src/main/kotlin/Allowed.kt",
                "import me.pinfort.tsvideos.storage.api.NasStore",
                "@SpringBootApplication(scanBasePackages = [\"me.pinfort.tsvideos.storage\", \"me.pinfort.tsvideos.core\"])",
                "// the Samba implementation (me.pinfort.tsvideos.storage.samba) stays internal",
                " * jcifs.smb.SmbFile is never named outside storage",
            )
        val declaresJcifs = fixture("core/build.gradle.kts", "dependencies {", "    implementation(libs.samba)", "}")
        val allowedScript = fixture("manager/api/build.gradle.kts", "dependencies {", "    implementation(project(\":storage\"))", "}")

        val violations =
            StorageBoundaryRules.violations(
                projectDependencies = listOf("implementation -> :core"),
                storageSources = listOf(storageReverse, storageAllowed),
                otherSources = rejected + allowed,
                otherBuildScripts = listOf(declaresJcifs, allowedScript),
                root = fixtureRoot,
            )

        fun reported(file: File) = violations.any { it.startsWith(file.relativeTo(fixtureRoot).invariantSeparatorsPath) }
        val problems = mutableListOf<String>()
        if (violations.none { it.contains("project dependency: implementation -> :core") }) {
            problems += "a project dependency of storage was not reported"
        }
        (rejected + storageReverse + declaresJcifs).filterNot(::reported).forEach {
            problems += "${it.relativeTo(fixtureRoot).invariantSeparatorsPath} was not reported"
        }
        listOf(storageAllowed, allowed, allowedScript).filter(::reported).forEach {
            problems += "${it.relativeTo(fixtureRoot).invariantSeparatorsPath} was reported but is allowed"
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Storage boundary rules are broken:\n" + (problems + "reported: $violations").joinToString("\n") { "  $it" },
            )
        }
    }
}

val checkStorageBoundaries by tasks.registering {
    group = "verification"
    description = "Fails when storage depends on another project or other modules reach storage internals."
    dependsOn(checkStorageBoundaryRules)
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
            exclude("storage/**", "**/build/**", ".gradle/**")
        }
    val otherBuildScripts =
        fileTree(rootDir) {
            include("**/build.gradle.kts")
            exclude("storage/**", "**/build/**", ".gradle/**")
        }
    val rootPath = rootDir
    inputs.property("projectDependencies", projectDependencies)
    inputs.files(ownSources, otherSources, otherBuildScripts)
    doLast {
        val violations =
            StorageBoundaryRules.violations(projectDependencies.get(), ownSources, otherSources, otherBuildScripts, rootPath)
        if (violations.isNotEmpty()) {
            throw GradleException("Storage boundary violations:\n" + violations.joinToString("\n") { "  $it" })
        }
    }
}

tasks.check {
    dependsOn(checkStorageBoundaries)
}
