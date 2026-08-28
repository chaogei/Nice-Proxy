package com.niceproxy.core.network.clash

import com.niceproxy.core.network.concurrent.BoundedQueue
import com.niceproxy.core.network.concurrent.OverflowPolicy
import com.niceproxy.core.network.concurrent.SendResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一条 WebSocket 流在**传输层**的样子：只吐原始文本，不认识任何业务类型。
 *
 * 抽成接口的唯一目的是让 [clashFrameFlow] 能脱离 OkHttp 单测 —— 背压、合帧、
 * 终止传播这几件事全都发生在这一层之上，而它们恰恰是最难用真实 WebSocket 复现的。
 */
internal interface FrameSink {

    /**
     * 收到一帧。**会在传输层自己的线程上被调用**（OkHttp 那条读线程），
     * 所以实现必须是无锁竞争、不挂起、不做解析的纯入队。
     */
    fun onFrame(text: String)

    /** 这条连接结束了。[cause] 一定非空：正常关闭也要走重连，见 ClashApiClient 的注释。 */
    fun onTerminated(cause: Throwable)
}

/** 一条已建立的传输层连接。关闭它必须能打断阻塞中的读。 */
internal fun interface FrameSubscription {
    fun cancel()
}

/** 每条流各自的溢出观测量，由 [clashFrameFlow] 回填给调用方累加。 */
internal interface FrameStreamRecorder {
    fun onSendResult(result: SendResult)
    fun onParseFailure()

    companion object {
        val NOOP = object : FrameStreamRecorder {
            override fun onSendResult(result: SendResult) = Unit
            override fun onParseFailure() = Unit
        }
    }
}

/**
 * 把一条原始文本流接成一条已解析、有背压保护的 [Flow]。
 *
 * 这里解决的是三个具体问题：
 *
 * 1. **解析不能发生在回调线程上。** OkHttp 的 WebSocket 读线程是**按连接**的，
 *    但它们同属一个共享的线程池；在上面跑 JSON 解析，一条 `/connections` 的大快照
 *    （几百条连接、几十 KB）就足以让同一批线程上的 `/traffic`、`/logs` 一起卡顿。
 *    所以回调里只做一次 [BoundedQueue.trySend]，解析挪到 [parseDispatcher]。
 *
 * 2. **突发必须有界。** `callbackFlow` 默认给 64 格缓冲，收集方一慢就先攒 64 帧
 *    再开始丢，而这 64 帧对 latest-value 语义的流全是垃圾 —— 用户要看的只有最新那帧。
 *    队列容量与策略在这里显式指定：观测流用 [OverflowPolicy.LATEST_WINS] 合帧，
 *    日志用 [OverflowPolicy.DROP_OLDEST] 保留最近若干条。
 *
 * 3. **下游必须是会合式的。** 末尾的 `buffer(RENDEZVOUS)` 把 `channelFlow` 自带的
 *    64 格缓冲取消掉。留着它就等于在我们精心限容的队列后面又串了一个不受控的缓冲，
 *    合帧策略会被它整个架空。
 */
internal fun <T : Any> clashFrameFlow(
    capacity: Int,
    policy: OverflowPolicy,
    parseDispatcher: CoroutineDispatcher,
    recorder: FrameStreamRecorder = FrameStreamRecorder.NOOP,
    parse: (String) -> T?,
    connect: (FrameSink) -> FrameSubscription,
): Flow<T> = channelFlow {
    val queue = BoundedQueue<String>(capacity, policy)

    // 解析泵单独起一条协程，而不是直接在 channelFlow 的块里循环：终止信号来自
    // 传输层线程，它必须能**打断一个正阻塞在 queue.receive() 上的消费者**。
    // 只调 close() 是不够的 —— 服务端关闭之后不会再有帧，消费者会永远等下去。
    val pump = launch { drainInto(queue, parseDispatcher, recorder, parse) }

    val subscription = connect(object : FrameSink {
        override fun onFrame(text: String) {
            recorder.onSendResult(queue.trySend(text))
        }

        override fun onTerminated(cause: Throwable) {
            // 先带着原因关闭下游，再唤醒泵。反过来的话泵可能先正常结束，
            // 收集方就只看到「流悄悄完成了」，重连逻辑无从触发。
            close(cause)
            pump.cancel()
        }
    })

    try {
        pump.join()
    } finally {
        subscription.cancel()
    }
}.buffer(Channel.RENDEZVOUS)

private suspend fun <T : Any> ProducerScope<T>.drainInto(
    queue: BoundedQueue<String>,
    parseDispatcher: CoroutineDispatcher,
    recorder: FrameStreamRecorder,
    parse: (String) -> T?,
) = withContext(parseDispatcher) {
    try {
        while (true) {
            val raw = queue.receive()
            // 内核偶尔推来无法解析的帧（例如版本更新引入的新字段），
            // 丢掉单帧远好于让整条流断掉。
            val parsed = runCatching { parse(raw) }.getOrNull()
            if (parsed == null) {
                recorder.onParseFailure()
                continue
            }
            send(parsed)
        }
    } catch (e: ClosedSendChannelException) {
        // 终止信号先关下游、再唤醒泵，两者之间那一瞬泵可能正好在 send。
        // 收集方要的终止原因已经随 close 带过去了，这里再抛一次只会盖掉它。
    }
}
