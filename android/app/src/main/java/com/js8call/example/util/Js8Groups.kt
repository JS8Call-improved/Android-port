package com.js8call.example.util

/**
 * The well-known JS8 group destinations, mirroring kBaseCalls in
 * core/src/protocol/varicode.cpp minus the "<....>" sentinel.
 * Js8GroupsTest reads that file and fails when the two drift apart.
 */
object Js8Groups {

    val WELL_KNOWN: List<String> = listOf(
        "@ALLCALL", "@JS8NET",
        "@DX/NA", "@DX/SA", "@DX/EU", "@DX/AS", "@DX/AF", "@DX/OC", "@DX/AN",
        "@REGION/1", "@REGION/2", "@REGION/3",
        "@GROUP/0", "@GROUP/1", "@GROUP/2", "@GROUP/3", "@GROUP/4",
        "@GROUP/5", "@GROUP/6", "@GROUP/7", "@GROUP/8", "@GROUP/9",
        "@COMMAND", "@CONTROL", "@NET", "@NTS",
        "@RESERVE/0", "@RESERVE/1", "@RESERVE/2", "@RESERVE/3", "@RESERVE/4",
        "@APRSIS", "@RAGCHEW", "@JS8", "@EMCOMM", "@ARES", "@MARS", "@AMRRON",
        "@RACES", "@RAYNET", "@RADAR", "@SKYWARN", "@CQ", "@HB", "@QSO",
        "@QSOPARTY", "@CONTEST", "@FIELDDAY", "@SOTA", "@IOTA", "@POTA",
        "@QRP", "@QRO"
    )

    /**
     * The well-known groups worth suggesting as message targets. The
     * protocol infrastructure addresses stay out of the picker.
     */
    val SUGGESTED: List<String> = WELL_KNOWN.filterNot {
        it in setOf("@COMMAND", "@CONTROL", "@APRSIS") ||
            it.startsWith("@RESERVE/")
    }
}
