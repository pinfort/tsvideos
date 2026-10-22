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

dependencies {
    // storage の公開型 (NasStore など) は core の公開シグネチャにも出る。利用側も storage へ明示的に依存する
    implementation(project(":storage"))
    // catalog の公開型 (Program など) も core の公開シグネチャに出る。利用側も catalog へ明示的に依存する
    implementation(project(":catalog"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.core:jackson-databind")
    implementation(libs.mybatis.spring.boot)

    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

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
