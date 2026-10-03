# shikigami

一个基于 Kotlin 的 Telegram LLM 机器人。用户在 Telegram 中发送 `/native`、`/mmj` 等命令，机器人将文本（及图片）转发给多个 OpenAI 兼容的模型服务，并把模型回复以 Telegram MarkdownV2 格式编辑到原占位消息中返回。

## 功能特性

- **多提供商路由**：单机接入 5 个 LLM 提供商（dmxapi、智谱 BigModel、DeepSeek、豆包、Kimi），通过不同命令切换模型。
- **多模态输入**：支持图片（取最大尺寸的 photo，命令写在图片 caption 中）和静态贴纸（webp；贴纸消息无法携带命令，只能通过回复贴纸消息把贴纸带入上下文），下载后以 base64 作为图像输入；回复某条消息时会把被回复消息（含图片）作为上下文，被回复内容若是机器人自己的发言则以 assistant 角色注入。
- **人格模式（mmj）**：`mmj` 系列命令在调用同一模型的同时注入可配置的 system prompt（默认为东方 Project 角色犬走椛的“阴阳怪气”人格），`native` 系列则不带人格。
- **MarkdownV2 渲染**：将模型输出的 CommonMark（含 GFM 表格、删除线）转换为 Telegram MarkdownV2 并做特殊字符转义；超长内容分段截断，编辑失败时回退为纯文本。
- **限流**：基于用户 ID 的 60 秒固定窗口限流——补全命令 10 次/分钟，`/start` 1 次/分钟。
- **权限控制与审计**：群聊仅响应白名单内的 chat id，私聊全放行；非白名单用户触发补全时，机器人会把消息原文转发给管理员。
- **健壮性**：Telegram API 调用自带重试（发送/编辑失败后最多重试 3 次，合计 4 次尝试；文件下载最多尝试 3 次）；主循环异常自动延迟 5 秒后重试；收到 SIGTERM/SIGINT 后优雅停机（停止接收新更新，等待进行中的补全任务最多 30 秒，超时则强制取消）；支持为 Telegram 配置 HTTP 代理。

## 命令一览

| 命令 | 提供商 | 默认模型 |
| --- | --- | --- |
| `/start` | — | 返回一段彩蛋文本 |
| `/native` `/native1`（及对应 `mmj`） | dmxapi | `gpt-6-sol` |
| `/native2`（及 `mmj2`） | BigModel | `glm-5.3-flash-guan` |
| `/native3`（及 `mmj3`） | DeepSeek | `deepseek-flash-guan` |
| `/native4`（及 `mmj4`） | dmxapi | `gemini-3.8-flash` |
| `/native5`（及 `mmj5`） | dmxapi | `grok-4.7` |
| `/native6`（及 `mmj6`） | dmxapi | `claude-opus-5-5` |
| `/native7`（及 `mmj7`） | 豆包（方舟） | `doubao-seed-2-1-pro-260628` |
| `/native8`（及 `mmj8`） | Kimi（月之暗面） | `kimi-k3` |

命令与提供商/模型的映射在 `src/main/kotlin/com/github/shikigami/Config.kt` 中集中定义，模型名与各提供商的 token、host 均从 `config.properties` 读取。

用法示例：`/native2 帮我写一首诗`，或直接回复某条消息并发送命令把该消息作为上下文。

## 快速开始

环境要求：JDK 21（Gradle toolchain 会自动解析）。

1. 复制配置模板并填写：

   ```bash
   cp config.properties.example config.properties
   ```

2. 必填配置：

   - `telegram.bot.token`、`telegram.bot.username` —— BotFather 创建的机器人 token 与用户名（群聊中 `@bot` 后缀匹配、识别机器人自身发言时使用）
   - `telegram.bot.admin.chat.id` —— 管理员 chat id，用于接收越权使用报告
   - `telegram.bot.allowed.chat.ids` —— 允许响应的群聊 chat id，逗号分隔；私聊不受此限制
   - `openai.provider.<name>.token` —— 各提供商的 API key（用不到的提供商可留空）

3. 本地运行：

   ```bash
   ./gradlew run
   ```

### Docker 运行

项目自带 `docker-compose.yml`（基于 `amazoncorretto:21` 镜像，挂载源码后执行 `./gradlew run`）：

```bash
docker compose up -d
```

容器配置了 `stop_grace_period: 40s`，`docker stop` 时为优雅停机的 30 秒宽限期留足时间。

Gradle 缓存挂载到 `.docker/.gradle`，该目录已在 `.gitignore` 中忽略。

### 代理

如果所在网络无法直连 Telegram API，配置 `telegram.bot.proxy.hostname` / `telegram.bot.proxy.port`（默认示例为 `127.0.0.1:7890`，置空 hostname 可禁用代理）。代理仅作用于 Telegram 客户端，不影响各 LLM 提供商的访问。

## 配置项说明

所有配置集中在根目录 `config.properties`（UTF-8 编码，已被 git 忽略）：

- `telegram.bot.*` —— 机器人 token、用户名、白名单、代理，以及各类回复文案（占位“🧠 思考中…”、限流提示、各类错误提示、`/start` 彩蛋、mmj 人格 prompt 等）
- `openai.provider.<name>.token` / `openai.provider.<name>.host` —— 各提供商的 API key 与 OpenAI 兼容接口地址
- `openai.model.<name>.default` —— 各命令使用的默认模型名

## 技术栈

- [Kotlin](https://kotlinlang.org/) 2.4 / JVM 21，协程并发
- [vendeli telegram-bot](https://github.com/vendelieu/telegram-bot) —— Telegram Bot 框架（含 ktnip KSP 插件，启动时加载其生成的 context loader）
- [openai-client](https://github.com/Aallam/openai-kotlin) —— OpenAI 兼容客户端（OkHttp 引擎），统一访问各 LLM 提供商
- [telegram-markdownv2](https://github.com/kamiiroawase/telegram-markdownv2) —— Markdown 转 Telegram MarkdownV2，超长回复经 renderChunked 分多条消息发送
- [Spotless](https://github.com/diffplug/spotless) + ktlint 代码格式化

## 开发

```bash
./gradlew spotlessCheck   # 检查代码格式
./gradlew spotlessApply   # 自动格式化
./gradlew test            # 运行测试（kotlin-test）
```
