package com.niceproxy.traffic

/**
 * 最近若干秒的上下行速率。
 *
 * 只活在内存里，进程一死就没了 —— 这是**刻意**的取舍，而不是「等数据库做好再说」。
 * FR-6.4 那张「按日/按节点」的报表需要持久化，但它回答的是「这个月用了多少」；
 * 而用户盯着首页看曲线时想知道的是「刚才那下卡顿是不是我这边的问题」，
 * 那个问题只关心最近这一分钟，落盘对它没有任何帮助，反而要为每秒一帧的写入
 * 付出 I/O 与磨损。
 *
 * 用 [ArrayDeque] 而不是 `list.takeLast(n)`：后者每秒分配两个新列表，
 * 而这个类的整个存在理由就是它每秒都要被写一次。
 *
 * **非线程安全。** 调用方（ViewModel 的单条收集协程）负责串行化。
 */
class TrafficHistory(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity >= MIN_CAPACITY) { "容量至少 $MIN_CAPACITY 个采样点才画得出折线" }
    }

    private val upload = ArrayDeque<Long>(capacity)
    private val download = ArrayDeque<Long>(capacity)

    /** 负速率没有物理意义，多半是内核重启后计数回绕，按 0 记而不是画到 x 轴下面。 */
    fun record(uploadBytesPerSecond: Long, downloadBytesPerSecond: Long) {
        upload.addLast(uploadBytesPerSecond.coerceAtLeast(0))
        download.addLast(downloadBytesPerSecond.coerceAtLeast(0))
        while (upload.size > capacity) upload.removeFirst()
        while (download.size > capacity) download.removeFirst()
    }

    fun clear() {
        upload.clear()
        download.clear()
    }

    /**
     * 取一份不可变快照给 UI。
     *
     * 必须复制：直接把 [ArrayDeque] 交出去的话，下一次 [record] 会原地改掉
     * Compose 已经拿在手里的那份数据，而实例没变 —— 界面不会刷新，
     * 或者更糟，在遍历途中被并发修改。
     */
    fun snapshot(): TrafficSamples {
        // 上下行共用一把纵向标尺，否则一条 10 KB/s 的上传曲线会和
        // 10 MB/s 的下载曲线画得一样高，图就成了误导。
        val peak = maxOf(upload.maxOrNull() ?: 0L, download.maxOrNull() ?: 0L)
        return TrafficSamples(
            upload = upload.toList(),
            download = download.toList(),
            peak = peak,
        )
    }

    companion object {
        /** 每秒一帧，一分钟 —— 刚好覆盖「刚才卡了一下」这个时间尺度。 */
        const val DEFAULT_CAPACITY = 60
        const val MIN_CAPACITY = 2
    }
}

/**
 * @property peak 两条曲线里的最高值，用作共同的纵轴上限。
 *           全为 0 时表示这段时间完全没有流量。
 */
data class TrafficSamples(
    val upload: List<Long> = emptyList(),
    val download: List<Long> = emptyList(),
    val peak: Long = 0,
) {
    /** 少于两个点画不出折线，UI 据此决定显示占位还是曲线。 */
    val isPlottable: Boolean get() = upload.size >= TrafficHistory.MIN_CAPACITY
}
