package com.nasller.codeglance.render

import com.intellij.ide.ui.UISettings
import com.intellij.openapi.application.invokeLater
import com.intellij.openapi.editor.*
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.ex.FoldingListener
import com.intellij.openapi.editor.ex.RangeHighlighterEx
import com.intellij.openapi.editor.ex.util.EditorUtil
import com.intellij.openapi.editor.ex.util.EmptyEditorHighlighter
import com.intellij.openapi.util.SystemInfoRt
import com.intellij.openapi.util.TextRange
import com.intellij.util.DocumentUtil
import com.intellij.util.Range
import com.intellij.util.ui.UIUtil
import com.nasller.codeglance.config.CodeGlanceColorsPage
import com.nasller.codeglance.panel.GlancePanel
import com.nasller.codeglance.util.MyVisualLinesIterator
import org.jetbrains.kotlin.ir.descriptors.IrAbstractDescriptorBasedFunctionFactory.Companion.offset
import java.awt.Color
import java.awt.Font
import java.beans.PropertyChangeEvent
import java.util.*
import kotlin.collections.set
import kotlin.math.roundToInt

@Suppress("UnstableApiUsage")
class TestMinimap(glancePanel: GlancePanel) : BaseMinimap(glancePanel){
	private val renderDataList = ArrayList<LineRenderData?>(Collections.nCopies(editor.visibleLineCount, null))
	init { makeListener() }

	override fun update() {
		val curImg = getMinimapImage() ?: return
		if(rangeList.size > 0) rangeList.clear()
		val markAttributes = editor.colorsScheme.getAttributes(CodeGlanceColorsPage.MARK_COMMENT_ATTRIBUTES)
		val font by lazy {
			editor.colorsScheme.getFont(
				when (markAttributes.fontType) {
					Font.ITALIC -> EditorFontType.ITALIC
					Font.BOLD -> EditorFontType.BOLD
					Font.ITALIC or Font.BOLD -> EditorFontType.BOLD_ITALIC
					else -> EditorFontType.PLAIN
				}
			).deriveFont(config.markersScaleFactor * config.pixelsPerLine)
		}
		val text = editor.document.immutableCharSequence
		val graphics = curImg.createGraphics()
		graphics.composite = GlancePanel.CLEAR
		graphics.fillRect(0, 0, curImg.width, curImg.height)
		UISettings.setupAntialiasing(graphics)
		var totalY = 0
		var skipLine = 0
		for ((index, it) in renderDataList.withIndex()) {
			if(it == null) continue
			it.rebuildRange(index, totalY)
			totalY += it.aboveBlockLine
			if(skipLine > 0){
				if(it.aboveBlockLine > 0) skipLine -= skipLine - 2
				else skipLine--
				totalY += it.y
				continue
			}
			when(it.lineType){
				LineType.CODE -> {
					it.renderX.forEach { renderX ->
						renderX.color.setColorRgba()
						var curX = renderX.xStart
						text.subSequence(renderX.xStart, renderX.xEnd).forEach { char ->
							curX += when (char.code) {
								9 -> 4 //TAB
								else -> 1
							}
							curImg.renderImage(curX, totalY, char.code)
						}
					}
				}
				LineType.COMMENT -> {
					graphics.composite = GlancePanel.srcOver
					graphics.color = markAttributes.foregroundColor
					val commentText = text.substring(it.commentHighlighterEx!!.startOffset, it.commentHighlighterEx.endOffset)
					val textFont = if (!SystemInfoRt.isMac && font.canDisplayUpTo(commentText) != -1) {
						UIUtil.getFontWithFallback(font).deriveFont(markAttributes.fontType, font.size2D)
					} else font
					graphics.font = textFont
					graphics.drawString(commentText,2,totalY + (graphics.getFontMetrics(textFont).height / 1.5).roundToInt())
					//skip line
					skipLine = config.markersScaleFactor.toInt() - 1 - (if(it.y > config.pixelsPerLine) it.y - config.pixelsPerLine else 0)
				}
				LineType.CUSTOM_FOLD -> {
					it.color!!.setColorRgba()
					var curX = 0
					var curY = totalY
					it.renderStr!!.forEach {char ->
						when (char.code) {
							9 -> curX += 4 //TAB
							10 -> {//ENTER
								curX = 0
								curY += config.pixelsPerLine
							}
							else -> curX += 1
						}
						curImg.renderImage(curX, curY, char.code)
					}
				}
			}
			totalY += it.y
		}
		graphics.dispose()
	}

	private fun LineRenderData.rebuildRange(index: Int,curY: Int){
		if(lineType == LineType.CUSTOM_FOLD){
			rangeList.add(index to Range(curY, curY + y - config.pixelsPerLine + aboveBlockLine))
		}else if(aboveBlockLine > 0){
			rangeList.add(index - 1 to Range(curY, curY + y - config.pixelsPerLine + aboveBlockLine))
		}
	}

