package co.shaypunter.worldexplorer.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {

    private fun v(text: String) = requireNotNull(Version.parse(text)) { "unparsable: $text" }

    @Test
    fun comparesNumericPartsNotStrings() {
        assertTrue(v("0.10.0") > v("0.9.0"))
        assertTrue(v("1.0.0") > v("0.99.99"))
        assertTrue(v("0.4.1") > v("0.4.0"))
        assertTrue(v("2.0.0") < v("10.0.0"))
    }

    @Test
    fun leadingVAndMissingPartsDoNotMatter() {
        assertEquals(v("v0.4.0"), v("0.4.0"))
        assertEquals(v("V1.2"), v("1.2.0"))
        assertEquals(v("1"), v("1.0.0.0"))
        assertEquals(v("1.2").hashCode(), v("1.2.0").hashCode())
    }

    @Test
    fun preReleaseSortsBeforeItsRelease() {
        assertTrue(v("1.0.0-beta.1") < v("1.0.0"))
        assertTrue(v("1.0.0-alpha") < v("1.0.0-beta"))
        assertTrue(v("1.0.0-beta.2") < v("1.0.0-beta.11"))
        assertTrue(v("1.0.0-beta") < v("1.0.0-beta.1"))
        assertTrue(v("1.0.0-1") < v("1.0.0-alpha"))
        assertTrue(v("1.0.1-rc.1") > v("1.0.0"))
    }

    @Test
    fun buildMetadataIsIgnored() {
        assertEquals(v("1.0.0+build.7"), v("1.0.0"))
    }

    @Test
    fun rejectsThingsThatAreNotVersions() {
        for (bad in listOf("", "latest", "v", "1.", ".1", "1..2", "1.2.x", "release-1.0", "1.0.0-", "1.0.0-a..b", "99999999999.0")) {
            assertNull(bad, Version.parse(bad))
        }
    }

    @Test
    fun isNewerVersionOnlyForAGenuinelyNewerRelease() {
        assertTrue(isNewerVersion("v0.5.0", "0.4.0"))
        assertTrue(isNewerVersion("0.4.1", "0.4.0"))
        assertFalse(isNewerVersion("v0.4.0", "0.4.0"))
        assertFalse(isNewerVersion("v0.3.9", "0.4.0"))
        assertFalse(isNewerVersion("v0.5.0-beta.1", "0.5.0"))
        // An unparsable tag or installed version never prompts.
        assertFalse(isNewerVersion("nightly", "0.4.0"))
        assertFalse(isNewerVersion("v0.5.0", "dev-build"))
    }
}
