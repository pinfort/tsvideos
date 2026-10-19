package me.pinfort.tsvideos.manager.api

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import me.pinfort.tsvideos.manager.api.stream.ResponseResourcesFilter
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext

// Regression guard for the class of bug fixed by MyBatisConfig (a bean-wiring mistake,
// e.g. a missing @MapperScan, throws during context startup and fails this test).
// manager:api isn't otherwise built/run by CI (see jar.backend.yaml), so nothing else
// here would catch that.
@SpringBootTest
class ApiApplicationTest : FunSpec() {
    override val extensions = listOf(SpringExtension())

    @Autowired
    lateinit var context: ApplicationContext

    init {
        test("application context loads") {}

        // Video streaming closes its NAS handle through this filter; without it every stream request fails.
        test("the response resource filter is assembled as a servlet filter bean") {
            context.getBeansOfType(ResponseResourcesFilter::class.java).size shouldBe 1
        }
    }
}
