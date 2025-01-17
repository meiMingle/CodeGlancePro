package com.nasller.codeglance.extensions.visitor

import MyRainbowVisitor
import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.lang.Language
import com.intellij.lang.LanguageCommenters
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.nasller.codeglance.config.CodeGlanceColorsPage
import com.nasller.codeglance.config.CodeGlanceConfigService
import java.util.concurrent.atomic.AtomicReference

class MarkCommentVisitor : MyRainbowVisitor() {

	override fun visit(element: PsiElement) {
		if (element is PsiComment) {
			val text = element.text
			markRegex.get()?.find(text)?.let {
				val textRange = element.textRange
				val index = text.indexOf('\n',it.range.last)
				val blockCommentSuffix by lazy(LazyThreadSafetyMode.NONE) { getLanguageBlockCommentSuffix(element.language) ?: "" }
				val end = if (index > 0) index + textRange.startOffset else {
					textRange.endOffset - if(index < 0 && blockCommentSuffix.isNotBlank() && text.endsWith(blockCommentSuffix)){
						blockCommentSuffix.length
					} else 0
				}
				addInfo(getInfo(it.range.last + textRange.startOffset + 1, end, CodeGlanceColorsPage.MARK_COMMENT_ATTRIBUTES))
			}
		}
	}

	override fun clone(): HighlightVisitor = MarkCommentVisitor()

	companion object{
		val markRegex = AtomicReference(CodeGlanceConfigService.getConfig().markRegex.run {
			if(isNotBlank()) Regex(this) else null
		})

		private fun getLanguageBlockCommentSuffix(language: Language) : String?{
			return when(language.displayName){
				"C#" -> "*/"
				else -> LanguageCommenters.INSTANCE.forLanguage(language)?.blockCommentSuffix
			}
		}
	}
}