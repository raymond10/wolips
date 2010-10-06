package org.objectstyle.wolips.core.resources.types;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaElementDelta;
import org.eclipse.jdt.core.IJavaModel;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.compiler.util.SuffixConstants;
import org.eclipse.jdt.internal.core.JarPackageFragmentRoot;
import org.eclipse.jdt.internal.core.JavaElement;
import org.eclipse.jdt.internal.core.JavaModel;
import org.eclipse.jdt.internal.core.JavaModelManager;
import org.eclipse.jdt.internal.core.JavaProject;
import org.eclipse.jdt.internal.core.hierarchy.TypeHierarchy;
import org.eclipse.jdt.internal.core.search.AbstractSearchScope;

/**
 * Scope limited to the subtype and supertype hierarchy of a given type.
 */
public class WOHierarchyScope extends AbstractSearchScope implements SuffixConstants {
	private IJavaProject javaProject;
	
	public IType focusType;
	private String focusPath;

	private ITypeHierarchy hierarchy;
	private IType[] types;
	private HashSet resourcePaths;
	private IPath[] enclosingProjectsAndJars;

	protected IResource[] elements;
	protected int elementCount;

	public boolean needsRefresh;

	/* (non-Javadoc)
	 * Adds the given resource to this search scope.
	 */
	public void add(IResource element) {
		if (this.elementCount == this.elements.length) {
			System.arraycopy(
				this.elements,
				0,
				this.elements = new IResource[this.elementCount * 2],
				0,
				this.elementCount);
		}
		this.elements[this.elementCount++] = element;
	}

