package me.pinfort.tsvideos.core

import me.pinfort.tsvideos.catalog.api.CatalogPersistence
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication

// Scans storage, catalog and diagnostics too, like the applications: core's NAS callers need the storage beans,
// its library and processing code calls catalog's operations, and ProcessingPreflightCommand needs diagnostics'
// EnvironmentDiagnosticCommand.
// Like the applications, the mappers of core and catalog are listed explicitly; catalog's live outside this package,
// so MyBatis's own scan of the auto-configuration package would miss them.
@MapperScan(
    basePackages = [
        "me.pinfort.tsvideos.core.external.database.mapper",
        CatalogPersistence.MAPPER_PACKAGE,
    ],
)
@SpringBootApplication(
    scanBasePackages = [
        "me.pinfort.tsvideos.storage",
        "me.pinfort.tsvideos.catalog",
        "me.pinfort.tsvideos.diagnostics",
        "me.pinfort.tsvideos.core",
    ],
)
class TestApplication
