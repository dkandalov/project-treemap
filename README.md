What is this?
=============

This is a micro-plugin IntelliJ (runs inside [intellij-eval](https://github.com/dkandalov/intellij_eval) plugin).

It shows treemap for project using [d3.js](http://d3js.org/) (requires browser with SVG support).

(It's implemented for Java but should be easy to extend for other languages supported by IntelliJ.)

Treemap view of [IntelliJ community edition](https://github.com/JetBrains/intellij-community) at the project root.
Numbers below package names show estimated size of all classes it contains (see below for definition of "class size").
<img src="https://github.com/dkandalov/project-treemap/blob/master/screenshots/intellij-treemap.png?raw=true" alt="auto-revert screenshot" title="auto-revert screenshot" align="left" />

Treemap view for "com.intellij" package under "platform-impl" source root.
<img src="https://github.com/dkandalov/project-treemap/blob/master/screenshots/intellij-treemap2.png?raw=true" alt="auto-revert screenshot" title="auto-revert screenshot" align="left" />


Why?
====
 - because normal tree structure doesn't show how big things are relative to each other
 - I've never been sure that generic complexity metrics make perfect sense for every project
 (e.g. [Cyclomatic complexity](http://en.wikipedia.org/wiki/Cyclomatic_complexity)).
 Therefore, simplistic complexity estimation that should be easy to change.
This is how it's implemented in ProjectTreeMap.groovy (you can change this code and reload plugin):
```groovy
static class JavaClassEstimator {
	int sizeOf(PsiClass psiClass) {
		if (!psiClass.containingFile instanceof PsiJavaFile)
			throw new IllegalArgumentException("$psiClass is not a Java class")

		int statementCount = 0
		psiClass.accept(new PsiRecursiveElementVisitor() {
			@Override void visitElement(PsiElement element) {
				if (element instanceof PsiStatement) statementCount++
				super.visitElement(element)
			}
		})

		1 + // this is to account for class declaration
		statementCount +
		psiClass.fields.size() +
		psiClass.initializers.size() +
		psiClass.constructors.sum(0){ sizeOfMethod((PsiMethod) it) } +
		psiClass.methods.sum(0){ sizeOfMethod((PsiMethod) it) } +
		psiClass.innerClasses.sum(0){ sizeOf((PsiClass) it) }
	}

	private static int sizeOfMethod(PsiMethod psiMethod) { 1 + psiMethod.parameterList.parametersCount }
}
```


How to use?
===========
 - install [intellij-eval](https://github.com/dkandalov/intellij_eval)
 - add and run this plugin (Plugins Tool Window -> Add -> Plugin from Git)
 - use alt+T to build/open treemap for current project
 - (in browser) click to go one level down, ctrl+click to go one level up
