package io.github.dohex.openrose.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelProfileTest {
    @Test
    fun everyModelIsIdentifiedByEachOfItsOwnAdvertisementPrefixes() {
        for (profile in ModelProfile.entries) {
            for (prefix in profile.spec.advertisementPrefixes) {
                // Normalized data is the little-endian company ID followed by the payload; a trailing byte keeps it a prefix.
                val bytes = (prefix + "99").chunked(2).map { it.toInt(16) }
                val manufacturerData = mapOf((bytes[0] or (bytes[1] shl 8)) to bytes.drop(2).map(Int::toByte).toByteArray())
                assertEquals(profile, ModelIdentification.identify(manufacturerData), "$profile $prefix")
            }
        }
    }

    @Test
    fun everyModelNameIsMatchedAndTheWakeFilterAdmitsExactlyTheRoseNames() {
        val wakeFilter = Regex(ModelProfile.namePattern)
        for (profile in ModelProfile.entries) {
            val name = "${profile.spec.nameBrand.uppercase()}-${profile.spec.nameModelNumber}"
            assertEquals(profile, ModelProfile.match(name), name)
            assertTrue(wakeFilter.matches(name), name)
        }
        for (name in listOf("ROSE i5", "roseselsa i7", "EarFeel-i5", "EarFree i7", "EarFree", "EarFeel")) {
            assertTrue(ModelProfile.match(name) != null && wakeFilter.matches(name), name)
        }
        for (name in listOf("iPhone 15", "ThinkPad i7", "Sony WH-1000XM4", "AirPods Pro")) {
            assertFalse(ModelProfile.match(name) != null || wakeFilter.matches(name), name)
        }
    }

    @Test
    fun retailNamesKeepMk2BrandsAndSwiftGenerationsDistinct() {
        for (name in listOf("BudsFeel MK2", "ROSE BudsFeel-MK2", "budsfeel mk2")) {
            assertEquals(ModelProfile.BUDSFEEL_MK2_RS_V2, ModelProfile.match(name), name)
        }
        for (name in listOf("OpenFeel MK2", "ROSE OpenFeel-MK2", "openfeel mk2")) {
            assertEquals(ModelProfile.OPENFEEL_MK2_RS_V2, ModelProfile.match(name), name)
        }
        for (name in listOf("SWIFT 2", "SWIFT2", "ROSE Swift 2")) {
            assertEquals(ModelProfile.SWIFT2_RS_V2, ModelProfile.match(name), name)
        }
        assertEquals(ModelProfile.JASMINE_RS_V2, ModelProfile.match("Jasmine"))
        for (name in listOf("ROSE MK2", "OpenFeel", "BudsFeel", "SWIFT", "ROSE Swift", "ROSE Swift 20")) {
            assertNull(ModelProfile.match(name), name)
        }
    }
}
