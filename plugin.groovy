import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager

import static ProjectTreeMap.JavaClassEstimator
import static liveplugin.PluginUtil.show

ProjectTreeMap.initActions(pluginPath)
if (!isIdeStartup) show("reloaded")

