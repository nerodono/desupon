package moe.desupon.feature.settings.logic

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeoUrlTest {
    @Test
    fun acceptsHttpFileUrlsAndBlank() {
        listOf(
            "",
            "  ",
            "https://raw.githubusercontent.com/runetfreedom/russia-v2ray-rules-dat/release/geoip.dat",
            "http://192.0.2.1:8080/geosite.dat",
            " HTTPS://mirror.example/data/geoip.dat?token=x ",
        ).forEach { assertTrue(GeoUrl.isValid(it), it) }
    }

    @Test
    fun rejectsOtherSchemesHostlessAndDirectoryUrls() {
        listOf(
            "ftp://mirror.example/geoip.dat",
            "file:///sdcard/geoip.dat",
            "mirror.example/geoip.dat",
            "https:///geoip.dat",
            "https://mirror.example",
            "https://mirror.example/",
            "https://mirror.example/data/",
            "https://mirror.example/geo ip.dat",
            "https://mirror.example:70000/geoip.dat",
        ).forEach { assertFalse(GeoUrl.isValid(it), it) }
    }

    @Test
    fun flagsPlainHttp() {
        assertTrue(GeoUrl.cleartext(" HTTP://192.0.2.1:8080/geosite.dat"))
        assertFalse(GeoUrl.cleartext("https://mirror.example/geoip.dat"))
        assertFalse(GeoUrl.cleartext(""))
    }
}
