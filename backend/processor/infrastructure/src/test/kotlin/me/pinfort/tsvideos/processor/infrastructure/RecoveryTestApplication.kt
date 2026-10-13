package me.pinfort.tsvideos.processor.infrastructure

import org.mybatis.spring.annotation.MapperScan
import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
@MapperScan("me.pinfort.tsvideos.core.external.database.mapper")
class RecoveryTestApplication
