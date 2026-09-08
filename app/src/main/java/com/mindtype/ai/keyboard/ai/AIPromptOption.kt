
package com.mindtype.ai.keyboard.ai

data class AIPromptOption(
    val title: String,
    val icon: String,
    val promptPrefix: String
)

object AIPrompts {
    val defaultList = listOf(
        AIPromptOption("Fix Grammar", "✨", "Fix all spelling and grammar mistakes in this text, keeping the original tone:"),
        AIPromptOption("Rephrase", "📝", "Rephrase and improve the flow of this text:"),
        AIPromptOption("Make Professional", "💼", "Rewrite this text to make it sound professional and polished:"),
        AIPromptOption("Summarize", "⚡", "Summarize this text concisely:"),
        AIPromptOption("Translate to EN", "文A", "Translate the following text into clear, natural English:"),
        AIPromptOption("Reply Ideas", "💡", "Generate 3 short, friendly reply options for this message:")
    )
}