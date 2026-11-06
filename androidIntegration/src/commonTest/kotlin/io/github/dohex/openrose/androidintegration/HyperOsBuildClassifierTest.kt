package io.github.dohex.openrose.androidintegration

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
            classifyHyperOsBuild("Xiaomi", "Xiaomi", "OS1.0.1.0.WMUMIXM"),
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

    @Test
    fun unrecognizedXiaomiBuildFormatsAndAndroidUnknownMarkerStayUnknown() {
        listOf(
            "V15.0.1.0.TMOMIXM",
            "MIUI-14",
            "OS3",
            "unknown",
            "UNKNOWN",
        ).forEach { version ->
            assertEquals(
                PlatformApplicability.UNKNOWN,
                classifyHyperOsBuild("Xiaomi", "Redmi", version),
                "unrecognized build identity '$version' must not be treated as unsupported",
            )
        }
        assertEquals(
            PlatformApplicability.UNKNOWN,
            classifyHyperOsBuild("unknown", "unknown", "OS3.0.1.0.WOBCNXM"),
            "Android Build.UNKNOWN manufacturer and brand markers do not establish Xiaomi identity",
        )
    }
}
