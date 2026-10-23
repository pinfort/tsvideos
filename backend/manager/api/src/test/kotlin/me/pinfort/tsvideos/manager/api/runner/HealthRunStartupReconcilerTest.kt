package me.pinfort.tsvideos.manager.api.runner

import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.library.api.HealthVerificationStore
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.transaction.CannotCreateTransactionException

class HealthRunStartupReconcilerTest :
    DescribeSpec({
        it("releases abandoned runs on startup") {
            val store = mockk<HealthVerificationStore>()
            every { store.failAbandoned() } returns 1
            HealthRunStartupReconciler(store).run(DefaultApplicationArguments())
            verify(exactly = 1) { store.failAbandoned() }
        }
        it("does not fail startup when the database is unreachable") {
            val store = mockk<HealthVerificationStore>()
            every { store.failAbandoned() } throws CannotCreateTransactionException("db down")
            HealthRunStartupReconciler(store).run(DefaultApplicationArguments())
        }
    })
