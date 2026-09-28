package com.github.shikigami.model

data class BotConfig(
    val adminChatId: Long,
    val adminMessageRelayText: String,
    val allowedChatIds: List<Long>,
    val commandStartRelayText: String,
    val errorEmptyRelayText: String,
    val errorFileRelayText: String,
    val errorMessageRelayText: String,
    val errorUnknownRelayText: String,
    val mmjPrompt: String,
    val placeHolderRelayText: String,
    val proxy: ProxyConfig?,
    val rateLimitRelayText: String,
    val token: String,
    val username: String,
)
