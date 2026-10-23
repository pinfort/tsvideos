import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/**
 * モジュールグラフ全体の検査 (module-boundaries.md §2、§11.7、#139)。実行アプリ (manager:api、manager:console、
 * processor:console) の build.gradle.kts が plugins ブロックの後で `apply(from = rootDir.resolve("module-graph.gradle.kts"))`
 * として適用し、check に含める。各モジュールの境界チェック (checkCatalogBoundaries など) と実行時隔離の検査
 * (runtime-isolation.gradle.kts) はそのまま残し、ここではそれらが個別には見ない次の全体像を検査する。
 * - ビルドに含まれるプロジェクトが現在の構成と一致し、廃止した旧モジュール (core、manager:infrastructure、
 *   processor:infrastructure) が戻っていない
 * - 全プロジェクトの宣言されたプロジェクト依存が許可された辺だけ (test で始まる設定は database-schema のテストフィクスチャも可)
 * - このアプリの解決後の実行時クラスパスに入るプロジェクトが許可された集合に収まる (manager は processing を含まない、
 *   processor は library を含まない) こと、manager には処理専用ツールの依存 (me.pinfort:tsselect) も入らないこと
 * - bootJar を作るプロジェクトがすべてこの検査を適用している
 * - 3 つの実行アプリの DataSource 設定 (application-datasource.yaml) が同じ内容である
 *
 * 新しいモジュールや依存を加えるときは、module-boundaries.md §2 と一緒に ModuleGraphRules の表を更新する。
 */
object ModuleGraphRules {
    // 中間のプロジェクト ":manager"・":processor" はディレクトリだけで、ソースを持たない
    val projects =
        setOf(
            ":",
            ":storage",
            ":database-schema",
            ":catalog",
            ":diagnostics",
            ":library",
            ":processing",
            ":console-common",
            ":manager",
            ":manager:api",
            ":manager:console",
            ":processor",
            ":processor:console",
        )
    val removed = setOf(":core", ":manager:infrastructure", ":processor:infrastructure")

    // 本番の設定 (implementation、api、runtimeOnly など) で宣言してよいプロジェクト依存
    val allowed: Map<String, Set<String>> =
        mapOf(
            ":storage" to emptySet(),
            ":database-schema" to emptySet(),
            ":catalog" to setOf(":storage"),
            ":diagnostics" to setOf(":storage"),
            ":library" to setOf(":catalog", ":storage"),
            ":processing" to setOf(":catalog", ":storage", ":diagnostics"),
            ":console-common" to emptySet(),
            ":manager:api" to setOf(":storage", ":catalog", ":library", ":diagnostics"),
            ":manager:console" to setOf(":storage", ":catalog", ":library", ":diagnostics", ":console-common"),
            ":processor:console" to setOf(":storage", ":catalog", ":diagnostics", ":processing", ":console-common"),
        )
    private const val TEST_FIXTURES = ":database-schema"

    // 実行アプリの解決後の実行時クラスパスに入ってよいプロジェクト
    val runtime: Map<String, Set<String>> =
        mapOf(
            ":manager:api" to setOf(":storage", ":catalog", ":library", ":diagnostics"),
            ":manager:console" to setOf(":storage", ":catalog", ":library", ":diagnostics", ":console-common"),
            ":processor:console" to setOf(":storage", ":catalog", ":diagnostics", ":processing", ":console-common"),
        )

    // 実行時クラスパスに入ってはならない外部モジュール ("group:name")。処理専用ツールは processor だけが使う
    private val processingTools = setOf("me.pinfort:tsselect")
    val forbiddenModules: Map<String, Set<String>> =
        mapOf(
            ":manager:api" to processingTools,
            ":manager:console" to processingTools,
            ":processor:console" to emptySet(),
        )

