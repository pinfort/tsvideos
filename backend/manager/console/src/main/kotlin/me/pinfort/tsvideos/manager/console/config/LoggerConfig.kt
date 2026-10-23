package me.pinfort.tsvideos.manager.console.config

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InjectionPoint
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Scope

/**
 * catalog・library などのモジュールが注入を要求する slf4j `Logger` を、このアプリが組み立てる (#139 で core から移した)。
 * 実行アプリごとに 1 つ持ち、モジュールは Bean 定義に依存しない。
 */
@Configuration
class LoggerConfig {
    /**
     * InjectionPoint はビーンの生成時にしか解決されないため、シングルトンにすると
     * 最初に生成されたクラス向けの Logger が全体で使い回され、ログのクラス名がすべて
     * そのクラスになってしまう。注入先ごとに生成するために prototype にする。
     */
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    fun logger(injectionPoint: InjectionPoint): Logger =
        LoggerFactory.getLogger(
            injectionPoint.methodParameter?.containingClass
                ?: injectionPoint.field?.declaringClass,
        )
}
