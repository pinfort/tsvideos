import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/**
 * 実行アプリの実際の実行時クラスパスに database-schema (migration とテストフィクスチャ) が入らないことの検査
 * (module-boundaries.md §11.6、§11.7)。実行アプリ (bootJar を作るプロジェクト) の build.gradle.kts が plugins ブロックの後で
 * `apply(from = rootDir.resolve("database-schema/runtime-isolation.gradle.kts"))` として適用する。適用漏れは
 * database-schema の checkDatabaseSchemaBoundaries が検出する。
 *
 * 宣言された依存ではなく解決後の依存グラフと成果物を見るので、設定の継承 (extendsFrom) や推移的な依存で入った場合も検出する。
 * 依存グラフはタスクの入力なので、依存が変わればタスクは再実行される。
 */
val databaseSchemaPath = ":database-schema"
val databaseSchemaDir = rootDir.resolve("database-schema")

val checkDatabaseSchemaRuntimeIsolation by tasks.registering {
    group = "verification"
    description = "Fails when database-schema or its test fixtures are on this application's resolved runtime classpath."
    // productionRuntimeClasspath (Spring Boot が bootJar に入れるもの) も runtimeClasspath とは別に解決されるので両方見る
    val classpaths = listOf("runtimeClasspath", "productionRuntimeClasspath").mapNotNull { configurations.findByName(it) }
    // "<到達したプロジェクト> [<ケイパビリティ>] <- <依存元>" の一覧
    val graphs =
        classpaths.associate { configuration ->
            configuration.name to
                configuration.incoming.resolutionResult.rootComponent.map { root ->
                    val edges = sortedSetOf<String>()
                    val visited = mutableSetOf(root.id)
                    val queue = kotlin.collections.ArrayDeque(listOf(root))
                    while (queue.isNotEmpty()) {
                        val component = queue.removeFirst()
                        val from = (component.id as? ProjectComponentIdentifier)?.projectPath ?: component.id.displayName
                        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { dependency ->
                            val selected = dependency.selected
                            val id = selected.id
                            if (id is ProjectComponentIdentifier) {
                                val capabilities = dependency.resolvedVariant.capabilities.joinToString { "${it.group}:${it.name}" }
                                edges += "${id.projectPath} [$capabilities] <- $from"
                            }
                            if (visited.add(id)) queue += selected
                        }
                    }
                    edges.toList()
                }
        }
    val artifacts =
        classpaths.associate { configuration ->
            configuration.name to
                configuration.incoming.artifacts.resolvedArtifacts.map { resolved ->
                    resolved.map { artifact ->
                        val id = artifact.id.componentIdentifier
                        ((id as? ProjectComponentIdentifier)?.projectPath ?: id.displayName) to artifact.file
                    }
                }
        }
    graphs.forEach { (name, graph) -> inputs.property("graph.$name", graph) }
    inputs.files(classpaths)
    val appPath = project.path
    doLast {
        val violations = mutableListOf<String>()
        graphs.forEach { (name, graph) ->
            val edges = graph.get()
            // 依存グラフを実際に見ていることの確認 (実行アプリは必ず他のプロジェクトに依存する)
            if (edges.isEmpty()) violations += "$name resolved no project dependencies; the runtime graph was not inspected"
            edges.filter { it.startsWith("$databaseSchemaPath ") }.forEach { violations += "$name resolves $it" }
        }
        artifacts.forEach { (name, resolved) ->
            resolved
                .get()
                .filter { (component, file) -> component == databaseSchemaPath || file.startsWith(databaseSchemaDir) }
                .forEach { (component, file) -> violations += "$name contains $file ($component)" }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "database-schema must not be on the runtime classpath of $appPath;" +
                    "use testImplementation(testFixtures(project(\"$databaseSchemaPath\"))) in tests only:\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkDatabaseSchemaRuntimeIsolation)
}
