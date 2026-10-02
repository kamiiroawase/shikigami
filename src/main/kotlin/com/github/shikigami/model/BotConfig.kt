package com.github.shikigami.model

data class BotConfig(
    val adminChatId: Long,
    val adminMessageText: String,
    val allowedChatIds: List<Long>,
    val commandStartText: String,
    val errorEmptyText: String,
    val errorFileText: String,
    val errorMessageText: String,
    val errorUnknownText: String,
    val mmjPrompt: String,
    val placeholderText: String,
    val proxy: ProxyConfig?,
    val rateLimitText: String,
    val token: String,
    val username: String,
)
