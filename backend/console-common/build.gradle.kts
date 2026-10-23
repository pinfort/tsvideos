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

kotlin {
    jvmToolchain(24)

    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.test {
    useJUnitPlatform()
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

// 両 CLI の --version が参照するバージョン情報 (ApplicationVersion) をリソースとして埋め込む (#139 で core から移した)
val generateVersionProperties by tasks.registering(WriteProperties::class) {
    destinationFile = layout.buildDirectory.file("generated/version/version.properties")
    property("version", project.version.toString())
    property("commit", gitCommitHash)
}

tasks.processResources {
    from(generateVersionProperties) {
        into("me/pinfort/tsvideos/console/common")
    }
}

dependencies {
    implementation("org.springframework:spring-context")

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
}
