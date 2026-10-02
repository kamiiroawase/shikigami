package com.github.shikigami

import eu.vendeli.tgbot.TelegramBot
import kotlin.test.Test

class TelegramBotStartupTest {
    @Test
    fun telegramBotInitializesWithGeneratedContextLoader() {
        TelegramBot(token = "123456:TEST-token-without-network-calls")
    }
}
