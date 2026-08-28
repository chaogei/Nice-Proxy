package com.niceproxy.feature.inbound

import androidx.annotation.StringRes
import com.niceproxy.R
import com.niceproxy.core.model.InboundType

/**
 * 入站类型的界面文案。
 *
 * `InboundType` 自带的 `displayName` / `description` 是 `core:model` 里写死的
 * 中文常量。那一层没有 `Context`，也不该有 —— 它同时被配置生成器和数据库用到，
 * 拖一个 Android 依赖进去只为了取字符串，代价不成比例。映射放在 app 这一侧。
 *
 * 协议名（HTTP、SOCKS、PAC）在任何语言下都不翻译。
 */
@StringRes
fun InboundType.labelRes(): Int = when (this) {
    InboundType.MIXED -> R.string.inbound_type_mixed
    InboundType.HTTP -> R.string.inbound_type_http
    InboundType.SOCKS -> R.string.inbound_type_socks
    InboundType.PAC -> R.string.inbound_type_pac
}

@StringRes
fun InboundType.descriptionRes(): Int = when (this) {
    InboundType.MIXED -> R.string.inbound_type_mixed_desc
    InboundType.HTTP -> R.string.inbound_type_http_desc
    InboundType.SOCKS -> R.string.inbound_type_socks_desc
    InboundType.PAC -> R.string.inbound_type_pac_desc
}
