package br.com.exames.domain.inference

import br.com.exames.domain.parser.SourceFragment
import br.com.exames.domain.validation.SourceRegion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SourcePresentationTest {
    private fun word(id: String,raw: String,x: Double,y: Double=10.0,space: Boolean?=true)=SourceFragment(id,raw,
        SourceRegion(1,x,y,x+10,y+10,600.0,800.0),"SYNTHETIC","test/1",spaceBefore=space)
    private fun groups(vararg words: SourceFragment): List<SourcePresentation> {
        val result=mutableListOf<SourcePresentation>()
        presentSource(words.mapIndexed { i,w -> "e${i+1}" to w }.toMap(),result::add)
        return result
    }
    @Test fun adjacentLiteralWordsKeepEveryOriginalIdAndExplicitSpacing() {
        val result=groups(word("a","10",0.0,space=false),word("b","a",12.0,space=false),
            word("c","20",24.0,space=false),word("d","mg/dL",36.0))
        assertEquals("10a20 mg/dL",result.single().raw)
        assertEquals(listOf("e1","e2","e3","e4"),result.single().ids)
        assertEquals(0.0,result.single().x);assertEquals(0.0125,result.single().y)
    }
    @Test fun differentRowsColumnsUnknownGeometryAndReversedOrderStaySeparate() {
        assertEquals(2,groups(word("a","a",0.0),word("b","b",30.0)).size)
        assertEquals(2,groups(word("a","a",0.0),word("b","b",12.0,30.0)).size)
        assertEquals(2,groups(word("a","a",12.0),word("b","b",0.0)).size)
        assertEquals(2,groups(word("a","a",0.0).copy(region=null),word("b","b",12.0)).size)
    }
    @Test fun groupingNeverChangesUnknownSpacingOrCrossesControlText() {
        assertEquals("ab",groups(word("a","a",0.0),word("b","b",12.0,space=null)).single().raw)
        assertEquals(2,groups(word("a","a\n",0.0),word("b","b",12.0)).size)
    }
    @Test fun lengthAndMemberBoundsSplitRatherThanDiscardSource() {
        val many=(0..128).map { i -> SourceFragment("w$i","x",
            SourceRegion(1,i*10.0,0.0,i*10.0+10,10.0,2000.0,100.0),"SYNTHETIC","test/1",spaceBefore=true) }
        val result=groups(*many.toTypedArray())
        assertEquals(listOf(128,1),result.map { it.ids.size })
        assertEquals(129,result.sumOf { it.ids.size })
        assertEquals(2,groups(word("a","a".repeat(510),0.0),word("b","bb",12.0)).size)
    }
}
