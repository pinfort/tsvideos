plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
rootProject.name = "tsvideos"

include("core", "console-common","manager:infrastructure", "manager:console", "manager:api", "processor:infrastructure", "processor:console")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS) // プロジェクト側のrepositoriesを禁止
    repositories {
        google()
        mavenCentral()
    }
}
