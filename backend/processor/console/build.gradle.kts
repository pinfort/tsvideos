plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.spring)
    alias(libs.plugins.spring)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
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

dependencies {
    implementation(project(":core"))
    implementation(project(":console-common"))
    implementation(project(":processor:infrastructure"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation(libs.mybatis.spring.boot)
    implementation(libs.clikt)

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
