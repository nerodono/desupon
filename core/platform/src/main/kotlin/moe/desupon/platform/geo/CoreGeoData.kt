package moe.desupon.platform.geo

import moe.desupon.core.bridge.Bridge

/** The core's v2ray .dat reader and `.srs` writer; blocking, JSON in and out as native/bridge/geodata.go defines. */
object CoreGeoData {
    fun index(datPath: String, kind: String): String = Bridge.geoDataIndex(datPath, kind)

    fun convert(datPath: String, kind: String, namesJson: String, outDir: String): String =
        Bridge.geoDataConvert(datPath, kind, namesJson, outDir)
}
