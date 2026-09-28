package com.github.shikigami

import com.aallam.openai.api.http.Timeout
import com.aallam.openai.api.logging.LogLevel
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.LoggingConfig
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.OpenAIConfig
import com.aallam.openai.client.OpenAIHost
import com.github.shikigami.model.BotConfig
import com.github.shikigami.model.ProxyConfig
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlin.time.Duration.Companion.seconds

object Config {
    private val props =
        Properties().apply {
            FileInputStream("config.properties").use { input ->
                InputStreamReader(input, StandardCharsets.UTF_8).use { reader ->
                    load(reader)
                }
            }
        }

    val botConfig =
        BotConfig(
            adminChatId = props.getProperty("telegram.bot.admin.chat.id").toLong(),
            adminMessageRelayText = props.getProperty("telegram.bot.admin.message.relay.text"),
            allowedChatIds =
                props
                    .getProperty("telegram.bot.allowed.chat.ids")
                    .split(",")
                    .mapNotNull { it.trim().toLongOrNull() },
            commandStartRelayText = props.getProperty("telegram.bot.command.start.relay.text"),
            errorEmptyRelayText = props.getProperty("telegram.bot.error.empty.relay.text"),
            errorFileRelayText = props.getProperty("telegram.bot.error.file.relay.text"),
            errorMessageRelayText = props.getProperty("telegram.bot.error.message.relay.text"),
            errorUnknownRelayText = props.getProperty("telegram.bot.error.unknown.relay.text"),
            mmjPrompt = props.getProperty("telegram.bot.mmj.prompt"),
            placeHolderRelayText = props.getProperty("telegram.bot.place.holder.relay.text"),
            proxy =
                props
                    .getProperty("telegram.bot.proxy.hostname")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { hostname ->
                        ProxyConfig(hostname = hostname, port = props.getProperty("telegram.bot.proxy.port").toInt())
                    },
            rateLimitRelayText = props.getProperty("telegram.bot.rate.limit.relay.text"),
            token = props.getProperty("telegram.bot.token"),
            username = props.getProperty("telegram.bot.username"),
        )

    val commandMatches: Map<String, BotCommand> =
        run {
            val dmxapiClient = openAiClient("dmxapi")
            val bigmodelClient = openAiClient("bigmodel")
            val deepseekClient = openAiClient("deepseek")
            val doubaoClient = openAiClient("doubao")
            val kimiClient = openAiClient("kimi")

            val gptModel = model("gpt")
            val glmModel = model("glm")
            val dsModel = model("ds")
            val geminiModel = model("gemini")
            val grokModel = model("grok")
            val claudeModel = model("claude")
            val doubaoModel = model("doubao")
            val kimiModel = model("kimi")

            fun native(
                client: OpenAI,
                modelId: ModelId,
            ) = BotCommand.Completion(client, modelId, mmj = false)

            fun mmj(
                client: OpenAI,
                modelId: ModelId,
            ) = BotCommand.Completion(client, modelId, mmj = true)

            mapOf(
                "start" to BotCommand.Start,
                "native" to native(dmxapiClient, gptModel),
                "mmj" to mmj(dmxapiClient, gptModel),
                "native1" to native(dmxapiClient, gptModel),
                "mmj1" to mmj(dmxapiClient, gptModel),
                "native2" to native(bigmodelClient, glmModel),
                "mmj2" to mmj(bigmodelClient, glmModel),
                "native3" to native(deepseekClient, dsModel),
                "mmj3" to mmj(deepseekClient, dsModel),
                "native4" to native(dmxapiClient, geminiModel),
                "mmj4" to mmj(dmxapiClient, geminiModel),
                "native5" to native(dmxapiClient, grokModel),
                "mmj5" to mmj(dmxapiClient, grokModel),
                "native6" to native(dmxapiClient, claudeModel),
                "mmj6" to mmj(dmxapiClient, claudeModel),
                "native7" to native(doubaoClient, doubaoModel),
                "mmj7" to mmj(doubaoClient, doubaoModel),
                "native8" to native(kimiClient, kimiModel),
                "mmj8" to mmj(kimiClient, kimiModel),
            )
        }

    private fun openAiClient(provider: String): OpenAI =
        OpenAI(
            OpenAIConfig(
                timeout =
                    Timeout(
                        request = 360.seconds,
                        socket = 300.seconds,
                        connect = 10.seconds,
                    ),
                logging = LoggingConfig(logLevel = LogLevel.None),
                token = props.getProperty("openai.provider.$provider.token").orEmpty(),
                host = OpenAIHost(props.getProperty("openai.provider.$provider.host").orEmpty()),
            ),
        )

    private fun model(key: String) = ModelId(props.getProperty("openai.model.$key.default"))

    sealed interface BotCommand {
        data object Start : BotCommand

        data class Completion(
            val client: OpenAI,
            val model: ModelId,
            val mmj: Boolean,
        ) : BotCommand
    }
}
