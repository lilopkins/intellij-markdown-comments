package uk.hpkns.mdcomments

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.FoldRegion
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.ex.FoldingModelEx
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectLocator
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiTreeUtil

object MarkdownCommentPresentationManager {
    private val inlaysKey = Key.create<MutableList<Inlay<*>>>("markdown.comments.inlays")
    private val foldsKey = Key.create<MutableList<FoldRegion>>("markdown.comments.folds")
    private val presentationsKey = Key.create<MutableList<CommentPresentation>>("markdown.comments.presentations")
    private val blocksKey = Key.create<MutableList<CommentBlock>>("markdown.comments.blocks")
    private val concealedRawKey = Key.create<MutableList<RangeHighlighter>>("markdown.comments.concealed.raw")
    private val editModeOffsetKey = Key.create<Int>("markdown.comments.edit.offset")

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
        val presentations = mutableListOf<CommentPresentation>()
        val concealedRaw = mutableListOf<RangeHighlighter>()
        val comments = PsiTreeUtil.findChildrenOfType(psiFile, PsiComment::class.java).toList()
        val blocks = buildCommentBlocks(editor, comments)
        editor.putUserData(blocksKey, blocks.toMutableList())

        val documentText = editor.document.charsSequence
        val activeEditOffset = editor.getUserData(editModeOffsetKey)
        var activeEditStillExists = false
        editor.foldingModel.runBatchFoldingOperation {
            for (block in blocks) {
                val displayEligible = isDisplayEligible(documentText, block)
                if (!displayEligible) continue
                if (activeEditOffset == block.startOffset) {
                    activeEditStillExists = true
                    continue
                }

                renderBlock(
                    editor = editor,
                    block = block,
                    documentText = documentText,
                    inlays = inlays,
                    folds = folds,
                    presentations = presentations,
                    concealedRaw = concealedRaw,
                )
            }
        }

        if (activeEditOffset != null && !activeEditStillExists) {
            editor.putUserData(editModeOffsetKey, null)
        }

        editor.putUserData(inlaysKey, inlays)
        editor.putUserData(foldsKey, folds)
        editor.putUserData(presentationsKey, presentations)
        editor.putUserData(concealedRawKey, concealedRaw)
    }

    /** Removes all Markdown inlays and fold regions previously managed by this plugin. */
    fun clear(editor: Editor) {
        clearManagedPresentation(editor)
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

    private fun isDisplayEligible(
        documentText: CharSequence,
        block: CommentBlock,
    ): Boolean =
        MarkdownDisplayModeLayout.isDisplayEligible(
            documentText = documentText,
            startOffset = block.startOffset,
            endOffset = block.endOffset,
        )

    private fun renderBlock(
        editor: Editor,
        block: CommentBlock,
        documentText: CharSequence,
        inlays: MutableList<Inlay<*>>,
        folds: MutableList<FoldRegion>,
        presentations: MutableList<CommentPresentation>,
        concealedRaw: MutableList<RangeHighlighter>,
    ) {
        val collapseEndOffset =
            MarkdownDisplayModeLayout.collapseEndOffset(
                documentText = documentText,
                startOffset = block.startOffset,
                endOffset = block.endOffset,
            )
        val collapseStartOffset =
            MarkdownDisplayModeLayout.collapseStartOffset(
                documentText = documentText,
                startOffset = block.startOffset,
                endOffset = block.endOffset,
            )

        // Collapse may include trailing spaces/newline for standalone comment blocks so
        // source rows do not occupy layout space in display mode.
        val inlay =
            editor.inlayModel.addBlockElement(
                collapseEndOffset,
                true,
                true,
                0,
                MarkdownCommentRenderer(
                    markdown = block.markdown,
                    indentLine = block.startLine,
                    indentColumns = block.indentColumns,
                ),
            ) ?: return

        val foldRegion = createFoldRegion(editor, collapseStartOffset, collapseEndOffset)
        if (foldRegion == null) {
            concealedRaw +=
                editor.markupModel.addRangeHighlighter(
                    block.startOffset,
                    collapseEndOffset,
                    HighlighterLayer.ADDITIONAL_SYNTAX,
                    concealedCommentTextAttributes(editor),
                    HighlighterTargetArea.EXACT_RANGE,
                )
        } else {
            foldRegion.isExpanded = false
            folds += foldRegion
        }

        inlays += inlay
        presentations += CommentPresentation(block.startOffset, block.endOffset, inlay)
    }

    private fun createFoldRegion(
        editor: Editor,
        startOffset: Int,
        endOffset: Int,
    ): FoldRegion? =
        when (val foldingModel = editor.foldingModel) {
            is FoldingModelEx -> foldingModel.createFoldRegion(startOffset, endOffset, "", null, true)
            else -> foldingModel.addFoldRegion(startOffset, endOffset, "")
        }

    /** Returns true when two comments form adjacent single-line comment rows in source. */
    private fun shouldGroupAdjacentSingleLineComments(
        editor: Editor,
        previous: PsiComment,
        next: PsiComment,
    ): Boolean {
        if (!isSingleLineComment(editor, previous) || !isSingleLineComment(editor, next)) return false
        val documentText = editor.document.charsSequence
        if (
            !MarkdownDisplayModeLayout.isDisplayEligible(
                documentText,
                previous.textRange.startOffset,
                previous.textRange.endOffset,
            )
        ) {
            return false
        }
        if (
            !MarkdownDisplayModeLayout.isDisplayEligible(
                documentText,
                next.textRange.startOffset,
                next.textRange.endOffset,
            )
        ) {
            return false
        }

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

    private fun clearManagedPresentation(editor: Editor) {
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

        editor.putUserData(presentationsKey, null)
        editor.putUserData(blocksKey, null)

        editor.getUserData(concealedRawKey)?.forEach { highlighter ->
            editor.markupModel.removeHighlighter(highlighter)
        }
        editor.putUserData(concealedRawKey, null)
    }

    internal fun installPresentationListeners(
        editor: Editor,
        project: Project,
        disposable: com.intellij.openapi.Disposable,
    ) {
        editor.addEditorMouseListener(
            object : EditorMouseListener {
                override fun mouseClicked(event: EditorMouseEvent) {
                    val point = event.mouseEvent.point
                    val presentation =
                        editor
                            .getUserData(presentationsKey)
                            ?.firstOrNull { it.inlay.bounds?.contains(point) == true }
                            ?: return
                    editor.putUserData(editModeOffsetKey, presentation.startOffset)
                    editor.caretModel.moveToOffset(presentation.startOffset)
                    refresh(editor, project)
                }
            },
            disposable,
        )

        editor.caretModel.addCaretListener(
            object : CaretListener {
                override fun caretPositionChanged(event: CaretEvent) {
                    val activeEditOffset = editor.getUserData(editModeOffsetKey) ?: return
                    val activeBlock =
                        editor
                            .getUserData(blocksKey)
                            ?.firstOrNull { it.startOffset == activeEditOffset }
                            ?: run {
                                editor.putUserData(editModeOffsetKey, null)
                                refresh(editor, project)
                                return
                            }

                    val caretOffset = event.caret.offset
                    if (caretOffset in activeBlock.startOffset until activeBlock.endOffset) return

                    editor.putUserData(editModeOffsetKey, null)
                    refresh(editor, project)
                }
            },
            disposable,
        )
    }

    private data class CommentBlock(
        val startOffset: Int,
        val endOffset: Int,
        val markdown: String,
        val startLine: Int,
        val indentColumns: Int,
    )

    private data class CommentPresentation(
        val startOffset: Int,
        val endOffset: Int,
        val inlay: Inlay<*>,
    )
}
