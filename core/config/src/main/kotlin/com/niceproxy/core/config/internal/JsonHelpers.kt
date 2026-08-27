package com.niceproxy.core.config.internal

import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * sing-box 对大量字段有默认值，显式写出空值会让配置噪音很大，
 * 个别字段（如空的 `alpn` 数组）甚至会改变内核行为。
 * 这组辅助函数保证只序列化真正有意义的字段。
 */

internal fun JsonObjectBuilder.putIfNotBlank(key: String, value: String?) {
    if (!value.isNullOrBlank()) put(key, value)
}

internal fun JsonObjectBuilder.putIfNotNull(key: String, value: Int?) {
    if (value != null) put(key, value)
}

internal fun JsonObjectBuilder.putIfNotNull(key: String, value: Long?) {
    if (value != null) put(key, value)
}

internal fun JsonObjectBuilder.putIfNotNull(key: String, value: Boolean?) {
    if (value != null) put(key, value)
}

internal fun JsonObjectBuilder.putIfTrue(key: String, value: Boolean) {
    if (value) put(key, true)
}

internal fun JsonObjectBuilder.putIfNotEmpty(key: String, values: List<String>) {
    if (values.isNotEmpty()) putJsonArray(key) { values.forEach { add(it) } }
}

@JvmName("putIfNotEmptyInt")
internal fun JsonObjectBuilder.putIfNotEmpty(key: String, values: List<Int>) {
    if (values.isNotEmpty()) putJsonArray(key) { values.forEach { add(it) } }
}

internal fun JsonObjectBuilder.putIfNotEmpty(key: String, values: Map<String, String>) {
    if (values.isEmpty()) return
    put(key, JsonObject(values.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }))
}

internal fun JsonObjectBuilder.putObjectIfNotNull(key: String, value: JsonObject?) {
    if (value != null && value.isNotEmpty()) put(key, value)
}

internal fun JsonArrayBuilder.addObject(value: JsonObject) {
    add(value)
}
