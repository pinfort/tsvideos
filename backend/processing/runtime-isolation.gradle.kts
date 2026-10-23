import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/**
 * manager の実行アプリの実際の実行時クラスパスに processing (TsSplitter/Amatsukaze/tsselect などの処理専用ツールと
 * processor-* 設定) が入らないことの検査 (module-boundaries.md §2、#165)。manager:api と manager:console の
 * build.gradle.kts が plugins ブロックの後で `apply(from = rootDir.resolve("processing/runtime-isolation.gradle.kts"))`
 * として適用する。database-schema の runtime-isolation.gradle.kts と同じく、宣言された依存ではなく解決後の依存グラフと
 * 成果物を見るので、推移的な依存で入った場合も検出する。許可されたプロジェクトの全体像と処理専用ツール (me.pinfort:tsselect) の
 * 不在は module-graph.gradle.kts の checkModuleGraph が検査する (#139)。
 */
val processingPath = ":processing"
val processingDir = rootDir.resolve("processing")

val checkProcessingRuntimeIsolation by tasks.registering {
    group = "verification"
    description = "Fails when processing is on this manager application's resolved runtime classpath."
    // productionRuntimeClasspath (Spring Boot が bootJar に入れるもの) も runtimeClasspath とは別に解決されるので両方見る
    val classpaths = listOf("runtimeClasspath", "productionRuntimeClasspath").mapNotNull { configurations.findByName(it) }
    // "<到達したプロジェクト> <- <依存元>" の一覧
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
                            if (id is ProjectComponentIdentifier) edges += "${id.projectPath} <- $from"
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
            edges.filter { it.startsWith("$processingPath ") }.forEach { violations += "$name resolves $it" }
        }
        artifacts.forEach { (name, resolved) ->
            resolved
                .get()
                .filter { (component, file) -> component == processingPath || file.startsWith(processingDir) }
                .forEach { (component, file) -> violations += "$name contains $file ($component)" }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "processing must not be on the runtime classpath of $appPath (module-boundaries.md §2):\n" +
                    violations.joinToString("\n") { "  $it" },
            )
        }
    }
}

tasks.named("check") {
    dependsOn(checkProcessingRuntimeIsolation)
}
