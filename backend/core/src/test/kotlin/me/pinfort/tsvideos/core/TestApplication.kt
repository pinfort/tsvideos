package me.pinfort.tsvideos.core

import me.pinfort.tsvideos.catalog.api.CatalogPersistence
import me.pinfort.tsvideos.processing.api.ProcessingPersistence
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication

// Scans storage, catalog, diagnostics and processing too, like the applications: core's VerifiedUploadCommand needs the
// storage beans, registers through catalog's operations and uses processing's retry and cancellation (the transitional
// core -> processing dependency until #165 moves the upload to processing).
// Like the applications, the mappers of core, catalog and processing are listed explicitly; catalog's and processing's
// live outside this package, so MyBatis's own scan of the auto-configuration package would miss them.
@MapperScan(
    basePackages = [
        "me.pinfort.tsvideos.core.external.database.mapper",
        CatalogPersistence.MAPPER_PACKAGE,
        ProcessingPersistence.MAPPER_PACKAGE,
    ],
)
@SpringBootApplication(
    scanBasePackages = [
        "me.pinfort.tsvideos.storage",
        "me.pinfort.tsvideos.catalog",
        "me.pinfort.tsvideos.diagnostics",
        "me.pinfort.tsvideos.processing",
        "me.pinfort.tsvideos.core",
    ],
)
class TestApplication
