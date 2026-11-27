package com.deepseek.chat.data

/**
 * 把底层网络/鉴权错误翻成人话（保留原文在括号里，方便排查）。
 */
object McpErrors {

    private const val CLOCK = "手机时间与服务器相差超过 5 分钟，签名鉴权被拒绝。" +
        "请开启「自动设置时间」后重试（设置 → 系统 → 日期和时间）"
    private const val CLOSED = "服务器未响应（连接被关闭），请确认服务器已启动、地址和端口正确"
    private const val REFUSED = "端口没有服务在监听，请确认地址/端口，或服务器是否启动"
    private const val DNS = "地址解析失败，请检查 IP 填写"

    fun friendly(error: Throwable): String {
        if (error is java.net.SocketTimeoutException) {
            return "连接超时（3 秒），请检查地址/端口/网络"
        }
        if (error is java.net.UnknownHostException) return DNS
        if (error is java.net.ConnectException) return REFUSED
        val raw = error.message?.trim().orEmpty()
        return when {
            raw.isEmpty() -> error.javaClass.simpleName
            raw.contains("时间戳") || raw.contains("timestamp", ignoreCase = true) ||
                raw.contains("clock", ignoreCase = true) -> CLOCK
            raw.contains("connection closed", ignoreCase = true) ||
                raw.contains("connection reset", ignoreCase = true) -> CLOSED
            raw.contains("ECONNREFUSED", ignoreCase = true) ||
                raw.contains("Connection refused", ignoreCase = true) -> REFUSED
            raw.contains("UnknownHost", ignoreCase = true) ||
                raw.contains("Unable to resolve host", ignoreCase = true) -> DNS
            else -> "$raw（${error.javaClass.simpleName}）"
        }
    }
}
