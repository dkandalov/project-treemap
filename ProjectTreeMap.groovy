import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.*
import http.SimpleHttpServer

import java.util.regex.Matcher

import static http.Util.restartHttpServer
import static intellijeval.PluginUtil.*

/**
 * What could be improved:
 *  - !!! add listener to VirtualFileManager and track all changes to keep treemap up-to-date
 *
 *  - open treemap based on currently selected item in project view or currently open file
 *
 *  - packages and classes should look differently in tree map (different color schemes? bold/bigger font for packages?)
 *  - clickable breadcrumbs (e.g. to quickly navigate several methods up)
 *  - reduce breadcrumbs font size when it doesn't fit on screen?
 *  - break up classes into methods?
 */
class ProjectTreeMap {

	static initActions(String pluginPath) {
		registerAction("ProjectTreeMap-Show", "alt T") { AnActionEvent actionEvent ->
			def project = actionEvent.project

			Map<Project, Container> treeMapsToProject = getGlobalVar("treeMapsToProject", new WeakHashMap<Project, Container>())
			def thisProjectTreeMap = { treeMapsToProject.get(project) } // TODO doesn't work after plugin reload

			def showTreeMapInBrowser_SelfContained = {
				whenTreeMapRootInitialized(project, thisProjectTreeMap.call()) { Container treeMap ->
					treeMapsToProject.put(project, treeMap)
					SimpleHttpServer server = loadIntoHttpServer(project.name, pluginPath + "/http", treeMap.wholeTreeToJSON())
					BrowserUtil.launchBrowser("http://localhost:${server.port}/treemap.html")
				}
			}

			JBPopupFactory.instance.createActionGroupPopup(
					"Project TreeMap View",
					new DefaultActionGroup().with {
						add(new AnAction("Show in Browser") {
							@Override void actionPerformed(AnActionEvent event) {
								showTreeMapInBrowser_SelfContained()
							}
						})
						add(new AnAction("Recalculate and Show in Browser") {
							@Override void actionPerformed(AnActionEvent event) {
								treeMapsToProject.remove(project)
								showTreeMapInBrowser_SelfContained()
							}
							@Override void update(AnActionEvent event) { event.presentation.enabled = (thisProjectTreeMap() != null) }
						})
						add(new AnAction("Remove From Cache") {
							@Override void actionPerformed(AnActionEvent event) { treeMapsToProject.remove(project) }
							@Override void update(AnActionEvent event) { event.presentation.enabled = (thisProjectTreeMap() != null) }
						})
						add(new AnAction("Save to file as json") {
							@Override void actionPerformed(AnActionEvent event) {
								new File("treemap.json").write(thisProjectTreeMap.call().wholeTreeToJSON())
								show("saved")
							}
							@Override void update(AnActionEvent event) { event.presentation.enabled = (thisProjectTreeMap() != null) }
						})
						it
					},
					actionEvent.dataContext,
					JBPopupFactory.ActionSelectionAid.SPEEDSEARCH,
					true
			).showCenteredInCurrentWindow(project)
		}
		log("Registered ProjectTreeMap actions")
	}

	private static whenTreeMapRootInitialized(Project project, Container treeMap, Closure callback) {
		if (treeMap != null) return callback.call(treeMap)

		doInBackground("Building tree map index for project...", {
		  log("Started building treemap for ${project}")
			try {
				treeMap = new PackageAndClassTreeBuilder(project).buildTree()
			} catch (ProcessCanceledException ignored) {
			} catch (Exception e) {
				log(e)
				showInConsole(e, project)
			} finally {
				log("Finished building treemap for ${project}")
			}
		}, { callback.call(treeMap) })
	}

	private static SimpleHttpServer loadIntoHttpServer(String projectId, String pathToHttpFiles, String json) {
		def tempDir = FileUtil.createTempDirectory(projectId + "_", "_treemap")
		FileUtil.copyDirContent(new File(pathToHttpFiles), tempDir)
		fillTemplate("$pathToHttpFiles/treemap_template.html", json, tempDir.absolutePath + "/treemap.html")

		log("Saved tree map into: " + tempDir.absolutePath)

		restartHttpServer(projectId, tempDir.absolutePath, {null}, {log(it)})
	}

