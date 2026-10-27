package io.github.dohex.openrose

import io.github.dohex.openrose.core.PlatformApplicability
import kotlin.test.Test
import kotlin.test.assertEquals

class HyperOsBuildClassifierTest {
    @Test
    fun classificationUsesXiaomiAndroidBuildIdentityAndKeepsUnknownFactsSeparate() {
        assertEquals(
            PlatformApplicability.APPLICABLE,
            classifyHyperOsBuild("Xiaomi", "Xiaomi", "OS3.0.1.0.WOBCNXM"),
        )
        assertEquals(
            PlatformApplicability.APPLICABLE,
            classifyHyperOsBuild("Xiaomi", "POCO", "OS4.0.1.0.WMUMIXM"),
        )
        assertEquals(
            PlatformApplicability.NOT_APPLICABLE,
            classifyHyperOsBuild("Xiaomi", "Xiaomi", "OS2.0.1.0.WMUMIXM"),
        )
        assertEquals(
            PlatformApplicability.NOT_APPLICABLE,
            classifyHyperOsBuild("Xiaomi", "Redmi", "V14.0.4.0.TMOMIXM"),
        )
        assertEquals(
            PlatformApplicability.NOT_APPLICABLE,
            classifyHyperOsBuild("Google", "Pixel", "OS3.0.1.0"),
        )
        assertEquals(
            PlatformApplicability.UNKNOWN,
            classifyHyperOsBuild("Xiaomi", "Redmi", "OS5.0.1.0"),
        )
        assertEquals(
            PlatformApplicability.UNKNOWN,
            classifyHyperOsBuild("Xiaomi", "Redmi", ""),
        )
        assertEquals(
            PlatformApplicability.UNKNOWN,
            classifyHyperOsBuild("", "", "OS3.0.1.0"),
        )
    }
}
