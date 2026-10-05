package me.pinfort.tsvideos.console.common.component

import io.kotest.core.spec.style.ExpectSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkStatic

class UserQuestionComponentTest :
    ExpectSpec({
        val userQuestionComponent = UserQuestionComponent()

        beforeEach {
            mockkStatic(::readlnOrNull)
        }

        context("askDefaultFalse") {
            expect("y returns true") {
                every { readlnOrNull() } returns "y"
                userQuestionComponent.askDefaultFalse("question") shouldBe true
            }

            expect("Y returns true") {
                every { readlnOrNull() } returns "Y"
                userQuestionComponent.askDefaultFalse("question") shouldBe true
            }

            expect("n returns false") {
                every { readlnOrNull() } returns "n"
                userQuestionComponent.askDefaultFalse("question") shouldBe false
            }

            expect("N returns false") {
                every { readlnOrNull() } returns "N"
                userQuestionComponent.askDefaultFalse("question") shouldBe false
            }

            expect("null returns false") {
                every { readlnOrNull() } returns null
                userQuestionComponent.askDefaultFalse("question") shouldBe false
            }

            expect("foo returns false") {
                every { readlnOrNull() } returns "foo"
                userQuestionComponent.askDefaultFalse("question") shouldBe false
            }
        }

        context("askDefaultTrue") {
            expect("y returns true") {
                every { readlnOrNull() } returns "y"
                userQuestionComponent.askDefaultTrue("question") shouldBe true
            }

            expect("Y returns true") {
                every { readlnOrNull() } returns "Y"
                userQuestionComponent.askDefaultTrue("question") shouldBe true
            }

            expect("n returns false") {
                every { readlnOrNull() } returns "n"
                userQuestionComponent.askDefaultTrue("question") shouldBe false
            }

            expect("N returns false") {
                every { readlnOrNull() } returns "N"
                userQuestionComponent.askDefaultTrue("question") shouldBe false
            }

            expect("null returns true") {
                every { readlnOrNull() } returns null
                userQuestionComponent.askDefaultTrue("question") shouldBe true
            }

            expect("foo returns true") {
                every { readlnOrNull() } returns "foo"
                userQuestionComponent.askDefaultTrue("question") shouldBe true
            }
        }
    })