    /**
     * @param projects ビルドに含まれるプロジェクトのパス
     * @param declared "<project> <configuration> -> <依存先>" の形 (自分自身への依存を除く)
     * @param resolved このアプリの "<classpath> project <path>" と "<classpath> module <group:name>" の形
     * @param unguardedApps bootJar を作るのにこの検査を適用していないプロジェクト
     * @param datasourceConfigs 実行アプリごとの application-datasource.yaml の内容 (ないときは null)
     */
    fun violations(
        app: String,
        projects: Collection<String>,
        declared: List<String>,
        resolved: List<String>,
        unguardedApps: List<String>,
        datasourceConfigs: Map<String, String?>,
    ): List<String> {
        val violations = mutableListOf<String>()
        projects.filter { it in removed }.forEach { violations += "the removed project $it is part of the build again" }
        (projects - this.projects - removed).forEach { violations += "$it is not in ModuleGraphRules; add it with its allowed edges" }
        (this.projects - projects.toSet()).forEach { violations += "$it is missing from the build; update ModuleGraphRules" }
        declared.forEach { edge ->
            val from = edge.substringBefore(' ')
            val configuration = edge.substringAfter(' ').substringBefore(" -> ")
            val to = edge.substringAfter(" -> ")
            val permitted = allowed[from].orEmpty() + if (configuration.startsWith("test")) setOf(TEST_FIXTURES) else emptySet()
            if (to !in permitted) violations += "forbidden project dependency: $edge"
        }
        val allowedRuntime = runtime[app]
        if (allowedRuntime == null) {
            violations += "$app applies module-graph.gradle.kts but has no runtime rule in ModuleGraphRules"
        } else {
            val projectsOnClasspath = resolved.filter { it.contains(" project ") }
            if (projectsOnClasspath.isEmpty()) violations += "$app resolved no project dependencies; the runtime graph was not inspected"
            projectsOnClasspath.filterNot { it.substringAfter(" project ") in allowedRuntime }.forEach {
                violations += "$app runtime classpath resolves a forbidden project: $it"
            }
            resolved.filter { it.substringAfter(" module ", "") in forbiddenModules[app].orEmpty() }.forEach {
                violations += "$app runtime classpath resolves a processing-only tool: $it"
            }
        }
        unguardedApps.forEach { violations += "$it builds an executable jar without applying module-graph.gradle.kts" }
        datasourceConfigs.filterValues { it == null }.keys.forEach {
            violations += "$it has no src/main/resources/application-datasource.yaml"
        }
        if (datasourceConfigs.values.filterNotNull().distinct().size > 1) {
            violations +=
                "the applications' application-datasource.yaml differ: ${datasourceConfigs.keys.joinToString()} must stay identical"
        }
        return violations
    }
}

/**
 * ルールが違反を見逃さないことを、違反を含むフィクスチャで確かめる (ルールの変更で検査が素通りになるのを防ぐ)。
 * 現在の構成 (許可された辺、DB テストのテストフィクスチャ、許可された実行時の集合) が違反にならないことも確かめる。
 */
val checkModuleGraphRules by tasks.registering {
    group = "verification"
    description = "Fails when the module graph rules stop rejecting known violations."
    doLast {
        val datasource = "spring:\n  datasource:\n    url: x\n"
        val sameDatasource = ModuleGraphRules.runtime.keys.associateWith { datasource }
        val currentEdges =
            ModuleGraphRules.allowed.flatMap { (from, targets) -> targets.map { "$from implementation -> $it" } } +
                listOf(":catalog testImplementation -> :database-schema", ":manager:api testImplementation -> :database-schema")
        val clean =
            ModuleGraphRules.runtime.keys.flatMap { app ->
                ModuleGraphRules.violations(
                    app,
                    ModuleGraphRules.projects,
                    currentEdges,
                    ModuleGraphRules.runtime.getValue(app).map { "runtimeClasspath project $it" } +
                        "runtimeClasspath module org.mariadb.jdbc:mariadb-java-client",
                    emptyList(),
                    sameDatasource,
                )
            }
        val processorTool = "runtimeClasspath module me.pinfort:tsselect"
        val processorWithTool =
            ModuleGraphRules.violations(
                ":processor:console",
                ModuleGraphRules.projects,
                emptyList(),
                listOf("runtimeClasspath project :processing", processorTool),
                emptyList(),
                sameDatasource,
            )
        val brokenEdges =
            listOf(
                ":manager:api implementation -> :processing",
                ":manager:console runtimeOnly -> :processing",
                ":processor:console implementation -> :library",
                ":library implementation -> :core",
                ":processing api -> :manager:infrastructure",
                ":catalog implementation -> :database-schema",
                ":processing testImplementation -> :library",
            )
        val managerRuntime =
            listOf(
                "runtimeClasspath project :processing",
                "productionRuntimeClasspath project :core",
                "productionRuntimeClasspath module me.pinfort:tsselect",
            )
        val broken =
            ModuleGraphRules.violations(
                ":manager:api",
                ModuleGraphRules.projects - ":console-common" + ":core" + ":processor:infrastructure" + ":shared",
                brokenEdges,
                managerRuntime,
                listOf(":manager:new-app"),
                mapOf(":manager:api" to datasource, ":manager:console" to "$datasource    username: y\n", ":processor:console" to null),
            )
        val processorWithLibrary =
            ModuleGraphRules.violations(
                ":processor:console",
                ModuleGraphRules.projects,
                emptyList(),
                listOf("runtimeClasspath project :processing", "runtimeClasspath project :library"),
                emptyList(),
                sameDatasource,
            )
        fun withoutRuntime(app: String) =
            ModuleGraphRules.violations(app, ModuleGraphRules.projects, emptyList(), emptyList(), emptyList(), sameDatasource)
        val uninspected = withoutRuntime(":manager:console")
        val unknownApp = withoutRuntime(":other")

        val problems = mutableListOf<String>()
        if (clean.isNotEmpty()) problems += "the current graph was reported: $clean"
        if (processorWithTool.isNotEmpty()) problems += "the processor's own tool was reported: $processorWithTool"
        (
            brokenEdges.map { "forbidden project dependency: $it" } +
                listOf(
                    "the removed project :core",
                    "the removed project :processor:infrastructure",
                    ":shared is not in ModuleGraphRules",
                    ":console-common is missing from the build",
                    ":manager:new-app builds an executable jar",
                    ":processor:console has no src/main/resources/application-datasource.yaml",
                    "application-datasource.yaml differ",
                )
        ).forEach { expected -> if (broken.none { it.contains(expected) }) problems += "\"$expected\" was not reported" }
        managerRuntime.filterNot { entry -> broken.any { it.endsWith(entry) } }.forEach { problems += "$it was not reported" }
        if (processorWithLibrary.none { it.endsWith("runtimeClasspath project :library") }) {
            problems += "library on the processor was not reported"
        }
        if (uninspected.none { it.contains("was not inspected") }) problems += "an empty runtime graph was not reported"
        if (unknownApp.none { it.contains("has no runtime rule") }) problems += "an application without a runtime rule was not reported"
        if (problems.isNotEmpty()) {
            throw GradleException("Module graph rules are broken:\n" + (problems + "reported: $broken").joinToString("\n") { "  $it" })
        }
    }
}

