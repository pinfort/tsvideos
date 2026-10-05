package me.pinfort.tsvideos.processor.console.component

import org.springframework.stereotype.Component

@Component
class UserQuestionComponent {
    private val yesResponses =
        listOf(
            "y",
            "Y",
            "yes",
            "Yes",
        )

    fun askDefaultFalse(question: String): Boolean {
        println(question)
        print("[y/N] >> ")
        val answer = readlnOrNull()
        return !answer.isNullOrEmpty() && yesResponses.contains(answer)
    }
}
