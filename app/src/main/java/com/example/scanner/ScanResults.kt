package com.example.scanner

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException

enum class ScanMode { BLE, CLASSIC }

object RssiRange {
    const val MIN = 20
    const val MAX = 110
    fun threshold(sliderValue: Int) = -sliderValue.coerceIn(MIN, MAX)
    fun sliderValue(threshold: Int) = (-threshold).coerceIn(MIN, MAX)
}

internal fun resolveDeviceName(advertised: String?, cached: String?): String? =
    advertised?.takeIf { it.isNotBlank() } ?: cached?.takeIf { it.isNotBlank() }

data class ScanDevice(
    val name: String?, val address: String, val rssi: Int, val mode: ScanMode,
    val deviceType: String, val bond: String, val connectable: Boolean? = null,
    val services: List<String> = emptyList(), val manufacturers: List<String> = emptyList(),
    val txPower: Int? = null, val seenAt: Long, val observations: Int = 1,
    val sources: Set<ScanMode> = setOf(mode),
)

class NameFilter(expression: String) {
    private var pattern: Pattern? = null
    var error: String? = null
        private set
    init {
        if (expression.length > 256) error = "Regex must be 256 characters or fewer"
        else if (expression.isNotBlank()) try {
            pattern = Pattern.compile(expression, Pattern.CASE_INSENSITIVE)
        } catch (e: PatternSyntaxException) { error = "Invalid regex: ${e.description}" }
    }
    fun matches(name: String?): Boolean = error == null && (pattern?.matcher(name.orEmpty())?.find() ?: true)
}

class ScanResults {
    private val items = linkedMapOf<String, ScanDevice>()
    val size get() = items.size
    fun observe(device: ScanDevice) {
        if (device.rssi !in -127..20) return
        val old = items[device.address]
        items[device.address] = device.copy(
            name = device.name?.takeIf { it.isNotBlank() } ?: old?.name,
            observations = (old?.observations ?: 0) + 1,
            services = (old?.services.orEmpty() + device.services).distinct(),
            manufacturers = device.manufacturers.ifEmpty { old?.manufacturers.orEmpty() },
            txPower = device.txPower ?: old?.txPower,
            connectable = device.connectable ?: old?.connectable,
            sources = old?.sources.orEmpty() + device.sources,
        )
        if (items.size > 500) items.remove(items.minBy { it.value.seenAt }.key)
    }
    fun visible(threshold: Int, filter: NameFilter, now: Long, scanning: Boolean, showUnnamed: Boolean = false): List<ScanDevice> = items.values.filter {
        (showUnnamed || !it.name.isNullOrBlank()) && it.rssi >= threshold && filter.matches(it.name) && (!scanning || now - it.seenAt <= 20_000)
    }.sortedWith(compareByDescending<ScanDevice> { it.rssi }.thenBy { it.address })
    fun clear() { items.clear() }
}
