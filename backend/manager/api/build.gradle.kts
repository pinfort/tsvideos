plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.spring)
    alias(libs.plugins.spring)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
}

// 実行時クラスパスに database-schema が入らないことの検査 (checkDatabaseSchemaRuntimeIsolation、check に含まれる)
apply(from = rootDir.resolve("database-schema/runtime-isolation.gradle.kts"))

kotlin {
    jvmToolchain(24)

    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

dependencies {
    implementation(project(":storage"))
    implementation(project(":catalog"))
    implementation(project(":library"))
    implementation(project(":diagnostics"))
    // 移行中だけ (#164)。core の VerifiedUploadCommand が processing の Bean を使うので、スキャンと Mapper 登録の定数
    // (ProcessingPersistence.MAPPER_PACKAGE) のために依存する。#165 で外す
    implementation(project(":processing"))
    implementation(project(":core"))
    implementation(project(":manager:infrastructure"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(libs.mybatis.spring.boot)

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(module = "mockito-core") // Kotestを使う
    }
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.mockk)
    testImplementation(libs.springmockk)

    // testcontainers, for ApiApplicationTest (full context load, backed by a real DB)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.spring.boot)
    // DATABASE_CONNECTION=jdbc:tc:...?TC_INITSCRIPT=ddl/01_create_database.sql (CI) の初期化スクリプト
    testImplementation(testFixtures(project(":database-schema")))
}

tasks.test {
    useJUnitPlatform()
}
