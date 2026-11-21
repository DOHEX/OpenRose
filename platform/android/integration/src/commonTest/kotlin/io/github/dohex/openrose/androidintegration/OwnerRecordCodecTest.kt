package io.github.dohex.openrose.androidintegration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerRecordCodecTest {
    private val way = IntegrationSettingsCodec.CONTROL_WAY_KEY

    /** What a preference store holds after applying the encoded entries: null removes the key. */
    private fun apply(stored: Map<String, Any?>, record: OwnerRecord): Map<String, Any?> {
        val next = stored.toMutableMap()
        OwnerRecordCodec.encode(record).forEach { (key, value) -> if (value == null) next.remove(key) else next[key] = value }
        return next
    }

    @Test
    fun everyRecordShapeSurvivesAnEncodeDecodeRoundTrip() {
        val records = listOf(
            OwnerRecord(),
            OwnerRecord(ControlWay.MODULE),
            OwnerRecord(ControlWay.ORDINARY, PendingModuleRelease("r", null)),
            OwnerRecord(ControlWay.MODULE, PendingModuleRelease(null, null)),
            OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", OwnerGeneration("host-1", 7))),
            OwnerRecord(ControlWay.ORDINARY, PendingModuleRelease(null, OwnerGeneration("host-2", Long.MAX_VALUE))),
        )
        records.forEach { assertEquals(it, OwnerRecordCodec.decode(apply(emptyMap(), it))) }
    }

    @Test
    fun savingARecordWithoutReleaseRemovesEarlierReleaseKeys() {
        val full = apply(emptyMap(), OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", OwnerGeneration("h", 3))))
        val cleared = apply(full, OwnerRecord(ControlWay.MODULE))
        assertEquals(setOf(way), cleared.keys)
        val withoutGeneration = apply(full, OwnerRecord(ControlWay.MODULE, PendingModuleRelease("r", null)))
        assertEquals(PendingModuleRelease("r", null), OwnerRecordCodec.decode(withoutGeneration).moduleRelease)
        assertFalse("module_release_incarnation" in withoutGeneration)
        assertFalse("module_release_sequence" in withoutGeneration)
    }

    @Test
    fun aReleaseWithoutRequestIdIsStoredAsAnEmptyMarkerAndReadsBackAsNull() {
        val encoded = OwnerRecordCodec.encode(OwnerRecord(ControlWay.MODULE, PendingModuleRelease(null, null)))
        assertEquals("", encoded["module_release_request"])
    }

    @Test
    fun missingOrMistypedEntriesFallBackToOrdinaryWithoutRelease() {
        assertEquals(OwnerRecord(), OwnerRecordCodec.decode(emptyMap()))
        assertEquals(OwnerRecord(), OwnerRecordCodec.decode(mapOf(way to 1, "module_release_request" to true)))
        assertEquals(OwnerRecord(), OwnerRecordCodec.decode(mapOf(way to "unknown")))
        assertEquals(OwnerRecord(ControlWay.MODULE), OwnerRecordCodec.decode(mapOf(way to "module")))
    }

    @Test
    fun aGenerationNeedsBothANonEmptyIncarnationAndAParsableSequence() {
        fun release(incarnation: Any?, sequence: Any?) = OwnerRecordCodec.decode(
            mapOf("module_release_request" to "r", "module_release_incarnation" to incarnation, "module_release_sequence" to sequence),
        ).moduleRelease
        assertEquals(OwnerGeneration("h", 4), release("h", "4")?.generation)
        assertNull(release("", "4")?.generation)
        assertNull(release("h", "four")?.generation)
        assertNull(release("h", 4L)?.generation)
        assertNull(release("h", null)?.generation)
        assertNull(release(null, "4")?.generation)
        assertEquals("r", release("h", "x")?.requestId)
    }

    @Test
    fun aGenerationWithoutPendingRequestKeyIsIgnored() {
        val values = mapOf("module_release_incarnation" to "h", "module_release_sequence" to "4")
        assertNull(OwnerRecordCodec.decode(values).moduleRelease)
    }

    @Test
    fun theHostIsAskedToPublishOnlyWhenAModuleChoiceChanges() {
        var asked = 0
        val canPublish = {
            asked++
            false
        }
        val ordinary = OwnerRecord()
        val module = OwnerRecord(ControlWay.MODULE)
        assertTrue(OwnerRecordCodec.canSave(ordinary, ordinary, canPublish))
        assertTrue(OwnerRecordCodec.canSave(module, module.copy(moduleRelease = PendingModuleRelease("r", null)), canPublish))
        assertEquals(0, asked)
        assertFalse(OwnerRecordCodec.canSave(ordinary, module, canPublish))
        assertFalse(OwnerRecordCodec.canSave(module, ordinary, canPublish))
        assertEquals(2, asked)
        assertTrue(OwnerRecordCodec.canSave(ordinary, module) { true })
    }

    @Test
    fun savingPublishesOnlyWhenTheChoiceChangesAndInvolvesTheModule() {
        val ordinary = OwnerRecord()
        val module = OwnerRecord(ControlWay.MODULE)
        var published = 0
        val publish = {
            published++
            true
        }
        assertTrue(OwnerRecordCodec.save(ordinary, ordinary.copy(moduleRelease = PendingModuleRelease("r", null)), publish) { true })
        assertTrue(OwnerRecordCodec.save(module, module.copy(moduleRelease = PendingModuleRelease("r", null)), publish) { true })
        assertEquals(0, published)
        assertTrue(OwnerRecordCodec.save(ordinary, module, publish) { true })
        assertEquals(1, published)
    }

    @Test
    fun aFailedWriteStopsBeforeAnythingIsPublished() {
        var published = 0
        val writes = mutableListOf<OwnerRecord>()
        val saved = OwnerRecordCodec.save(OwnerRecord(), OwnerRecord(ControlWay.MODULE), {
            published++
            true
        }) {
            writes += it
            false
        }
        assertFalse(saved)
        assertEquals(listOf(OwnerRecord(ControlWay.MODULE)), writes)
        assertEquals(0, published)
    }

    @Test
    fun aRejectedPublishRestoresThePreviousRecordAndPublishesItAgain() {
        val previous = OwnerRecord(ControlWay.ORDINARY, PendingModuleRelease("earlier", null))
        val next = OwnerRecord(ControlWay.MODULE)
        val events = mutableListOf<String>()
        val saved = OwnerRecordCodec.save(
            previous,
            next,
            publish = {
                events += "publish"
                false
            },
            write = {
                events += if (it == next) {
                    "write-next"
                } else if (it == previous) {
                    "write-previous"
                } else {
                    "write-other"
                }
                true
            },
        )
        assertFalse(saved)
        assertEquals(listOf("write-next", "publish", "write-previous", "publish"), events)
    }
}
