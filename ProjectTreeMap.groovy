import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.progress.PerformInBackgroundOption
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.io.FileUtil
import com.intellij.psi.*

import java.util.regex.Matcher

import static http.Util.restartHttpServer
import static liveplugin.PluginUtil.*
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

			def showTreeMapInBrowser = {
				whenTreeMapRootInitialized(project, thisProjectTreeMap.call()) { Container treeMap ->
					treeMapsToProject.put(project, treeMap)

					def json = treeMap.wholeTreeToJSON()
					def pathToHttpFiles = pluginPath + "/http"
					def tempDir = FileUtil.createTempDirectory(project.name + "_", "_treemap")
					FileUtil.copyDirContent(new File(pathToHttpFiles), tempDir)
					fillTemplate("$pathToHttpFiles/treemap_template.html", json, tempDir.absolutePath + "/treemap.html")
					log("Saved tree map into: " + tempDir.absolutePath)

					def server = restartHttpServer(project.name, tempDir.absolutePath, {null}, {log(it)})

					BrowserUtil.browse("http://localhost:${server.port}/treemap.html")
				}
			}

			JBPopupFactory.instance.createActionGroupPopup(
					"Project TreeMap View",
					new DefaultActionGroup().with {
						add(new AnAction("Show in Browser") {
							@Override void actionPerformed(AnActionEvent event) {
								showTreeMapInBrowser()
							}
						})
						add(new AnAction("Recalculate and Show in Browser") {
							@Override void actionPerformed(AnActionEvent event) {
								treeMapsToProject.remove(project)
								showTreeMapInBrowser()
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

	private static void fillTemplate(String template, String jsValue, String pathToNewFile) {
		def templateText = new File(template).readLines().join("\n")
		def text = templateText.replaceFirst(/(?s)\/\*data_placeholder\*\/.*\/\*data_placeholder\*\//, Matcher.quoteReplacement(jsValue))
		new File(pathToNewFile).write(text)
	}


	private static whenTreeMapRootInitialized(Project project, Container treeMap, Closure callback) {
		if (treeMap != null) return callback.call(treeMap)

		doInBackground("Building tree map index for project...", false, PerformInBackgroundOption.ALWAYS_BACKGROUND, {
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
		}, {}, { callback.call(treeMap) })
	}

	static class PackageAndClassTreeBuilder {
		private final Project project

		PackageAndClassTreeBuilder(Project project) {
			this.project = project
		}

		public Container buildTree() {
			def topLevelContainers = PackageAndClassTreeBuilder.sourceRootsIn(project).collect { root ->
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
				JavaDirectoryService.instance.getClasses(directory).each {
					def metrics = [
							size: sizeOf(it),
							amountOfMethods: new JavaClassMethodCounter().sizeOf(it),
							amountOfFields: new JavaClassFieldCounter().sizeOf(it)
					]
					childContainers.add(new Container(it.name, metrics))
				}
				directory.subdirectories
			}
			subDirectories.each{ childContainers.add(convertToContainerHierarchy(it as PsiDirectory)) }

			new Container(directory.name, childContainers)
		}

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

	static class JavaClassMethodCounter {
		int sizeOf(PsiClass psiClass) {
			if (!psiClass.containingFile instanceof PsiJavaFile)
				throw new IllegalArgumentException("$psiClass is not a Java class")

			psiClass.initializers.size() +
			psiClass.constructors.size() +
			psiClass.methods.size() +
			sum(psiClass.innerClasses, { sizeOf((PsiClass) it) })
		}

		@Optimization private static int sum(Object[] array, Closure closure) {
			int sum = 0
			for (object in array) sum += closure(object)
			sum
		}
	}

	static class JavaClassFieldCounter {
		int sizeOf(PsiClass psiClass) {
			if (!psiClass.containingFile instanceof PsiJavaFile)
				throw new IllegalArgumentException("$psiClass is not a Java class")

			psiClass.fields.size() +
			sum(psiClass.innerClasses, { sizeOf((PsiClass) it) })
		}

		@Optimization private static int sum(Object[] array, Closure closure) {
			int sum = 0
			for (object in array) sum += closure(object)
			sum
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
		private final String name
		private final Collection<Container> children
		private final Map<String, Integer> metrics = [:].withDefault{0}
		private Container parent = null

		Container(String name, Collection<Container> children) {
			this(name, children, sumOfChildrenMetrics(children))
		}

		Container(String name, Collection<Container> children = new ArrayList(), Map<String, Integer> metrics) {
			this.name = name
			this.children = children.findAll{ it.metrics.size() > 0 }
			this.children.each { child -> child.parent = this }
			this.metrics = metrics
		}

		Container withName(String newName) {
			new Container(newName, children, metrics)
		}

		static Map<String, Integer> sumOfChildrenMetrics(Collection<Container> children) {
			Map<String, Integer> result = [:].withDefault{0}
			for (Container child in children) {
				child.metrics.entrySet().each {
					result[it.key] += it.value
				}
			}
			result
		}

		private String getFullName() {
			if (parent == null) name
			else parent.fullName + "." + name
		}

		String wholeTreeToJSON() {
			String childrenAsJson = "\"children\": [\n" + children.collect { it.wholeTreeToJSON() }.join(',\n') + "]"
			String metricsAsJson = metrics.collect{ "\"${it.key}\": ${it.value}" }.join(", \n")

			"{" +
				"\"name\": \"$name\", \n" +
				metricsAsJson + ", \n" +
				childrenAsJson +
			"}"
		}
	}
}

/**
 * Marks optimized groovy code (e.g. don't convert between arrays and collections)
 * (As a benchmark was used IntelliJ source code; takes ~1.5min)
 */
@interface Optimization {}