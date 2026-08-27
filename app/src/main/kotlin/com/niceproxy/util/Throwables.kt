package com.niceproxy.util

/**
 * 给界面看的一行异常描述。
 *
 * `message` 经常是空的（OkHttp 关流、协程取消都这样），只显示它等于没显示；
 * 退回类名至少能让用户在反馈问题时说清楚是哪一类失败。
 */
fun Throwable.describe(): String =
    message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "未知错误"
