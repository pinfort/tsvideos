package me.pinfort.tsvideos.library

import me.pinfort.tsvideos.catalog.api.CatalogPersistence
import me.pinfort.tsvideos.library.api.LibraryPersistence
import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication

// Scans storage and catalog too, like the applications: library calls catalog's operations, which need the storage beans.
// Like the applications, the mappers of catalog and library are listed explicitly; catalog's live outside this package, so
// MyBatis's own scan of the auto-configuration package would miss them.
@MapperScan(basePackages = [CatalogPersistence.MAPPER_PACKAGE, LibraryPersistence.MAPPER_PACKAGE])
@SpringBootApplication(scanBasePackages = ["me.pinfort.tsvideos.storage", "me.pinfort.tsvideos.catalog", "me.pinfort.tsvideos.library"])
class TestApplication
