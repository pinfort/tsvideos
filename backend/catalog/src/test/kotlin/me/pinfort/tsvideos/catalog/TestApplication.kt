package me.pinfort.tsvideos.catalog

import org.springframework.boot.autoconfigure.SpringBootApplication

// Scans storage too, like the applications: catalog's coordinated delete/move needs the storage beans.
// MyBatis's slice tests register catalog's @Mapper interfaces from this package down.
@SpringBootApplication(scanBasePackages = ["me.pinfort.tsvideos.storage", "me.pinfort.tsvideos.catalog"])
class TestApplication