	/* (non-Javadoc)
	 * Creates a new hiearchy scope for the given type.
	 */
	public WOHierarchyScope(IType type, IJavaProject javaProject) throws JavaModelException {
		this.focusType = type;
		this.javaProject = javaProject;

		this.enclosingProjectsAndJars = computeProjectsAndJars(type);

		// resource path
		IPackageFragmentRoot root = (IPackageFragmentRoot)type.getPackageFragment().getParent();
		if (root.isArchive()) {
			IPath jarPath = root.getPath();
			Object target = JavaModel.getTarget(jarPath, true);
			String zipFileName;
			if (target instanceof IFile) {
				// internal jar
				zipFileName = jarPath.toString();
			} else if (target instanceof File) {
				// external jar
				zipFileName = ((File)target).getPath();
			} else {
				return; // unknown target
			}
			this.focusPath =
				zipFileName
					+ JAR_FILE_ENTRY_SEPARATOR
					+ type.getFullyQualifiedName().replace('.', '/')
					+ SUFFIX_STRING_class;
		} else {
			this.focusPath = type.getPath().toString();
		}

		this.needsRefresh = true;

		//disabled for now as this could be expensive
		//JavaModelManager.getJavaModelManager().rememberScope(this);
	}
	private void buildResourceVector() {
		HashMap resources = new HashMap();
		HashMap paths = new HashMap();
		this.types = this.hierarchy.getAllTypes();
		for (int i = 0; i < this.types.length; i++) {
			IType type = this.types[i];
			IResource resource = ((JavaElement) type).resource();
			if (resource != null && resources.get(resource) == null) {
				resources.put(resource, resource);
				add(resource);
			}
			IPackageFragmentRoot root =
				(IPackageFragmentRoot) type.getPackageFragment().getParent();
			if (root instanceof JarPackageFragmentRoot) {
				// type in a jar
				JarPackageFragmentRoot jar = (JarPackageFragmentRoot) root;
				IPath jarPath = jar.getPath();
				Object target = JavaModel.getTarget(jarPath, true);
				String zipFileName;
				if (target instanceof IFile) {
					// internal jar
					zipFileName = jarPath.toString();
				} else if (target instanceof File) {
					// external jar
					zipFileName = ((File)target).getPath();
				} else {
					continue; // unknown target
				}
				String resourcePath =
					zipFileName
						+ JAR_FILE_ENTRY_SEPARATOR
						+ type.getFullyQualifiedName().replace('.', '/')
						+ SUFFIX_STRING_class;

				this.resourcePaths.add(resourcePath);
				paths.put(jarPath, type);
			} else {
				// type is a project
				paths.put(type.getJavaProject().getProject().getFullPath(), type);
			}
		}
		this.enclosingProjectsAndJars = new IPath[paths.size()];
		int i = 0;
		for (Iterator iter = paths.keySet().iterator(); iter.hasNext();) {
			this.enclosingProjectsAndJars[i++] = (IPath) iter.next();
		}
	}
	/*
	 * Computes the paths of projects and jars that the hierarchy on the given type could contain.
	 * This is a super set of the project and jar paths once the hierarchy is computed.
	 */
	private IPath[] computeProjectsAndJars(IType type) throws JavaModelException {
		HashSet set = new HashSet();
		IPackageFragmentRoot root = (IPackageFragmentRoot)type.getPackageFragment().getParent();
		if (root.isArchive()) {
			// add the root
			set.add(root.getPath());
			// add all projects that reference this archive and their dependents
			IPath rootPath = root.getPath();
			IJavaModel model = JavaModelManager.getJavaModelManager().getJavaModel();
			IJavaProject[] projects = model.getJavaProjects();
			HashSet visited = new HashSet();
			for (int i = 0; i < projects.length; i++) {
				JavaProject project = (JavaProject) projects[i];
				IClasspathEntry entry = project.getClasspathEntryFor(rootPath);
				if (entry != null) {
					// add the project and its binary pkg fragment roots
					IPackageFragmentRoot[] roots = project.getAllPackageFragmentRoots();
					set.add(project.getPath());
					for (int k = 0; k < roots.length; k++) {
						IPackageFragmentRoot pkgFragmentRoot = roots[k];
						if (pkgFragmentRoot.getKind() == IPackageFragmentRoot.K_BINARY) {
							set.add(pkgFragmentRoot.getPath());
						}
					}
					// add the dependent projects
					computeDependents(project, set, visited);
				}
			}
		} else {
			// add all the project's pkg fragment roots
			IJavaProject project = (IJavaProject)root.getParent();
			IPackageFragmentRoot[] roots = project.getAllPackageFragmentRoots();
			for (int i = 0; i < roots.length; i++) {
				IPackageFragmentRoot pkgFragmentRoot = roots[i];
				if (pkgFragmentRoot.getKind() == IPackageFragmentRoot.K_BINARY) {
					set.add(pkgFragmentRoot.getPath());
				} else {
					set.add(pkgFragmentRoot.getParent().getPath());
				}
			}
			// add the dependent projects
			computeDependents(project, set, new HashSet());
		}
		IPath[] result = new IPath[set.size()];
		set.toArray(result);
		return result;
	}
	private void computeDependents(IJavaProject project, HashSet set, HashSet visited) {
		if (visited.contains(project)) return;
		visited.add(project);
		IProject[] dependents = project.getProject().getReferencingProjects();
		for (int i = 0; i < dependents.length; i++) {
			try {
				IJavaProject dependent = JavaCore.create(dependents[i]);
				IPackageFragmentRoot[] roots = dependent.getPackageFragmentRoots();
				set.add(dependent.getPath());
				for (int j = 0; j < roots.length; j++) {
					IPackageFragmentRoot pkgFragmentRoot = roots[j];
					if (pkgFragmentRoot.isArchive()) {
						set.add(pkgFragmentRoot.getPath());
					}
				}
				computeDependents(dependent, set, visited);
			} catch (JavaModelException e) {
				// project is not a java project
			}
		}
	}
	/* (non-Javadoc)
	 * @see IJavaSearchScope#encloses(String)
	 */
	public boolean encloses(String resourcePath) {
		if (this.hierarchy == null) {
			if (resourcePath.equals(this.focusPath)) {
				return true;
			} else {
				if (this.needsRefresh) {
					try {
						initialize();
					} catch (JavaModelException e) {
						return false;
					}
				} else {
					// the scope is used only to find enclosing projects and jars
					// clients is responsible for filtering out elements not in the hierarchy (see SearchEngine)
					return true;
				}
			}
		}
		if (this.needsRefresh) {
			try {
				refresh();
			} catch(JavaModelException e) {
				return false;
			}
		}
		int separatorIndex = resourcePath.indexOf(JAR_FILE_ENTRY_SEPARATOR);
		if (separatorIndex != -1) {
			return this.resourcePaths.contains(resourcePath);
		} else {
			for (int i = 0; i < this.elementCount; i++) {
				if (resourcePath.startsWith(this.elements[i].getFullPath().toString())) {
					return true;
				}
			}
		}
		return false;
	}
	/* (non-Javadoc)
	 * @see IJavaSearchScope#encloses(IJavaElement)
	 */
	public boolean encloses(IJavaElement element) {
		if (this.hierarchy == null) {
			if (this.focusType.equals(element.getAncestor(IJavaElement.TYPE))) {
				return true;
			} else {
				if (this.needsRefresh) {
					try {
						initialize();
					} catch (JavaModelException e) {
						return false;
					}
				} else {
					// the scope is used only to find enclosing projects and jars
					// clients is responsible for filtering out elements not in the hierarchy (see SearchEngine)
					return true;
				}
			}
		}
		if (this.needsRefresh) {
			try {
				refresh();
			} catch(JavaModelException e) {
				return false;
			}
		}
		IType type = null;
		if (element instanceof IType) {
			type = (IType) element;
		} else if (element instanceof IMember) {
			type = ((IMember) element).getDeclaringType();
		}
		if (type != null) {
			if (this.hierarchy.contains(type)) {
				return true;
			} else {
				// be flexible: look at original element (see bug 14106 Declarations in Hierarchy does not find declarations in hierarchy)
				IType original;
				if (!type.isBinary()
						&& (original = (IType)type.getPrimaryElement()) != null) {
					return this.hierarchy.contains(original);
				}
			}
		}
		return false;
	}
	/* (non-Javadoc)
	 * @see IJavaSearchScope#enclosingProjectsAndJars()
	 * @deprecated
	 */
	public IPath[] enclosingProjectsAndJars() {
		if (this.needsRefresh) {
			try {
				refresh();
			} catch(JavaModelException e) {
				return new IPath[0];
			}
		}
		return this.enclosingProjectsAndJars;
	}
//	protected void initialize() throws JavaModelException {
//		this.resourcePaths = new HashSet();
//		this.elements = new IResource[5];
//		this.elementCount = 0;
//		this.needsRefresh = false;
//		if (this.hierarchy == null) {
//			this.hierarchy = this.focusType.newTypeHierarchy(this.owner, null);
//		} else {
//			this.hierarchy.refresh(null);
//		}
//		buildResourceVector();
//	}
	/*
	 * @see AbstractSearchScope#processDelta(IJavaElementDelta)
	 */
	public void processDelta(IJavaElementDelta delta, int eventType) {
		if (this.needsRefresh) return;
		this.needsRefresh = this.hierarchy == null ? false : ((TypeHierarchy)this.hierarchy).isAffected(delta, eventType);
	}
	protected void refresh() throws JavaModelException {
		if (this.hierarchy != null) {
			initialize();
		}
	}
	public String toString() {
		return "WOHierarchyScope on " + ((JavaElement)this.focusType).toStringWithAncestors(); //$NON-NLS-1$
	}

