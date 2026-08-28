package com.niceproxy.util

/**
 * 给界面看的一行异常描述。
 *
 * `message` 经常是空的（OkHttp 关流、协程取消都这样），只显示它等于没显示；
 * 退回类名至少能让用户在反馈问题时说清楚是哪一类失败。
 */
fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() }
    ?: this::class.simpleName
    // simpleName 对匿名类是 null。之前这里兜的是一句写死的中文，在英文界面下
    // 会突兀地冒出来；类的全名不可能为 null，而且对着它提问题更有用。
    ?: javaClass.name
