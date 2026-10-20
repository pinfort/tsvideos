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

// DDLの単一の情報源は db/migrations。Testcontainers(TC_INITSCRIPT=ddl/01_create_database.sql)用に連結して生成する
val generateTestDdl by tasks.registering {
    val migrations = fileTree("src/main/resources/db/migrations") { include("*.sql") }
    val output = layout.buildDirectory.file("generated/test-ddl/ddl/01_create_database.sql")
    inputs.files(migrations)
    outputs.file(output)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(migrations.sortedBy { it.name }.joinToString("\n") { it.readText() })
    }
}

sourceSets.test {
    resources.srcDir(generateTestDdl.map { layout.buildDirectory.dir("generated/test-ddl") })
}

dependencies {
    // storage の公開型 (NasStore など) は core の公開シグネチャにも出る。利用側も storage へ明示的に依存する
    implementation(project(":storage"))
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
