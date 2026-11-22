package br.com.exames.datadocuments

import br.com.exames.domain.model.BoundingBox
import org.junit.Assert.*
import org.junit.Test

class PdfWordFragmentsTest {
    private fun chars(text: String)=text.mapIndexed { i,c -> PdfTextSpan("glyph-$i",i,c.toString(),
        if(c.isWhitespace()) null else BoundingBox(i*5,10,i*5+5,20)) }
    @Test fun coalescesLiteralNumbersAndWordsWithoutChangingSeparatorsOrInventingSpaces() {
        val words=PdfWordFragments.group(1,chars("1,234 mg/dL"),100,100,"test/words1")
        assertEquals(listOf("1,234","mg/dL"),words.map { it.raw })
        assertFalse(words[0].spaceBefore!!);assertTrue(words[1].spaceBefore!!)
        assertEquals(0.0,words[0].region!!.left,0.0);assertEquals(25.0,words[0].region!!.right,0.0)
        assertEquals(words.map { it.spanId }.distinct().size,words.size)
    }
    @Test fun separatedColumnsAndUnpositionedOrControlTextAreNeverSilentlyJoinedOrDiscarded() {
        val spans=listOf(PdfTextSpan("a",0,"Glicose",BoundingBox(0,10,20,20)),
            PdfTextSpan("b",1,"15",BoundingBox(50,10,60,20)),PdfTextSpan("c",2,"unknown",null),PdfTextSpan("d",3,"\u0000",null))
        val words=PdfWordFragments.group(1,spans,100,100,"1")
        assertEquals(listOf("Glicose","15","unknown","\u0000"),words.map { it.raw })
        assertNull(words[2].region);assertNull(words[3].region)
        assertFalse(words[1].spaceBefore!!)
    }
    @Test fun newlinesAndOverlappingReversedGlyphsPreserveExplicitSourceBoundaries() {
        val spans=chars("15\n20")
        assertEquals(listOf("15","20"),PdfWordFragments.group(1,spans,100,100,"1").map { it.raw })
        val reversed=chars("12").reversed()
        assertEquals(listOf("2","1"),PdfWordFragments.group(1,reversed,100,100,"1").map { it.raw })
    }
}
