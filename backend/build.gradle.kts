/**
 * ktlint の実行用クラスパスを、アプリケーションの依存管理から切り離す。
 *
 * io.spring.dependency-management はすべての設定 (configuration) に Spring Boot の BOM の版を当てる規則を足し、
 * Spring Boot プラグインは BOM の `kotlin.version` を Kotlin Gradle プラグインの版にそろえる。そのため ktlint が
 * 自分の POM で求める kotlin-compiler-embeddable などまでアプリケーションの Kotlin の版に置き換わり、
 * ktlint が Kotlin のソースを解析できなくなる (Extensions storage is not registered)。
 *
 * ktlint プラグインの設定 (`ktlint`、`ktlintRuleset`、`ktlintReporter`、`ktlintBaselineReporter`) でだけ、
 * 依存管理の規則の後に、要求された版 (ktlint の POM の版) へ戻す規則を足す。アプリケーションの設定の解決は変えない。
 * どちらのプラグインが先に適用されても依存管理の規則の後に足すよう、両方の適用を待ってから登録する。
 */
subprojects {
    pluginManager.withPlugin("org.jlleitschuh.gradle.ktlint") {
        pluginManager.withPlugin("io.spring.dependency-management") {
            configurations.matching { it.name.startsWith("ktlint") }.configureEach {
                resolutionStrategy.eachDependency {
                    val declared = requested.version
                    if (!declared.isNullOrEmpty()) useVersion(declared)
                }
            }
        }
    }
}
