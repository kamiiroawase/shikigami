package com.github.shikigami.model

data class FileRef(
    // 解析阶段猜测的兜底值，实际 MIME 以下载内容的文件头嗅探结果为准
    val mimeType: String,
    val fileId: String,
)
