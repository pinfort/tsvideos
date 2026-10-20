package me.pinfort.tsvideos.core

import org.springframework.boot.autoconfigure.SpringBootApplication

// Scans storage too, like the applications: core's NAS callers need the storage beans.
@SpringBootApplication(scanBasePackages = ["me.pinfort.tsvideos.storage", "me.pinfort.tsvideos.core"])
class TestApplication
