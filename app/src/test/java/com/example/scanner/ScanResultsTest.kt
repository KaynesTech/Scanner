package com.example.scanner

import org.junit.Assert.*
import org.junit.Test

class ScanResultsTest {
    private fun device(name: String? = "Sensor One", rssi: Int = -60, address: String = "02:00:00:00:00:01", at: Long = 1000) =
        ScanDevice(name, address, rssi, ScanMode.BLE, "Dual-mode device", "Not paired", seenAt = at)
    @Test fun sliderEndpointsAndSavedThresholdRoundTrip() {
        assertEquals(-20, RssiRange.threshold(20))
        assertEquals(-110, RssiRange.threshold(110))
        assertEquals(-20, RssiRange.threshold(0))
        assertEquals(-110, RssiRange.threshold(200))
        for (threshold in -110..-20) assertEquals(threshold, RssiRange.threshold(RssiRange.sliderValue(threshold)))
    }
    @Test fun lowerSliderValuesOnlyIncludeStrongerSignals() {
        val results = ScanResults()
        results.observe(device(rssi = -40))
        results.observe(device(rssi = -90, address = "02:00:00:00:00:02"))
        fun shown(value: Int) = results.visible(RssiRange.threshold(value), NameFilter(""), 1000, true).size
        assertEquals(0, shown(20)); assertEquals(1, shown(50)); assertEquals(2, shown(110))
    }
    @Test fun sameAddressCombinesSourcesWithoutLosingBleDetails() {
        val results = ScanResults()
        results.observe(device().copy(connectable = true, services = listOf("advertised"), manufacturers = listOf("data"), txPower = -4))
        results.observe(ScanDevice(null, "02:00:00:00:00:01", -55, ScanMode.CLASSIC,
            "Dual-mode device", "Paired", services = listOf("cached"), seenAt = 2000))
        val item = results.visible(-75, NameFilter(""), 2000, true).single()
        assertEquals(setOf(ScanMode.BLE, ScanMode.CLASSIC), item.sources)
        assertEquals(listOf("advertised", "cached"), item.services)
        assertEquals(listOf("data"), item.manufacturers)
        assertEquals(true, item.connectable); assertEquals(-4, item.txPower)
        assertEquals("Sensor One", item.name); assertEquals(-55, item.rssi); assertEquals(2, item.observations)
    }
    @Test fun differentAddressesWithSameNameRemainDistinct() {
        val results = ScanResults()
        results.observe(device())
        results.observe(ScanDevice("Sensor One", "02:00:00:00:00:02", -55, ScanMode.CLASSIC,
            "Classic device", "Not paired", seenAt = 1000))
        assertEquals(2, results.visible(-75, NameFilter(""), 1000, true).size)
    }
    @Test fun unnamedDevicesAreHiddenByDefaultAndCanBeIncludedExplicitly() {
        val results = ScanResults()
        results.observe(device(null)); results.observe(device("Weak", -95, "02:00:00:00:00:02"))
        assertEquals(0, results.visible(-75, NameFilter(""), 1000, true).size)
        assertEquals(1, results.visible(-75, NameFilter(""), 1000, true, showUnnamed = true).size)
        assertEquals(2, results.visible(-100, NameFilter("   "), 1000, true, showUnnamed = true).size)
    }
    @Test fun unnamedToggleDoesNotBypassNameRegexOrRssi() {
        val results = ScanResults(); results.observe(device(null, -80))
        assertEquals(0, results.visible(-75, NameFilter(""), 1000, true, showUnnamed = true).size)
        assertEquals(0, results.visible(-90, NameFilter("Sensor"), 1000, true, showUnnamed = true).size)
        assertEquals(0, results.visible(-90, NameFilter(".*"), 1000, true).size)
        assertEquals(1, results.visible(-90, NameFilter("^$"), 1000, true, showUnnamed = true).size)
    }
    @Test fun blankAdvertisementFallsBackToCachedName() {
        assertEquals("Sensor One", resolveDeviceName("", "Sensor One"))
        assertEquals("Sensor One", resolveDeviceName("   ", "Sensor One"))
        assertEquals("Sensor One", resolveDeviceName(null, "Sensor One"))
        assertEquals("Beacon_1234", resolveDeviceName("Beacon_1234", "Sensor One"))
        assertNull(resolveDeviceName("", "   "))
    }
    @Test fun regexIsCaseInsensitiveSubstringUnlessAnchored() {
        assertTrue(NameFilter("sensor.*one").matches("Sensor One"))
        assertTrue(NameFilter("Beacon|Sensor").matches("Beacon_1234"))
        assertFalse(NameFilter("^Sensor$").matches("Sensor One"))
        assertTrue(NameFilter("^Beacon_[0-9A-F]{4}$").matches("Beacon_1234"))
    }
    @Test fun bothFiltersMustMatchAndThresholdIsInclusive() {
        val results = ScanResults()
        results.observe(device(rssi = -75)); results.observe(device(rssi = -76, address = "02:00:00:00:00:02"))
        results.observe(device("Other", -40, "02:00:00:00:00:03"))
        assertEquals(listOf("02:00:00:00:00:01"), results.visible(-75, NameFilter("Sensor"), 1000, true).map { it.address })
    }
    @Test fun invalidOrUnsupportedRegexNeverMatchesEverything() {
        for (expression in listOf("[", "(?=Sensor)", "(a)\\1", "a".repeat(257))) {
            val filter = NameFilter(expression)
            assertNotNull(expression, filter.error); assertFalse(filter.matches("Sensor")); assertFalse(filter.matches(null))
        }
    }
    @Test fun pathologicalBacktrackingPatternFinishesQuickly() {
        assertFalse(NameFilter("^(a+)+$").matches("a".repeat(20000) + "!"))
    }
    @Test fun duplicateMacUpdatesLatestRssiAndRetainsAdvertisedName() {
        val results = ScanResults()
        results.observe(device()); results.observe(device(null, -90, at = 2000))
        assertEquals(1, results.size)
        val item = results.visible(-100, NameFilter("Sensor"), 2000, true).single()
        assertEquals(-90, item.rssi); assertEquals(2, item.observations); assertEquals(2000L, item.seenAt)
    }
    @Test fun staleResultsDisappearDuringScanButRemainInspectableAfterStop() {
        val results = ScanResults(); results.observe(device())
        assertTrue(results.visible(-110, NameFilter(""), 22000, true).isEmpty())
        assertEquals(1, results.visible(-110, NameFilter(""), 22000, false).size)
    }
    @Test fun strongestSignalSortsFirstWithStableMacTieBreak() {
        val results = ScanResults()
        results.observe(device(rssi = -90)); results.observe(device(rssi = -40, address = "02:00:00:00:00:03"))
        results.observe(device(rssi = -40, address = "02:00:00:00:00:02"))
        assertEquals(listOf(-40, -40, -90), results.visible(-110, NameFilter(""), 1000, true).map { it.rssi })
        assertEquals("02:00:00:00:00:02", results.visible(-110, NameFilter(""), 1000, true).first().address)
    }
    @Test fun clearAndInvalidRssiAreHandled() {
        val results = ScanResults(); results.observe(device(rssi = 127)); assertEquals(0, results.size)
        results.observe(device()); results.clear(); assertEquals(0, results.size)
    }
    @Test fun storageIsBoundedAndKeepsRecentDevices() {
        val results = ScanResults()
        repeat(600) { results.observe(device(address = "test-$it", at = it.toLong())) }
        assertEquals(500, results.size)
        assertFalse(results.visible(-110, NameFilter(""), 600, false).any { it.address == "test-0" })
    }
}
