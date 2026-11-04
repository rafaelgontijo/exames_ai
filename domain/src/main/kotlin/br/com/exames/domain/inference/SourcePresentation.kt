package br.com.exames.domain.inference

import br.com.exames.domain.parser.SourceFragment

/** Presentation only: adjacent literal words, original IDs and geometry, no parser fields or truth. */
internal data class SourcePresentation(val raw: String, val ids: List<String>, val x: Double?, val y: Double?)
internal fun presentSource(evidence: Map<String,SourceFragment>, emit: (SourcePresentation) -> Unit) {
    var words=mutableListOf<Pair<String,SourceFragment>>()
    var length=0
    fun flush() {
        if(words.isEmpty()) return
        val first=words.first().second
        val raw=StringBuilder()
        for((_,word) in words) {
            if(word.spaceBefore==true && raw.isNotEmpty() && !raw.last().isWhitespace() && word.raw.firstOrNull()?.isWhitespace()==false) raw.append(' ')
            raw.append(word.raw)
        }
        val box=first.region
        emit(SourcePresentation(raw.toString(),words.map { it.first },box?.let { it.left/it.pageWidth },box?.let { it.top/it.pageHeight }))
        words=mutableListOf();length=0
    }
    for(entry in evidence.entries) {
        val word=entry.value
        val previous=words.lastOrNull()?.second
        val a=previous?.region;val b=word.region
        val height=if(a!=null && b!=null) minOf(a.bottom-a.top,b.bottom-b.top) else 0.0
        val join=previous!=null && a!=null && b!=null &&
            a.page==b.page && a.pageWidth==b.pageWidth && a.pageHeight==b.pageHeight && a.rotationDegrees==b.rotationDegrees &&
            height>0 && minOf(a.bottom,b.bottom)-maxOf(a.top,b.top)>=height*0.5 &&
            b.left>=a.right && b.left-a.right<=height*0.75 && words.size<128 &&
            length+word.raw.length+1<=512 && !previous.raw.any { it.isISOControl() } && !word.raw.any { it.isISOControl() }
        if(!join) flush()
        words+=entry.key to word;length+=word.raw.length+1
    }
    flush()
}
