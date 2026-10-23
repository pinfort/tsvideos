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

tasks.test {
    useJUnitPlatform()
}

dependencies {
    implementation(project(":storage"))
    implementation(project(":catalog"))
    implementation(project(":library"))
    implementation(project(":diagnostics"))
    implementation(project(":console-common"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation(libs.mybatis.spring.boot)
    implementation(libs.clikt)
    implementation("tools.jackson.core:jackson-databind")
    // DataSource (application-datasource.yaml) はこのアプリのもの。#139 まで core が持っていた JDBC ドライバも明示する
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(module = "mockito-core") // Kotestを使う
    }
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
}

// Clikt(mordant)の端末制御がJNA経由でSystem.loadを呼ぶため、JDK24以降では
// 制限付きメソッドの警告が出る。実行可能ジャー/bootRunに明示的にネイティブアクセスを許可する。
tasks.bootJar {
    manifest {
        attributes("Enable-Native-Access" to "ALL-UNNAMED")
    }
}

tasks.bootRun {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
