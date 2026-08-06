package uk.hpkns.mdcomments

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil
import javax.swing.Icon

object MarkdownCommentPresentationManager {
    private val inlaysKey = Key.create<MutableList<Inlay<*>>>("markdown.comments.inlays")
    private val foldsKey = Key.create<MutableList<FoldRegion>>("markdown.comments.folds")
    private val gutterKey = Key.create<MutableList<RangeHighlighter>>("markdown.comments.gutters")
    private val concealedRawKey = Key.create<MutableList<RangeHighlighter>>("markdown.comments.concealed.raw")

    // Tracks which comment startOffsets are in display mode. null = initial load (all display).
    private val displayModeOffsetsKey = Key.create<MutableSet<Int>>("markdown.comments.display.offsets")
    private val toggleIcon: Icon =
        IconLoader.getIcon("/icons/comment-toggle.svg", MarkdownCommentPresentationManager::class.java)

    /** Rebuilds rendered Markdown comment presentation for a single editor. */
    fun refresh(
        editor: Editor,
        project: Project,
    ) {
        if (editor.isDisposed || editor.isViewer) return

        clear(editor)
        if (!service<MarkdownCommentsSettings>().enabled) return

        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        if (psiFile.isPhysical.not()) return

        val inlays = mutableListOf<Inlay<*>>()
        val folds = mutableListOf<FoldRegion>()
        val gutters = mutableListOf<RangeHighlighter>()
        val concealedRaw = mutableListOf<RangeHighlighter>()

        // null = initial load → treat all current comments as display mode and seed the set.
        val existingDisplayOffsets = editor.getUserData(displayModeOffsetsKey)
        val isInitialLoad = existingDisplayOffsets == null
        val displayModeOffsets = existingDisplayOffsets ?: mutableSetOf()

        val comments = PsiTreeUtil.findChildrenOfType(psiFile, PsiComment::class.java).toList()
        val commentBlocks = buildCommentBlocks(editor, comments)
        editor.foldingModel.runBatchFoldingOperation {
            for ((startOffset, endOffset, markdown, startLine, indentColumns) in commentBlocks) {
                // On initial load every comment is registered as display-mode.
                // On subsequent refreshes, only known offsets are display; new offsets → raw.
                val inDisplayMode = isInitialLoad || displayModeOffsets.contains(startOffset)
                if (isInitialLoad) displayModeOffsets += startOffset
                val inRawMode = !inDisplayMode

                val line = editor.document.getLineNumber(startOffset)
                val gutter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.ADDITIONAL_SYNTAX, null)
                gutter.gutterIconRenderer =
                    CommentModeToggleGutterIconRenderer(
                        editor = editor,
                        project = project,
                        commentStartOffset = startOffset,
                        inRawMode = inRawMode,
                    )
                gutters += gutter

                if (inRawMode) continue

                // Fold covers the comment text only (PSI endOffset is exclusive so the trailing
                // newline is outside the fold, naturally terminating the fold-placeholder line).
                // The inlay at endOffset with showAbove=false appears below the fold placeholder.
                val inlay =
                    editor.inlayModel.addBlockElement(
                        endOffset,
                        true,
                        false,
                        0,
                        MarkdownCommentRenderer(
                            markdown = markdown,
                            indentLine = startLine,
                            indentColumns = indentColumns,
                        ),
                    )
                if (inlay == null) continue

                val foldRegion =
                    when (val foldingModel = editor.foldingModel) {
                        is FoldingModelEx ->
                            foldingModel.createFoldRegion(startOffset, endOffset, " ", null, true)
                        else ->
                            foldingModel.addFoldRegion(startOffset, endOffset, " ")
                    }
                if (foldRegion == null) {
                    val concealHighlighter =
                        editor.markupModel.addRangeHighlighter(
                            startOffset,
                            endOffset,
                            HighlighterLayer.ADDITIONAL_SYNTAX,
                            concealedCommentTextAttributes(editor),
                            HighlighterTargetArea.EXACT_RANGE,
                        )
                    concealedRaw += concealHighlighter
                } else {
                    foldRegion.isExpanded = false
                    folds += foldRegion
                }
                inlays += inlay
            }
        }