	private fun refreshRenderData(startVisualLine: Int = 0, endVisualLine: Int = 0) {
		if(editor.isDisposed) return
		if(startVisualLine == 0 && endVisualLine == 0) resetRenderData()
		val visLinesIterator = MyVisualLinesIterator(editor, startVisualLine)
		if(visLinesIterator.atEnd()) return

		val defaultColor = editor.colorsScheme.defaultForeground
		val markCommentMap = hashMapOf<Int,RangeHighlighterEx>()
		editor.filteredDocumentMarkupModel.processRangeHighlightersOverlappingWith(visLinesIterator.getVisualLineStartOffset(), editor.document.textLength){
			if(CodeGlanceColorsPage.MARK_COMMENT_ATTRIBUTES == it.textAttributesKey){
				markCommentMap[DocumentUtil.getLineStartOffset(it.startOffset,editor.document)] = it
			}
			return@processRangeHighlightersOverlappingWith true
		}
		while (!visLinesIterator.atEnd()) {
			val visualLine = visLinesIterator.getVisualLine()
			//BLOCK_INLAY
			val aboveBlockLine = visLinesIterator.getBlockInlaysAbove().sumOf { (it.heightInPixels * scrollState.scale).toInt() }
			//CUSTOM_FOLD
			val customFoldRegion = visLinesIterator.getCustomFoldRegion()
			if(customFoldRegion != null){
				val startOffset = customFoldRegion.startOffset
				val endOffset = customFoldRegion.endOffset
				//jump over the fold line
				val heightLine = (customFoldRegion.heightInPixels * scrollState.scale).toInt()
				//this is render document
				val line = visLinesIterator.getStartLogicalLine() - 1 + (heightLine / config.pixelsPerLine)
				val renderStr = editor.document.getText(TextRange(startOffset, if(DocumentUtil.isValidLine(line,editor.document)) {
					val lineEndOffset = editor.document.getLineEndOffset(line)
					if(endOffset < lineEndOffset) endOffset else lineEndOffset
				}else endOffset))
				renderDataList[visualLine] = LineRenderData(emptyArray(), heightLine, aboveBlockLine, LineType.CUSTOM_FOLD, renderStr = renderStr,
					color = runCatching { editor.highlighter.createIterator(endOffset - 1).textAttributes.foregroundColor }.getOrNull() ?: defaultColor)
			}else{
				val start = visLinesIterator.getVisualLineStartOffset()
				//COMMENT
				val commentData = markCommentMap[start]
				if(commentData != null){
					renderDataList[visualLine] = LineRenderData(emptyArray(), config.pixelsPerLine, aboveBlockLine,
						LineType.COMMENT, commentHighlighterEx = commentData)
				}else{
					var x = visLinesIterator.getStartsWithSoftWrap()?.indentInColumns ?: 0
					val end = visLinesIterator.getVisualLineEndOffset()
					val hlIter = editor.highlighter.createIterator(start)
					val xRenderDataList = mutableListOf<XRenderData>()
					while (!hlIter.atEnd() && hlIter.end <= end){
						val curStart = hlIter.start
						val curEnd = hlIter.end
						val xEnd = x + (curEnd - curStart)
						val highlightList = if(config.syntaxHighlight) getHighlightColor(curStart, curEnd) else emptyList()
						xRenderDataList.add(XRenderData(x, xEnd, (highlightList.firstOrNull {
							offset >= it.startOffset && offset < it.endOffset
						}?.foregroundColor ?: runCatching { hlIter.textAttributes.foregroundColor }.getOrNull() ?: defaultColor)))
						x = xEnd + 1
						hlIter.advance()
					}
					renderDataList[visualLine] = LineRenderData(xRenderDataList.toTypedArray(), config.pixelsPerLine, aboveBlockLine)
				}
			}
			if(endVisualLine == 0 || visualLine <= endVisualLine) visLinesIterator.advance()
			else break
		}
		updateImage()
	}

	override fun rebuildDataAndImage(directUpdate: Boolean) {
		if (directUpdate) refreshRenderData()
		else invokeLater{ refreshRenderData() }
	}

	private fun resetRenderData(){
		renderDataList.clear()
		renderDataList.addAll(Collections.nCopies(editor.visibleLineCount, null))
	}

	/** FoldingListener */
	override fun onFoldProcessingEnd() {
		if (editor.document.isInBulkUpdate) return
		refreshRenderData()
	}

	override fun onCustomFoldRegionPropertiesChange(region: CustomFoldRegion, flags: Int) {
		if (flags and FoldingListener.ChangeFlags.HEIGHT_CHANGED != 0 && !editor.document.isInBulkUpdate) {
			val visualLine = editor.offsetToVisualLine(region.startOffset)
			refreshRenderData(visualLine, visualLine)
		}
	}

	/** InlayModel.Listener */
	override fun onAdded(inlay: Inlay<*>) = checkinInlayAndUpdate(inlay)

	override fun onRemoved(inlay: Inlay<*>) = checkinInlayAndUpdate(inlay)

	override fun onUpdated(inlay: Inlay<*>, changeFlags: Int) = checkinInlayAndUpdate(inlay, changeFlags)

