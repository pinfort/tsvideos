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

/**
 * ビルド時のgitコミットハッシュ。gitリポジトリ外（Dockerビルドなど）では "unknown" になる。
 */
abstract class GitCommitHashValueSource : ValueSource<String, GitCommitHashValueSource.Parameters> {
    interface Parameters : ValueSourceParameters {
        val workingDirectory: DirectoryProperty
    }

    override fun obtain(): String =
        runCatching {
            val process =
                ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .directory(parameters.workingDirectory.get().asFile)
                    .redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .use { it.readText() }
                    .trim()
            output.takeIf { process.waitFor() == 0 && it.isNotBlank() }
        }.getOrNull() ?: "unknown"
}

val gitCommitHash =
    providers.of(GitCommitHashValueSource::class.java) {
        parameters.workingDirectory.set(layout.projectDirectory)
    }

// CLIの--versionが参照するバージョン情報をリソースとして埋め込む
val generateVersionProperties by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("generated/version/version.properties")
    property("version", project.version.toString())
    property("commit", gitCommitHash)
}

tasks.processResources {
    from(generateVersionProperties) {
        into("me/pinfort/tsvideos/core")
    }
}

// #165 以降の core は LoggerConfig・ApplicationVersion・DataSource の設定 (application-core.yaml) だけを持つ (#139 まで)。
// 業務モジュール (storage・catalog・processing など) には依存しない。processing → core も禁止
dependencies {
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(module = "mockito-core") // Kotestを使う
    }
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    // StorageCallerBoundaryTest が core の設定の合成を diagnostics の DiagnosticEnvironment で読む。
    // core の application.yaml が include する storage プロファイルの設定 (application-storage.yaml) は storage の資源
    testImplementation(project(":diagnostics"))
    testRuntimeOnly(project(":storage"))
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
