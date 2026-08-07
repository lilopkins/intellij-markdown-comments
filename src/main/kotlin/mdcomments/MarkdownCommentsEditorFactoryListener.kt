package uk.hpkns.mdcomments

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.Alarm

class MarkdownCommentsEditorFactoryListener : EditorFactoryListener {
    private val listenerDisposableKey = Key.create<Disposable>("markdown.comments.listener.disposable")

    /** Installs listeners and schedules initial Markdown comment rendering for a new editor. */
    override fun editorCreated(event: EditorFactoryEvent) {
        val editor = event.editor
        if (editor.isViewer || editor.isDisposed) return

        val project = editor.project ?: MarkdownCommentPresentationManager.findProject(editor) ?: return
        val disposable = Disposer.newDisposable("markdown-comments-${editor.hashCode()}")
        val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, disposable)

        fun scheduleRefresh() {
            alarm.cancelAllRequests()
            alarm.addRequest({
                PsiDocumentManager.getInstance(project).performLaterWhenAllCommitted {
                    MarkdownCommentPresentationManager.refresh(editor, project)
                }
            }, 120)
        }

        editor.document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    scheduleRefresh()
                }
            },
            disposable,
        )
        MarkdownCommentPresentationManager.installPresentationListeners(editor, project, disposable)

        editor.putUserData(listenerDisposableKey, disposable)
        scheduleRefresh()
    }

    /** Disposes editor-scoped resources and removes managed Markdown presentation. */
    override fun editorReleased(event: EditorFactoryEvent) {
        val editor = event.editor
        editor.getUserData(listenerDisposableKey)?.let {
            Disposer.dispose(it)
            editor.putUserData(listenerDisposableKey, null)
        }
        MarkdownCommentPresentationManager.clear(editor)
    }
}
