package com.feldman.scholix.drive

enum class ReaderFit { Page, Width }
object ReaderSizing {
    fun widthZoom(pageWidth:Float,pageHeight:Float,viewWidth:Float,viewHeight:Float):Float {
        if(listOf(pageWidth,pageHeight,viewWidth,viewHeight).any {!it.isFinite()||it<=0f})return 1f
        val fittedWidth=minOf(viewWidth,viewHeight*pageWidth/pageHeight)
        return (viewWidth/fittedWidth).coerceIn(1f,10f)
    }
}