        editor.putUserData(displayModeOffsetsKey, displayModeOffsets)

        editor.putUserData(inlaysKey, inlays)
        editor.putUserData(foldsKey, folds)
        editor.putUserData(gutterKey, gutters)
        editor.putUserData(concealedRawKey, concealedRaw)
    }

    /** Removes all Markdown inlays and fold regions previously managed by this plugin. */
    fun clear(editor: Editor) {
        editor.getUserData(inlaysKey)?.forEach { it.dispose() }
        editor.putUserData(inlaysKey, null)

        val foldRegions = editor.getUserData(foldsKey).orEmpty()
        if (foldRegions.isNotEmpty()) {
            editor.foldingModel.runBatchFoldingOperation {
                foldRegions.forEach { region ->
                    if (region.isValid) {
                        editor.foldingModel.removeFoldRegion(region)
                    }
                }
            }
        }
        editor.putUserData(foldsKey, null)

        editor.getUserData(gutterKey)?.forEach { highlighter ->
            editor.markupModel.removeHighlighter(highlighter)
        }
        editor.putUserData(gutterKey, null)

        editor.getUserData(concealedRawKey)?.forEach { highlighter ->
            editor.markupModel.removeHighlighter(highlighter)
        }
        editor.putUserData(concealedRawKey, null)
    }

    /** Refreshes all open editors, optionally restricted to one project. */
    fun refreshAllOpenEditors(project: Project? = null) {
        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.isDisposed || editor.isViewer) continue
            val editorProject = editor.project ?: findProject(editor) ?: continue
            if (project != null && project != editorProject) continue
            refresh(editor, editorProject)
        }
    }

    /** Sets one comment to raw-text or rendered-preview mode. */
    fun setRawMode(
        editor: Editor,
        commentStartOffset: Int,
        rawMode: Boolean,
    ) {
        val displayOffsets =
            editor.getUserData(displayModeOffsetsKey)
                ?: mutableSetOf<Int>().also {
                    editor.putUserData(displayModeOffsetsKey, it)
                }
        if (rawMode) {
            displayOffsets -= commentStartOffset
        } else {
            displayOffsets += commentStartOffset
        }
    }

    /** Attempts to resolve the owning project for the editor document. */
    fun findProject(editor: Editor): Project? {
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return null
        return ProjectLocator.getInstance().guessProjectForFile(file)
    }

    /** Returns true when the given text is a documentation-style comment. */
    private fun isDocComment(text: String): Boolean = text.trimStart().startsWith("/**")

    /** Groups adjacent single-line comments into one presentational Markdown block. */
    private fun buildCommentBlocks(
        editor: Editor,
        comments: List<PsiComment>,
    ): List<CommentBlock> {
        if (comments.isEmpty()) return emptyList()

        val sorted = comments.sortedBy { it.textRange.startOffset }
        val blocks = mutableListOf<CommentBlock>()
        var currentGroup = mutableListOf<PsiComment>()

        fun flushGroup() {
            if (currentGroup.isEmpty()) return
            val block = buildBlockFromGroup(editor, currentGroup)
            if (block != null) {
                blocks += block
            }
            currentGroup = mutableListOf()
        }

        for (comment in sorted) {
            if (isDocComment(comment.text)) {
                flushGroup()
                continue
            }

            if (currentGroup.isEmpty()) {
                currentGroup += comment
                continue
            }

            val previous = currentGroup.last()
            if (shouldGroupAdjacentSingleLineComments(editor, previous, comment)) {
                currentGroup += comment
            } else {
                flushGroup()
                currentGroup += comment
            }
        }
        flushGroup()
        return blocks
    }

    /** Builds a renderable block from one comment or a grouped run of line comments. */
    private fun buildBlockFromGroup(
        editor: Editor,
        comments: List<PsiComment>,
    ): CommentBlock? {
        val first = comments.firstOrNull() ?: return null
        val last = comments.lastOrNull() ?: return null
        if (first.textRange.length <= 0 || last.textRange.length <= 0) return null

        val normalizedLines = comments.map { MarkdownCommentNormalizer.normalize(it.text) }
        val markdown = trimOuterBlankLines(normalizedLines.joinToString("\n"))
        if (markdown.isBlank()) return null
        val startLine = editor.document.getLineNumber(first.textRange.startOffset)

        return CommentBlock(
            startOffset = first.textRange.startOffset,
            endOffset = last.textRange.endOffset,
            markdown = markdown,
            startLine = startLine,
            indentColumns = indentColumns(editor, first.textRange.startOffset),
        )
    }

    /** Returns true when two comments form adjacent single-line comment rows in source. */
    private fun shouldGroupAdjacentSingleLineComments(
        editor: Editor,
        previous: PsiComment,
        next: PsiComment,
    ): Boolean {
        if (!isSingleLineComment(editor, previous) || !isSingleLineComment(editor, next)) return false

        val previousEndLine = editor.document.getLineNumber(previous.textRange.endOffset - 1)
        val nextStartLine = editor.document.getLineNumber(next.textRange.startOffset)
        if (nextStartLine != previousEndLine + 1) return false

        val betweenStart = previous.textRange.endOffset
        val betweenEnd = next.textRange.startOffset
        if (betweenEnd < betweenStart) return false
        if (betweenEnd == betweenStart) return true

        val between = editor.document.charsSequence.subSequence(betweenStart, betweenEnd)
        return between.all { it == ' ' || it == '\t' || it == '\n' || it == '\r' }
    }

    /** Returns true when comment occupies exactly one document line. */
    private fun isSingleLineComment(
        editor: Editor,
        comment: PsiComment,
    ): Boolean {
        if (comment.textRange.length <= 0) return false
        val startLine = editor.document.getLineNumber(comment.textRange.startOffset)
        val endLine = editor.document.getLineNumber(comment.textRange.endOffset - 1)
        return startLine == endLine
    }

    private fun trimOuterBlankLines(markdown: String): String =
        markdown
            .lines()
            .dropWhile(String::isBlank)
            .dropLastWhile(String::isBlank)
            .joinToString("\n")

    /** Computes visual indentation width from line start to comment start. */
    private fun indentColumns(
        editor: Editor,
        commentStartOffset: Int,
    ): Int {
        val line = editor.document.getLineNumber(commentStartOffset)
        val lineStart = editor.document.getLineStartOffset(line)
        val commentColumn = editor.offsetToVisualPosition(commentStartOffset).column
        val lineStartColumn = editor.offsetToVisualPosition(lineStart).column
        return (commentColumn - lineStartColumn).coerceAtLeast(0)
    }

    private fun concealedCommentTextAttributes(editor: Editor): TextAttributes {
        val background = editor.colorsScheme.defaultBackground
        return TextAttributes(background, background, null, null, 0)
    }

    private data class CommentBlock(
        val startOffset: Int,
        val endOffset: Int,
        val markdown: String,
        val startLine: Int,
        val indentColumns: Int,
    )

    private class CommentModeToggleGutterIconRenderer(
        private val editor: Editor,
        private val project: Project,
        private val commentStartOffset: Int,
        private val inRawMode: Boolean,
    ) : GutterIconRenderer() {
        override fun getIcon(): Icon = toggleIcon

        override fun isNavigateAction(): Boolean = true

        override fun getTooltipText(): String =
            if (inRawMode) {
                "Switch comment to Markdown preview mode"
            } else {
                "Switch comment to raw text mode"
            }

        override fun getClickAction(): AnAction =
            object : AnAction() {
                override fun actionPerformed(event: AnActionEvent) {
                    setRawMode(editor, commentStartOffset, rawMode = !inRawMode)
                    refresh(editor, project)
                }
            }

        override fun equals(other: Any?): Boolean =
            other is CommentModeToggleGutterIconRenderer &&
                other.editor == editor &&
                other.commentStartOffset == commentStartOffset &&
                other.inRawMode == inRawMode

        override fun hashCode(): Int = 31 * commentStartOffset + inRawMode.hashCode()
    }
}
