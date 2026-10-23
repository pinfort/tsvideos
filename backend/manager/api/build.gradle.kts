plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.spring)
    alias(libs.plugins.spring)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
}

// 実行時クラスパスに database-schema が入らないことの検査 (checkDatabaseSchemaRuntimeIsolation、check に含まれる)
apply(from = rootDir.resolve("database-schema/runtime-isolation.gradle.kts"))
// 実行時クラスパスに processing (処理専用ツールと設定) が入らないことの検査 (checkProcessingRuntimeIsolation、check に含まれる)
apply(from = rootDir.resolve("processing/runtime-isolation.gradle.kts"))
// 宣言されたモジュールグラフ全体と、このアプリの実行時に入るプロジェクト・処理専用ツールの検査 (checkModuleGraph、check に含まれる)
apply(from = rootDir.resolve("module-graph.gradle.kts"))

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
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(libs.mybatis.spring.boot)
    // DataSource (application-datasource.yaml) はこのアプリのもの。#139 まで core が持っていた JDBC ドライバも明示する
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

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
