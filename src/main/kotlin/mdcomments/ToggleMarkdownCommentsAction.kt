package uk.hpkns.mdcomments

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware

class ToggleMarkdownCommentsAction :
    ToggleAction(),
    DumbAware {
    /** Reflects whether Markdown comment rendering is globally enabled. */
    override fun isSelected(event: AnActionEvent): Boolean = service<MarkdownCommentsSettings>().enabled

    /** Persists the toggle state and refreshes open editors. */
    override fun setSelected(
        event: AnActionEvent,
        state: Boolean,
    ) {
        service<MarkdownCommentsSettings>().enabled = state
        MarkdownCommentPresentationManager.refreshAllOpenEditors(event.project)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}
