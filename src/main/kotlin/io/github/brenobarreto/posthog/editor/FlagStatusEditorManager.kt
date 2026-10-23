package io.github.brenobarreto.posthog.editor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import io.github.brenobarreto.posthog.detect.ScanLanguage
import com.intellij.openapi.project.Project

/** Keeps one [FlagStatusController] per editor that shows a file the scanner understands. */
@Service(Service.Level.PROJECT)
internal class FlagStatusEditorManager(private val project: Project) : Disposable {
    private val controllers = HashMap<Editor, FlagStatusController>() // EDT only

    fun attach(editor: Editor) {
        if (editor.editorKind != EditorKind.MAIN_EDITOR && editor.editorKind != EditorKind.UNTYPED) return
        if (controllers.containsKey(editor)) return
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val language = ScanLanguage.forFileName(file.name) ?: return
        val controller = FlagStatusController(project, editor, language)
        Disposer.register(this, controller)
        controllers[editor] = controller
    }

    fun detach(editor: Editor) {
        controllers.remove(editor)?.let(Disposer::dispose)
    }

    override fun dispose() {
        controllers.clear()
    }
}

class PostHogEditorFactoryListener : EditorFactoryListener {
    override fun editorCreated(event: EditorFactoryEvent) {
        val project = event.editor.project?.takeUnless { it.isDisposed } ?: return
        project.service<FlagStatusEditorManager>().attach(event.editor)
    }

    override fun editorReleased(event: EditorFactoryEvent) {
        val project = event.editor.project?.takeUnless { it.isDisposed } ?: return
        project.getServiceIfCreated(FlagStatusEditorManager::class.java)?.detach(event.editor)
    }
}
