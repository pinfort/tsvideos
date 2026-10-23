package me.pinfort.tsvideos.library.api

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import me.pinfort.tsvideos.library.domain.VerificationReport
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DuplicateKeyException

class LibraryHealthScanCommandTest :
    ExpectSpec({
        lateinit var store: HealthVerificationStore
        lateinit var verifier: LibraryVerificationCommand
        lateinit var command: LibraryHealthScanCommand

        beforeTest {
            store = mockk()
            verifier = mockk()
            command = LibraryHealthScanCommand(store, verifier)
            every { store.start() } returns 7
            every { store.fail(7) } just Runs
        }
        expect("an existing active claim maps to HealthScanAlreadyRunning without verifying") {
            every { store.start() } throws DuplicateKeyException("uq_health_active")
            shouldThrow<HealthScanAlreadyRunning> { command.scan() }
            verify(exactly = 0) { verifier.verify() }
        }
        expect("a successful report completes the run") {
            val report = VerificationReport(emptyList())
            every { verifier.verify() } returns report
            every { store.complete(7, report) } just Runs
            command.scan()
            verify(exactly = 0) { store.fail(any()) }
        }
        expect("a null report fails the run") {
            every { verifier.verify() } returns null
            command.scan()
            verify(exactly = 1) { store.fail(7) }
            verify(exactly = 0) { store.complete(any(), any()) }
        }
        expect("a verifier exception fails the run and is not propagated") {
            every { verifier.verify() } throws IllegalStateException("password=secret")
            command.scan()
            verify(exactly = 1) { store.fail(7) }
        }
        expect("an interrupt fails the run and restores the interrupt flag") {
            every { verifier.verify() } throws InterruptedException()
            try {
                command.scan()
                Thread.currentThread().isInterrupted shouldBe true
            } finally {
                Thread.interrupted()
            }
            verify(exactly = 1) { store.fail(7) }
        }
        expect("an Error fails the run and is rethrown") {
            every { verifier.verify() } throws StackOverflowError()
            shouldThrow<StackOverflowError> { command.scan() }
            verify(exactly = 1) { store.fail(7) }
        }
        expect("a failing fail() is retried once") {
            every { verifier.verify() } throws IllegalStateException("nas")
            every { store.fail(7) } throws DataAccessResourceFailureException("db") andThen Unit
            command.scan()
            verify(exactly = 2) { store.fail(7) }
        }
        expect("a persistently failing fail() does not escape scan") {
            every { verifier.verify() } throws IllegalStateException("nas")
            every { store.fail(7) } throws DataAccessResourceFailureException("db")
            command.scan()
            verify(exactly = 2) { store.fail(7) }
        }
    })