	/**
	 * This is annoying, but we have to replace this ONE method, but we can't access our superclass variables, so just slurp the whole damn thing in.
	 * @throws JavaModelException
	 */
	protected void initialize() throws JavaModelException {
		this.resourcePaths = new HashSet<String>();
		this.elements = new IResource[5];
		this.elementCount = 0;
		this.needsRefresh = false;
		if (this.hierarchy == null) {
			this.hierarchy = SubTypeHierarchyCache.getTypeHierarchyInProject(this.focusType, this.javaProject, null);
		} else {
			this.hierarchy.refresh(null);
		}
		buildResourceVector();
	}
	
	protected static class WOHierarchyScopeCacheEntry {
		public IProject project;
		public int referenceCount;
		public Map<IType, WOHierarchyScope> scopes;
		
		public WOHierarchyScopeCacheEntry(IProject project) {
			scopes = new HashMap<IType, WOHierarchyScope>();
			this.project = project;
		}
		
		public synchronized WOHierarchyScope hierarchyScopeForType(IType type) throws JavaModelException {
			WOHierarchyScope scope = scopes.get(type);
			if (scope == null) {
				// System.out.println("WOHierarchyScope.WOHierarchyScopeCacheEntry.hierarchyScopeForType: cache MISS for " + type.getElementName() + " in " + project.getName());
				scope = new WOHierarchyScope(type, JavaCore.create(project));
				scopes.put(type, scope);
			}
			else {
				// System.out.println("WOHierarchyScope.WOHierarchyScopeCacheEntry.hierarchyScopeForType: cache hit for " + type.getElementName() + " in " + project.getName());
			}
			return scope;
		}
	}
	  
