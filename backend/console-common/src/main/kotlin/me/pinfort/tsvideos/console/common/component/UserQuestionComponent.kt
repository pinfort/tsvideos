package me.pinfort.tsvideos.console.common.component

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
    private val noResponses =
        listOf(
            "n",
            "N",
            "no",
            "No",
        )

    fun askDefaultFalse(question: String): Boolean {
        println(question)
        print("[y/N] >> ")
        val answer = readlnOrNull()
        return !answer.isNullOrEmpty() && yesResponses.contains(answer)
    }

    fun askDefaultTrue(question: String): Boolean {
        println(question)
        print("[Y/n] >> ")
        val answer = readlnOrNull()
        return answer.isNullOrEmpty() || !noResponses.contains(answer)
    }
}
