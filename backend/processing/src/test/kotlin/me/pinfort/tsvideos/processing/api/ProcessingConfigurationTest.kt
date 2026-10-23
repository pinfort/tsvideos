package me.pinfort.tsvideos.processing.api

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.processing.TestLoggerConfig
import me.pinfort.tsvideos.processing.api.config.ProcessingRetryConfigurationProperties
import me.pinfort.tsvideos.processing.api.config.ProcessorToolConfigurationProperties
import me.pinfort.tsvideos.processing.api.config.RecordingQualityConfigurationProperties
import me.pinfort.tsvideos.processing.api.shell.ShellClient
import me.pinfort.tsvideos.processing.api.tool.AmatsukazeAddTaskClient
import me.pinfort.tsvideos.processing.api.tool.TsSplitterClient
import me.pinfort.tsvideos.processing.domain.QualityAction
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import

/** The shell and tool clients, with the properties the applications register by scanning processing's config package. */
@ComponentScan(basePackageClasses = [ShellClient::class, TsSplitterClient::class])
@Import(TestLoggerConfig::class, ProcessingCancellation::class, RunningPlatformComponent::class)
@EnableConfigurationProperties(
    ProcessorToolConfigurationProperties::class,
    ProcessingRetryConfigurationProperties::class,
    RecordingQualityConfigurationProperties::class,
)
private class ToolContext

/**
 * processing's packaged settings (application-processing.yaml, moved from core's application-core.yaml in #164): the
 * `processing` profile supplies the `processor-tool.*` and `processor-preflight.*` keys with the same defaults and
 * environment variables, and the tool clients assemble from it without any database or application wiring.
 */
class ProcessingConfigurationTest :
    ExpectSpec({
        fun env(name: String) = System.getenv(name)

        fun runner() =
            ApplicationContextRunner()
                .withInitializer(ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ToolContext::class.java)

        expect("the processing profile supplies the tool and preflight settings with their documented defaults") {
            runner().withPropertyValues("spring.profiles.active=processing").run { context ->
                context.startupFailure shouldBe null
                val tools = context.getBean(ProcessorToolConfigurationProperties::class.java)
                tools.tsSplitterPath shouldBe (env("TS_SPLITTER_PATH") ?: """C:\tools\TsSplitter\TsSplitter.exe""")
                tools.amatsukazeAddTaskPath shouldBe
                    (env("AMATSUKAZE_ADD_TASK_PATH") ?: """C:\tools\Amatsukaze\exe_files\AmatsukazeAddTask.exe""")
                tools.ffprobePath shouldBe (env("FFPROBE_PATH") ?: "ffprobe")
                tools.amatsukaze.host shouldBe (env("AMATSUKAZE_HOST") ?: "localhost")
                tools.amatsukaze.port shouldBe (env("AMATSUKAZE_PORT")?.toInt() ?: 32768)
                tools.amatsukaze.defaultProfile shouldBe (env("AMATSUKAZE_DEFAULT_PROFILE") ?: "30fps_light")
                tools.amatsukaze.atxDivProfile shouldBe (env("AMATSUKAZE_ATX_DIV_PROFILE") ?: "30fps_light_atx_div")
                context.environment.getProperty("processor-preflight.space-multiplier") shouldBe
                    (env("PROCESSOR_PREFLIGHT_SPACE_MULTIPLIER") ?: "3")
                context.getBean(TsSplitterClient::class.java).shouldNotBeNull()
                context.getBean(AmatsukazeAddTaskClient::class.java).shouldNotBeNull()
                // processor-retry and processor-quality keep their in-code defaults; the profile does not set them.
                context.getBean(ProcessingRetryConfigurationProperties::class.java) shouldBe ProcessingRetryConfigurationProperties()
                context.getBean(RecordingQualityConfigurationProperties::class.java).unavailableAction shouldBe QualityAction.WARN
            }
        }

        expect("without the processing profile the tool settings are missing and the tool clients do not assemble") {
            runner().run { context ->
                context.startupFailure.shouldNotBeNull()
            }
        }
    })