	private static Map<IProject, WOHierarchyScopeCacheEntry> _hierarchyScopeCache = new HashMap<IProject, WOHierarchyScopeCacheEntry>();
	  
	public static WOHierarchyScope hierarchyScope(IType superclassType, IProject project) throws JavaModelException {
		WOHierarchyScopeCacheEntry scopeCacheEntry;
		synchronized (_hierarchyScopeCache) {
			scopeCacheEntry = _hierarchyScopeCache.get(project);
		}
		if (scopeCacheEntry == null) {
			scopeCacheEntry = new WOHierarchyScopeCacheEntry(project);
		}
		return scopeCacheEntry.hierarchyScopeForType(superclassType);
	}
	  
	public static void clearCacheForProject(IProject project) {
		synchronized (_hierarchyScopeCache) {
			// System.out.println("WOHierarchyScope.clearCacheForProject: " + project.getName() + " clearing");
			_hierarchyScopeCache.remove(project);
		}
	}
	  
	public static WOHierarchyScopeCacheEntry incrementReferenceCountForProject(IProject project) {
		WOHierarchyScopeCacheEntry scopeCacheEntry;
		synchronized (_hierarchyScopeCache) {
			scopeCacheEntry = _hierarchyScopeCache.get(project);
			if (scopeCacheEntry == null) {
				scopeCacheEntry = new WOHierarchyScopeCacheEntry(project);
				_hierarchyScopeCache.put(project, scopeCacheEntry);
			}
			scopeCacheEntry.referenceCount ++;
			//System.out.println("WOHierarchyScope.incrementReferenceCountForProject: " + project.getName() + "=>" + scopeCacheEntry.referenceCount);
		}
		return scopeCacheEntry;
	}
	  
	public static void decrementReferenceCountForProject(IProject project) {
	  	synchronized (_hierarchyScopeCache) {
	  		WOHierarchyScopeCacheEntry scopeCacheEntry = _hierarchyScopeCache.get(project);
	  		if (scopeCacheEntry != null) {
	  			scopeCacheEntry.referenceCount --;
				//System.out.println("WOHierarchyScope.decrementReferenceCountForProject: " + project.getName() + "=>" + scopeCacheEntry.referenceCount);
	  			if (scopeCacheEntry.referenceCount <= 0) {
	  				clearCacheForProject(project);
	  			}
	  		}
	  	}
	}

}
