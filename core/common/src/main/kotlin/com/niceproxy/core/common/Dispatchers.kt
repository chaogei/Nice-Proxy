package com.niceproxy.core.common

import javax.inject.Qualifier

/**
 * 注入调度器而不是直接引用 [kotlinx.coroutines.Dispatchers]，
 * 这样单元测试可以替换成 TestDispatcher。
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class Dispatcher(val kind: NiceDispatcher)

enum class NiceDispatcher { IO, Default }

/**
 * 与应用进程同生命周期的协程作用域，用于服务状态订阅这类
 * 不应随页面或服务销毁而取消的长期工作。
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
