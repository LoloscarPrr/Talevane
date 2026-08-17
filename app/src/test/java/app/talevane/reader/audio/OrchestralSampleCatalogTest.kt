package app.talevane.reader.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class OrchestralSampleCatalogTest {
    @Test
    fun `classifies the orchestral families used by the VSCO pack`() {
        assertEquals(
            OrchestralFamily.STRINGS,
            OrchestralSampleCatalog.familyFor("VlnEns_Pizz_C4_v2_rr1.wav")
        )
        assertEquals(
            OrchestralFamily.WOODWINDS,
            OrchestralSampleCatalog.familyFor("DCClar_stac_D3_v2_rr1.wav")
        )
        assertEquals(
            OrchestralFamily.BRASS,
            OrchestralSampleCatalog.familyFor("TbnEns_Stac_Bb2_ff_1.wav")
        )
        assertEquals(
            OrchestralFamily.KEYS,
            OrchestralSampleCatalog.familyFor("glock_medium_C6.wav")
        )
        assertEquals(
            OrchestralFamily.PERCUSSION,
            OrchestralSampleCatalog.familyFor("BDrumNewhit_v4_rr1.wav")
        )
    }

    @Test
    fun `parses sharp flat and natural root notes from sample names`() {
        assertEquals(60, OrchestralSampleCatalog.rootMidiFor("Violin_sus_C4_v1.wav"))
        assertEquals(51, OrchestralSampleCatalog.rootMidiFor("Bassoon_D#3_v1.wav"))
        assertEquals(46, OrchestralSampleCatalog.rootMidiFor("Trombone_Bb2_v1.wav"))
    }
}
