package me.pinfort.tsvideos.processing

import me.pinfort.tsvideos.catalog.api.CatalogPersistence
import me.pinfort.tsvideos.processing.api.ProcessingPersistence
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication

// Scans storage, catalog and diagnostics too, like the applications: processing's components call catalog's operations
// and storage's NAS contracts, and ProcessingPreflightCommand needs diagnostics' EnvironmentDiagnosticCommand.
// Like the applications, the mappers of catalog and processing are listed explicitly; catalog's live outside this
// package, so MyBatis's own scan of the auto-configuration package would miss them.
@MapperScan(basePackages = [CatalogPersistence.MAPPER_PACKAGE, ProcessingPersistence.MAPPER_PACKAGE])
@SpringBootApplication(
    scanBasePackages = [
        "me.pinfort.tsvideos.storage",
        "me.pinfort.tsvideos.catalog",
        "me.pinfort.tsvideos.diagnostics",
        "me.pinfort.tsvideos.processing",
    ],
)
class TestApplication
