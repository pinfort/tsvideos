plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.spring)
    alias(libs.plugins.spring)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
}

dependencies {
    implementation(project(":core"))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("me.pinfort:tsselect:1.0.0")

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.extensions.spring)
    testImplementation(libs.mybatis.spring.test)
    testImplementation(libs.mybatis.spring.boot)
    testImplementation(libs.testcontainers.core)
    testImplementation(libs.testcontainers.junit5)
    testImplementation(libs.testcontainers.mariadb)
    testImplementation(libs.testcontainers.spring.boot)
    testRuntimeOnly("org.mariadb.jdbc:mariadb-java-client")
    testImplementation("org.springframework.boot:spring-boot-starter-test") { exclude(module = "mockito-core") }
    testRuntimeOnly(libs.junit.platform.launcher)
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

tasks.bootJar {
    enabled = false
}

sourceSets.test { resources.srcDir(project(":core").file("src/test/resources")) }