	private static void fillTemplate(String template, String jsValue, String pathToNewFile) {
		def templateText = new File(template).readLines().join("\n")
		def text = templateText.replaceFirst(/(?s)\/\*data_placeholder\*\/.*\/\*data_placeholder\*\//, Matcher.quoteReplacement(jsValue))
		new File(pathToNewFile).write(text)
	}

	static class PackageAndClassTreeBuilder {
		private final Project project

		PackageAndClassTreeBuilder(Project project) {
			this.project = project
		}

		public Container buildTree() {
			def topLevelContainers = sourceRootsIn(project).collect { root ->
				String rootName = runReadAction{ (root.parent == null ? "" : root.parent.name) + "/" + root.name }
				convertToContainerHierarchy(root).withName(rootName)
			}
			new Container("", topLevelContainers)
		}

		private static Collection<PsiDirectory> sourceRootsIn(Project project) {
			runReadAction {
				def psiManager = PsiManager.getInstance(project)
				ProjectRootManager.getInstance(project).contentSourceRoots.collect{ psiManager.findDirectory(it) }
			}
		}

		@Optimization private static Container convertToContainerHierarchy(PsiDirectory directory) {
			def childContainers = []

			def subDirectories = runReadAction {
				JavaDirectoryService.instance.getClasses(directory).each{ childContainers.add(convertToElement(it)) }
				directory.subdirectories
			}
			subDirectories.each{ childContainers.add(convertToContainerHierarchy(it as PsiDirectory)) }

			new Container(directory.name, childContainers)
		}

		private static Container convertToElement(PsiClass psiClass) { new Container(psiClass.name, sizeOf(psiClass)) }

		private static sizeOf(PsiClass psiClass) {
			if (psiClass.containingFile instanceof PsiJavaFile)
				return new JavaClassEstimator().sizeOf(psiClass)
			else
				return new GenericClassEstimator().sizeOf(psiClass)
		}
	}

	/**
	 * Estimator for other languages (e.g. Groovy) which extend PsiClass even though it's psi for "java class"
	 */
	static class GenericClassEstimator {
		int sizeOf(PsiClass psiClass) {
			// TODO
			if (psiClass.text == null) return 1
			psiClass.text.split("\n").findAll{ it.trim() != "" }.size()
		}
	}

	static class JavaClassEstimator {
		int sizeOf(PsiClass psiClass) {
			if (!psiClass.containingFile instanceof PsiJavaFile)
				throw new IllegalArgumentException("$psiClass is not a Java class")

			int amountOfStatements = 0
			psiClass.accept(new PsiRecursiveElementVisitor() {
				@Override void visitElement(PsiElement element) {
					if (element instanceof PsiStatement) amountOfStatements++
					super.visitElement(element)
				}
			})

			1 + // this is to account for class declaration
			amountOfStatements +
			psiClass.fields.size() +
			psiClass.initializers.size() +
			sum(psiClass.constructors, { sizeOfMethodDeclaration((PsiMethod) it) }) +
			sum(psiClass.methods, { sizeOfMethodDeclaration((PsiMethod) it) }) +
			sum(psiClass.innerClasses, { sizeOf((PsiClass) it) })
		}

		@Optimization private static int sum(Object[] array, Closure closure) {
			int sum = 0
			for (object in array) sum += closure(object)
			sum
		}

		private static int sizeOfMethodDeclaration(PsiMethod psiMethod) { 1 + psiMethod.parameterList.parametersCount }
	}

	static class Container {
		final String name
		final Collection<Container> children
		final int size
		Container parent = null

		Container(String name, Collection<Container> children) {
			this(name, children, sumOfChildrenSizes(children))
		}

		Container(String name, Collection<Container> children = new ArrayList(), int size) {
			this.name = name
			this.children = children.findAll{ it.size > 0 }
			this.size = size

			for (child in this.children) child.parent = this
		}

		Container withName(String newName) {
			new Container(newName, children, size)
		}

		@Optimization static int sumOfChildrenSizes(Collection<Container> children) {
			int sum = 0
			for (Container child in children) sum += child.size
			sum
		}

		private String getFullName() {
			if (parent == null) name
			else parent.fullName + "." + name
		}

		String wholeTreeToJSON() {
			String childrenAsJSON = "\"children\": [\n" + children.collect { it.wholeTreeToJSON() }.join(',\n') + "]"

			"{" +
			"\"name\": \"$name\", " +
			"\"size\": \"$size\", " +
			childrenAsJSON +
			"}"
		}

		String toJSON(int maxDepth = 1, int level = 0) {
			String childrenAsJSON
			String jsonName
			if (level < maxDepth) {
				childrenAsJSON = "\"children\": [\n" + children.collect { it.toJSON(maxDepth, level + 1) }.join(',\n') + "]"
				jsonName = fullName
			} else {
				childrenAsJSON = "\"hasChildren\": " + (children.size() > 0 ? "true" : "false")
				jsonName = name
			}

			"{" +
			"\"name\": \"$jsonName\", " +
			"\"size\": \"$size\", " +
			childrenAsJSON +
			"}"
		}
	}
}

/**
 * Marks optimized groovy code (e.g. don't convert between arrays and collections)
 * (As a benchmark was used IntelliJ source code; takes ~1.5min)
 */
@interface Optimization {}