	private fun checkinInlayAndUpdate(inlay: Inlay<*>, changeFlags: Int? = null) {
		if(editor.document.isInBulkUpdate || editor.inlayModel.isInBatchMode || inlay.placement != Inlay.Placement.ABOVE_LINE
			|| !inlay.isValid || (changeFlags != null && changeFlags and InlayModel.ChangeFlags.HEIGHT_CHANGED == 0)) return
		val visualLine = editor.offsetToVisualLine(inlay.offset)
		refreshRenderData(visualLine,visualLine)
	}

	override fun onBatchModeFinish(editor: Editor) {
		if (editor.document.isInBulkUpdate) return
		refreshRenderData()
	}

	/** SoftWrapChangeListener */
	override fun softWrapsChanged() {
		val enabled = editor.softWrapModel.isSoftWrappingEnabled
		if (enabled && !softWrapEnabled) {
			softWrapEnabled = true
			refreshRenderData()
		} else if (!enabled && softWrapEnabled) {
			softWrapEnabled = false
			refreshRenderData()
		}
	}

	/** MarkupModelListener */
	override fun afterAdded(highlighter: RangeHighlighterEx) = updateRangeHighlight(highlighter,false)

	override fun beforeRemoved(highlighter: RangeHighlighterEx) = updateRangeHighlight(highlighter,true)

	private fun updateRangeHighlight(highlighter: RangeHighlighterEx,remove: Boolean) {
		//如果开启隐藏滚动条则忽略Vcs高亮
		val highlightChange = glancePanel.markCommentState.markCommentHighlightChange(highlighter, remove)
		if (editor.document.isInBulkUpdate || editor.inlayModel.isInBatchMode || editor.foldingModel.isInBatchFoldingOperation
			|| (glancePanel.config.hideOriginalScrollBar && highlighter.isThinErrorStripeMark)) return
		if(highlightChange || EditorUtil.attributesImpactForegroundColor(highlighter.getTextAttributes(editor.colorsScheme))) {
			val visualLine = editor.offsetToVisualLine(highlighter.startOffset)
			refreshRenderData(visualLine, visualLine)
		} else if(highlighter.getErrorStripeMarkColor(editor.colorsScheme) != null){
			repaintOrRequest(false)
		}
	}

	/** PrioritizedDocumentListener */
	private var myUpdateInProgress: Boolean = false
	private var myDocumentChangeOldEndLine = 0

	override fun beforeDocumentChange(event: DocumentEvent) {
		if (event.document.isInBulkUpdate) return
		myUpdateInProgress = true
		myDocumentChangeOldEndLine = editor.offsetToVisualLine(event.offset + event.oldLength)
	}

	override fun documentChanged(event: DocumentEvent) {
		try {
			if (event.document.isInBulkUpdate) return
			val startVisualLine = editor.offsetToVisualLine(event.offset)
			val endVisualLine = editor.offsetToVisualLine(event.offset + event.newLength)
			if(myDocumentChangeOldEndLine < endVisualLine) {
				renderDataList.addAll(myDocumentChangeOldEndLine + 1,
					Collections.nCopies(endVisualLine - myDocumentChangeOldEndLine, null))
			}else if(myDocumentChangeOldEndLine > endVisualLine) {
				renderDataList.subList(endVisualLine + 1, myDocumentChangeOldEndLine + 1).clear()
			}
			refreshRenderData(startVisualLine, endVisualLine)
		}finally {
			myUpdateInProgress = false
		}
	}

	override fun bulkUpdateFinished(document: Document) = refreshRenderData()

	/** PropertyChangeListener */
	override fun propertyChange(evt: PropertyChangeEvent) {
		if (EditorEx.PROP_HIGHLIGHTER != evt.propertyName || evt.newValue is EmptyEditorHighlighter) return
		refreshRenderData()
	}

	override fun dispose() {
		super.dispose()
		renderDataList.clear()
		rangeList.clear()
	}

	private data class LineRenderData(val renderX: Array<XRenderData>, val y: Int,val aboveBlockLine: Int, val lineType: LineType = LineType.CODE,
	                                  val renderStr: String? = null, val color: Color? = null, val commentHighlighterEx: RangeHighlighterEx? = null) {
		override fun equals(other: Any?): Boolean {
			if (this === other) return true
			if (javaClass != other?.javaClass) return false
			other as LineRenderData
			if (!renderX.contentEquals(other.renderX)) return false
			if (y != other.y) return false
			if (aboveBlockLine != other.aboveBlockLine) return false
			if (lineType != other.lineType) return false
			if (renderStr != other.renderStr) return false
			if (color != other.color) return false
			return commentHighlighterEx == other.commentHighlighterEx
		}

		override fun hashCode(): Int {
			var result = renderX.contentHashCode()
			result = 31 * result + y
			result = 31 * result + aboveBlockLine
			result = 31 * result + lineType.hashCode()
			result = 31 * result + (renderStr?.hashCode() ?: 0)
			result = 31 * result + (color?.hashCode() ?: 0)
			result = 31 * result + (commentHighlighterEx?.hashCode() ?: 0)
			return result
		}
	}

	private data class XRenderData(val xStart: Int, val xEnd: Int, val color: Color)

	private enum class LineType{ CODE, COMMENT, CUSTOM_FOLD}
}