import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.*
import http.SimpleHttpServer

import javax.swing.*

import static http.Util.restartHttpServer
import static intellijeval.PluginUtil.*

/**
 * What could be improved:
 *  - open treemap based on currently selected item in project view or currently open file
 *  - ask whether to recalculate treemap (better calculate if it's null; have separate action to recalculate it)
 *
 *  - popup hints for small rectangles in treemap (otherwise it's impossible to read package/class name)
 *  - make sure it works with multiple projects (per-project treemap cache)
 *  - packages and classes should look differently in tree map (different color schemes? bold/bigger font for packages?)
 *  - clickable breadcrumbs (e.g. to quickly navigate several methods up)
 *  - reduce breadcrumbs font size when it doesn't fit on screen?
 *  - break up classes into methods?
 */
class ProjectTreeMap {
	private static final Logger LOG = Logger.getInstance(ProjectTreeMap.class);

	// TODO this should be a container per project
	// TODO must be cached like http server is cached (otherwise it's not really cached)
	private static Container treeMapRoot = null // TODO will it be GCed on plugin reload?

	static initActions(String pluginPath) {
		registerAction("ProjectTreeMap-Show", "alt T") { AnActionEvent event ->
			ensureRootContainerInitialized(event.project) {
				SimpleHttpServer server = restartHttpServer(pluginPath)
				BrowserUtil.launchBrowser("http://localhost:${server.port}/treemap.html")
			}
		}
		registerAction("ProjectTreeMap-RecalculateTree", "alt R") { AnActionEvent event ->
			treeMapRoot = null
			ensureRootContainerInitialized(event.project) {
				show("Recalculated project tree map")
			}
		}
		show("Registered ProjectTreeMap actions")
	}

	private static ensureRootContainerInitialized(Project project, Closure closure) {
		if (treeMapRoot != null) return closure.call()

		doInBackground("Building tree map index for project...", {
				ApplicationManager.application.runReadAction {
					try {
						treeMapRoot = new PackageAndClassTreeBuilder(project).buildTree()
					} catch (Exception e) {
						showInConsole(e, project)
					}
				}
		}, closure)
	}

	private static  SimpleHttpServer restartHttpServer(String pluginPath) {
		def server = restartHttpServer("ProjectTreeMap_HttpServer", pluginPath,
				{ String requestURI -> new RequestHandler(treeMapRoot).handleRequest(requestURI) },
				{ Exception e -> SwingUtilities.invokeLater { LOG.error("", e) } }
		)
		server
	}

	static class RequestHandler {
		private final boolean skipEmptyMiddlePackages
		private final Container rootContainer

		RequestHandler(Container rootContainer, boolean skipEmptyMiddlePackages = true) {
			this.rootContainer = rootContainer
			this.skipEmptyMiddlePackages = skipEmptyMiddlePackages
		}

		String handleRequest(String requestURI) {
			if (!requestURI.endsWith(".json")) return

			def containerRequest = requestURI.replace(".json", "").replaceFirst("/", "")

			Container container
			if (containerRequest == "") {
				container = rootContainer
			} else if (containerRequest.startsWith("parent-of/")) {
				containerRequest = containerRequest.replaceFirst("parent-of/", "")
				List<String> path = splitName(containerRequest)
				container = findContainer(path, rootContainer).parent

				if (skipEmptyMiddlePackages) {
					while (container != null && container != rootContainer && container.children.size() == 1)
						container = container.parent
				}
				if (container == null) container = rootContainer
			} else {
				List<String> path = splitName(containerRequest)
				container = findContainer(path, rootContainer)
				if (skipEmptyMiddlePackages) {
					while (container.children.size() == 1 && container.children.first().children.size() > 0)
						container = container.children.first()
				}
			}
			container?.toJSON()
		}

		private static List<String> splitName(String containerFullName) {
			def namesList = containerFullName.split(/\./).toList()
			if (namesList.size() > 0 && namesList.first() != "") {
				// this is because for "" split returns [""] but for "foo" returns ["foo"], i.e. list without first ""
				namesList.add(0, "")
			}
			namesList
		}

		private static Container findContainer(List path, Container container) {
			if (container == null || path.empty || path.first() != container.name) return null
			if (path.size() == 1 && path.first() == container.name) return container

			for (child in container.children) {
				def result = findContainer(path.tail(), child)
				if (result != null) return result
			}
			null
		}
	}

	static class PackageAndClassTreeBuilder {
		private final Project project

		PackageAndClassTreeBuilder(Project project) {
			this.project = project
		}

		public Container buildTree() {
			def topLevelContainers = sourceRootsIn(project).collect {
				convertToContainerHierarchy(it).withName(it.parent.name + "/" + it.name)
			}
			new Container("", topLevelContainers)
		}

		private static Collection<PsiDirectory> sourceRootsIn(Project project) {
			def psiManager = PsiManager.getInstance(project)
			ProjectRootManager.getInstance(project).contentSourceRoots.collect{ psiManager.findDirectory(it) }
		}

		private static def convertToContainerHierarchy(PsiDirectory directory) {
			def directoryService = JavaDirectoryService.instance

			def classes = { directoryService.getClasses(directory).collect{ convertToElement(it) } }
			def packages = { directory.children.findAll{it instanceof PsiDirectory}.collect{ convertToContainerHierarchy(it) } }

			new Container(directory.name, classes() + packages())
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
			psiClass.constructors.sum(0){ sizeOfMethodDeclaration((PsiMethod) it) } +
			psiClass.methods.sum(0){ sizeOfMethodDeclaration((PsiMethod) it) } +
			psiClass.innerClasses.sum(0){ sizeOf((PsiClass) it) }
		}

		private static int sizeOfMethodDeclaration(PsiMethod psiMethod) { 1 + psiMethod.parameterList.parametersCount }
	}

	static class Container {
		final String name
		final Container[] children
		final int size
		private Container parent = null

		Container(String name, Collection<Container> children) {
			this(name, (Container[]) children.toArray(), sumOfChildrenSizes(children))
		}

		Container(String name, Container[] children = new Container[0], int size) {
			this.name = name
			this.children = children.findAll{ it.size > 0 }
			this.size = size

			for (child in this.children) child.parent = this
		}

		Container withName(String newName) {
			new Container(newName, children, size)
		}

		static int sumOfChildrenSizes(Collection<Container> children) {
			// this is an attempt to optimize groovy by not using .sum(Closure) method
			int sum = 0
			for (Container child in children) sum += child.size
			sum
		}

		Container getParent() {
			this.parent
		}

		private String getFullName() {
			if (parent == null) name
			else parent.fullName + "." + name
		}

		String toJSON(int level = 0) {
			String childrenAsJSON
			String jsonName
			if (level == 0) {
				childrenAsJSON = "\"children\": [\n" + children.collect { it.toJSON(level + 1) }.join(',\n') + "]"
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