val checkModuleGraph by tasks.registering {
    group = "verification"
    description = "Fails when the module graph, this application's runtime classpath or the applications' DataSource settings drift."
    dependsOn(checkModuleGraphRules)
    val appPath = project.path
    val projectPaths = rootProject.allprojects.map { it.path }
    val declared =
        provider {
            rootProject.allprojects.flatMap { other ->
                other.configurations.flatMap { configuration ->
                    configuration.dependencies
                        .withType<ProjectDependency>()
                        // java-test-fixtures は自分自身への依存を追加する
                        .filterNot { it.path == other.path }
                        .map { "${other.path} ${configuration.name} -> ${it.path}" }
                }
            }
        }
    // productionRuntimeClasspath (Spring Boot が bootJar に入れるもの) も runtimeClasspath とは別に解決されるので両方見る
    val classpaths = listOf("runtimeClasspath", "productionRuntimeClasspath").mapNotNull { configurations.findByName(it) }
    val resolved =
        classpaths.map { configuration ->
            configuration.incoming.resolutionResult.rootComponent.map { root ->
                val entries = sortedSetOf<String>()
                val visited = mutableSetOf(root.id)
                val queue = kotlin.collections.ArrayDeque(listOf(root))
                while (queue.isNotEmpty()) {
                    val component = queue.removeFirst()
                    component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
                        val selected = dependency.selected
                        when (val id = selected.id) {
                            is ProjectComponentIdentifier ->
                                if (id.projectPath != appPath) entries += "${configuration.name} project ${id.projectPath}"
                            is ModuleComponentIdentifier -> entries += "${configuration.name} module ${id.group}:${id.module}"
                        }
                        if (visited.add(selected.id)) queue += selected
                    }
                }
                entries.toList()
            }
        }
    // 実行アプリ (bootJar が有効) のうち、この検査を適用していないもの
    val unguardedApps =
        provider {
            rootProject.allprojects
                .filter { other -> other.tasks.findByName("bootJar")?.enabled == true }
                .filter { other -> other.tasks.findByName("checkModuleGraph") == null }
                .map { it.path }
        }
    val datasourceFiles =
        ModuleGraphRules.runtime.keys.associateWith { path ->
            rootDir.resolve(path.removePrefix(":").replace(':', '/')).resolve("src/main/resources/application-datasource.yaml")
        }
    inputs.property("projects", projectPaths)
    inputs.property("declared", declared)
    resolved.forEachIndexed { index, entries -> inputs.property("resolved.$index", entries) }
    inputs.property("unguardedApps", unguardedApps)
    inputs.files(datasourceFiles.values.filter { it.isFile })
    doLast {
        val violations =
            ModuleGraphRules.violations(
                appPath,
                projectPaths,
                declared.get(),
                resolved.flatMap { it.get() },
                unguardedApps.get(),
                datasourceFiles.mapValues { (_, file) -> file.takeIf { it.isFile }?.readText()?.replace("\r\n", "\n") },
            )
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module graph violations (module-boundaries.md §2, §11.7):\n" + violations.joinToString("\n") { "  $it" },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkModuleGraph)
}
