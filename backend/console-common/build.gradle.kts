plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.spring)
    alias(libs.plugins.spring)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.ktlint)
}

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

dependencies {
    implementation("org.springframework:spring-context")

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.mockk)
}
