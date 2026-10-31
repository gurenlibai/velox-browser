package com.velox.browser.metrics

data class Stats(
    val name: String,
    val count: Int,
    val p50: Long,
    val p90: Long,
    val min: Long,
    val max: Long,
    val avg: Long
)
