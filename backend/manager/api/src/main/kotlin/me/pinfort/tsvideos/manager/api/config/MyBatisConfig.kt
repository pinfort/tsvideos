package me.pinfort.tsvideos.manager.api.config

import me.pinfort.tsvideos.catalog.api.CatalogPersistence
import me.pinfort.tsvideos.library.api.LibraryPersistence
import me.pinfort.tsvideos.processing.api.ProcessingPersistence
import org.mybatis.spring.annotation.MapperScan
import org.springframework.context.annotation.Configuration

// Kept off ApiApplication itself: @MapperScan on the primary @SpringBootConfiguration class
// still registers mapper beans inside @WebMvcTest slices (it runs via a
// BeanDefinitionRegistryPostProcessor, which slice-test type filters don't exclude), and those
// mapper beans then fail because the slice never wires up a real SqlSessionFactory. A plain
// @Configuration class is excluded by @WebMvcTest's default filtering, so it only takes effect
// when the full application context loads.
// Every module's mappers share the application's single SqlSessionFactory and transaction manager.
// processing's mappers are registered only while core's VerifiedUploadCommand pulls processing in (#164, removed in #165).
@Configuration
@MapperScan(
    basePackages = [
        "me.pinfort.tsvideos.core.external.database.mapper",
        CatalogPersistence.MAPPER_PACKAGE,
        LibraryPersistence.MAPPER_PACKAGE,
        ProcessingPersistence.MAPPER_PACKAGE,
    ],
)
class MyBatisConfig
