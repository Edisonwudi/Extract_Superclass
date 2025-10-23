package com.refactoring.extractsuperclass;

import org.slf4j.Logger;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Handles project module analysis and Maven dependency adjustments for the extract-superclass flow.
 */
final class ModuleDependencyManager {
	private static final Set<String> MODULE_SCAN_IGNORED_DIRS = new HashSet<>(Arrays.asList(
		"target",
		"build",
		"out",
		".idea",
		".git",
		".gradle"
	));

	private final List<File> projectRoots;
	private final Logger logger;

	ModuleDependencyManager(List<File> projectRoots, Logger logger) {
		this.projectRoots = new ArrayList<>(projectRoots);
		this.logger = logger;
	}

	ExtractSuperclassRefactorer.SuperclassPlacement planSuperclassPlacement(
		ExtractSuperclassRefactorer.RefEnv env,
		ExtractSuperclassRefactorer.NameParts planned,
		List<ExtractSuperclassRefactorer.TargetType> targets,
		boolean allowPackageInference
	) {
		ExtractSuperclassRefactorer.SuperclassPlacement autoPlacement =
			attemptAutoSuperclassPlacement(env, planned, targets, allowPackageInference);
		if (autoPlacement != null) {
			return autoPlacement;
		}
		return new ExtractSuperclassRefactorer.SuperclassPlacement(planned, null);
	}

	List<Path> ensureModuleDependencies(Path superFile, List<ExtractSuperclassRefactorer.TargetType> targets) throws Exception {
		if (superFile == null) {
			return Collections.emptyList();
		}
		Path superModule = findModuleRoot(superFile);
		if (superModule == null) {
			logger.debug("No module root resolved for superclass file {}", superFile);
			return Collections.emptyList();
		}
		Path superPomPath = superModule.resolve("pom.xml");
		PomInfo superPom;
		try {
			superPom = loadPom(superPomPath);
		} catch (Exception ex) {
			logger.warn("Failed to read anchor module pom {}", superPomPath, ex);
			return Collections.emptyList();
		}
		if (superPom.groupId == null || superPom.groupId.isEmpty()
			|| superPom.artifactId == null || superPom.artifactId.isEmpty()) {
			logger.warn("Anchor module pom {} is missing groupId or artifactId; skipping dependency update", superPomPath);
			return Collections.emptyList();
		}

		ModuleGraph dependencyGraph;
		ModuleInfo superModuleInfo;
		try {
			dependencyGraph = buildModuleGraph(targets);
			superModuleInfo = dependencyGraph.findByRoot(superModule);
		} catch (Exception ex) {
			logger.debug("Failed to build dependency graph for cycle detection: {}", ex.getMessage());
			dependencyGraph = null;
			superModuleInfo = null;
		}

		Set<Path> targetModules = new LinkedHashSet<>();
		for (ExtractSuperclassRefactorer.TargetType target : targets) {
			Path moduleRoot = findModuleRoot(target.filePath);
			if (moduleRoot == null || moduleRoot.equals(superModule)) {
				continue;
			}
			targetModules.add(moduleRoot);
		}

		List<Path> changed = new ArrayList<>();
		for (Path moduleRoot : targetModules) {
			Path pomPath = moduleRoot.resolve("pom.xml");
			PomInfo targetPom;
			try {
				targetPom = loadPom(pomPath);
			} catch (Exception ex) {
				logger.warn("Failed to read pom {} while adding superclass dependency", pomPath, ex);
				continue;
			}

			if (dependencyGraph != null && superModuleInfo != null) {
				ModuleInfo targetInfo = dependencyGraph.findByRoot(moduleRoot);
				if (targetInfo != null) {
					if (dependencyGraph.hasPathBetween(superModuleInfo, targetInfo)) {
						logger.warn("Skipping dependency from {} to {} to avoid introducing a cycle", moduleRoot, superModule);
						continue;
					}
					if (dependencyGraph.hasPathBetween(targetInfo, superModuleInfo)) {
						// Already depends (directly or indirectly) on the super module.
						logger.debug("Skipping dependency from {} to {}; relationship already satisfied transitively", moduleRoot, superModule);
						continue;
					}
				}
			}

			if (addDependencyIfMissing(targetPom, superPom)) {
				writePom(targetPom);
				changed.add(pomPath);
			}
		}
		return changed;
	}

	private ExtractSuperclassRefactorer.SuperclassPlacement attemptAutoSuperclassPlacement(
		ExtractSuperclassRefactorer.RefEnv env,
		ExtractSuperclassRefactorer.NameParts planned,
		List<ExtractSuperclassRefactorer.TargetType> targets,
		boolean allowPackageInference
	) {
		if (targets == null || targets.isEmpty()) {
			return null;
		}

		try {
			ModuleGraph graph = buildModuleGraph(targets);
			if (graph.isEmpty()) {
				return null;
			}

			List<ModuleInfo> targetModules = new ArrayList<>();
			for (ExtractSuperclassRefactorer.TargetType target : targets) {
				Path moduleRoot = findModuleRoot(target.filePath);
				if (moduleRoot == null) {
					logger.debug("Skipping auto-placement: no module root for {}", target.fqn);
					return null;
				}
				ModuleInfo moduleInfo = graph.findByRoot(moduleRoot);
				if (moduleInfo == null) {
					logger.debug("Skipping auto-placement: module {} missing from graph", moduleRoot);
					return null;
				}
				if (!targetModules.contains(moduleInfo)) {
					targetModules.add(moduleInfo);
				}
			}

			if (targetModules.isEmpty()) {
				return null;
			}

			ModuleInfo candidate = graph.selectBestCommonUpstream(targetModules);
			if (candidate == null) {
				logger.debug("No common upstream module found for {}", targetModules);
				return null;
			}

			Path sourceRoot = resolveJavaSourceRoot(candidate.root);
			Path filePath = sourceRoot;
			if (planned.pkg != null && !planned.pkg.isEmpty()) {
				String[] segments = planned.pkg.split("\\.");
				for (String seg : segments) {
					if (!seg.isEmpty()) {
						filePath = filePath.resolve(seg);
					}
				}
			}
			filePath = filePath.resolve(planned.simple + ".java");

			String pkg = planned.pkg == null ? "" : planned.pkg;
			if (allowPackageInference) {
				String inferred = inferPackageFromAbsolutePath(env, filePath.getParent());
				if (inferred != null) {
					pkg = inferred;
				}
			}

			ExtractSuperclassRefactorer.NameParts effective = new ExtractSuperclassRefactorer.NameParts(pkg, planned.simple);
			logger.info("Auto-selected superclass placement in module {} at {}", candidate.describe(), filePath);
			return new ExtractSuperclassRefactorer.SuperclassPlacement(effective, filePath);
		} catch (Exception ex) {
			logger.warn("Failed to auto-select superclass placement: {}", ex.getMessage(), ex);
			return null;
		}
	}

	private ModuleGraph buildModuleGraph(List<ExtractSuperclassRefactorer.TargetType> targets) {
		Map<Path, ModuleInfo> modulesByRoot = new LinkedHashMap<>();
		Map<ModuleCoordinate, ModuleInfo> modulesByCoordinate = new LinkedHashMap<>();

		for (File rootFile : projectRoots) {
			Path projectRoot = rootFile.toPath().toAbsolutePath().normalize();
			if (!Files.exists(projectRoot)) {
				continue;
			}
			try (Stream<Path> stream = Files.walk(projectRoot, 8)) {
				stream.filter(Files::isRegularFile)
					.filter(p -> "pom.xml".equalsIgnoreCase(p.getFileName().toString()))
					.filter(p -> !isIgnoredModulePath(projectRoot, p))
					.forEach(p -> registerModule(modulesByRoot, modulesByCoordinate, p));
			} catch (Exception ex) {
				logger.debug("Failed to scan {} for pom.xml files: {}", projectRoot, ex.getMessage());
			}
		}

		if (targets != null) {
			for (ExtractSuperclassRefactorer.TargetType target : targets) {
				Path moduleRoot = findModuleRoot(target.filePath);
				if (moduleRoot == null) {
					continue;
				}
				Path pomPath = moduleRoot.resolve("pom.xml");
				if (!Files.exists(pomPath)) {
					continue;
				}
				registerModule(modulesByRoot, modulesByCoordinate, pomPath);
			}
		}

		ModuleGraph graph = new ModuleGraph(modulesByRoot, modulesByCoordinate);
		graph.resolveLinks();
		return graph;
	}

	private void registerModule(
		Map<Path, ModuleInfo> modulesByRoot,
		Map<ModuleCoordinate, ModuleInfo> modulesByCoordinate,
		Path pomPath
	) {
		Path moduleRoot = pomPath.getParent();
		if (moduleRoot == null) {
			return;
		}
		Path normalizedRoot = moduleRoot.toAbsolutePath().normalize();
		if (modulesByRoot.containsKey(normalizedRoot)) {
			return;
		}
		try {
			PomInfo pom = loadPom(pomPath);
			ModuleCoordinate coordinate = ModuleCoordinate.from(pom.groupId, pom.artifactId);
			Set<ModuleCoordinate> dependencyCoords = collectDependencyCoordinates(pom);
			boolean hasJavaSources = Files.isDirectory(normalizedRoot.resolve("src").resolve("main").resolve("java"));
			ModuleInfo info = new ModuleInfo(normalizedRoot, pom, coordinate, pom.packaging, dependencyCoords, hasJavaSources);
			modulesByRoot.put(normalizedRoot, info);
			if (coordinate != null && coordinate.isValid()) {
				modulesByCoordinate.putIfAbsent(coordinate, info);
			}
		} catch (Exception ex) {
			logger.debug("Skipping pom {} due to error: {}", pomPath, ex.getMessage());
		}
	}

	private Set<ModuleCoordinate> collectDependencyCoordinates(PomInfo pom) {
		Set<ModuleCoordinate> coordinates = new LinkedHashSet<>();
		List<Element> dependencyBlocks = findDirectChildren(pom.projectElement, "dependencies");
		for (Element block : dependencyBlocks) {
			NodeList children = block.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				Node node = children.item(i);
				if (!(node instanceof Element)) {
					continue;
				}
				Element dep = (Element) node;
				if (!matchesName(dep, "dependency")) {
					continue;
				}
				String groupId = textOfDirectChild(dep, "groupId");
				String artifactId = textOfDirectChild(dep, "artifactId");
				ModuleCoordinate coordinate = ModuleCoordinate.from(groupId, artifactId);
				if (coordinate != null && coordinate.isValid()) {
					coordinates.add(coordinate);
				}
			}
		}
		return coordinates;
	}

	private boolean isIgnoredModulePath(Path projectRoot, Path pomPath) {
		try {
			Path parent = pomPath.getParent();
			if (parent == null) {
				return false;
			}
			Path relative = projectRoot.relativize(parent);
			for (Path segment : relative) {
				if (MODULE_SCAN_IGNORED_DIRS.contains(segment.toString())) {
					return true;
				}
			}
		} catch (IllegalArgumentException ignore) {
			// pom not beneath project root; include by default
		}
		return false;
	}

	private String inferPackageFromAbsolutePath(ExtractSuperclassRefactorer.RefEnv env, Path directory) {
		if (directory == null) {
			return "";
		}
		Path normalized = directory.toAbsolutePath().normalize();
		for (String sp : env.sourcepaths) {
			Path root = new File(sp).toPath().toAbsolutePath().normalize();
			if (normalized.startsWith(root)) {
				Path relative = root.relativize(normalized);
				if (relative.getNameCount() == 0) {
					return "";
				}
				List<String> segments = new ArrayList<>();
				for (Path part : relative) {
					segments.add(part.toString());
				}
				int startIdx = 0;
				for (int i = 0; i <= segments.size() - 3; i++) {
					String first = segments.get(i);
					String second = segments.get(i + 1);
					String third = segments.get(i + 2);
					boolean isSrcMain = "src".equals(first) && ("main".equals(second) || "test".equals(second));
					boolean isLanguageDir = "java".equals(third) || "kotlin".equals(third) || "resources".equals(third);
					if (isSrcMain && isLanguageDir) {
						startIdx = i + 3;
					}
				}
				if (startIdx >= segments.size()) {
					return "";
				}
				StringBuilder builder = new StringBuilder();
				for (int i = startIdx; i < segments.size(); i++) {
					if (builder.length() > 0) {
						builder.append('.');
					}
					builder.append(segments.get(i));
				}
				String pkg = builder.toString();
				logger.debug("Inferred package {} for directory {}", pkg, normalized);
				return pkg;
			}
		}
		logger.debug("Unable to infer package for {}; falling back to planned value", normalized);
		return null;
	}

	private Path resolveJavaSourceRoot(Path moduleRoot) {
		Path normalized = moduleRoot.toAbsolutePath().normalize();
		Path javaDir = normalized.resolve("src").resolve("main").resolve("java");
		if (Files.isDirectory(javaDir)) {
			return javaDir;
		}
		Path mainDir = normalized.resolve("src").resolve("main");
		if (Files.isDirectory(mainDir)) {
			return javaDir;
		}
		Path srcDir = normalized.resolve("src");
		if (Files.isDirectory(srcDir)) {
			return javaDir;
		}
		return javaDir;
	}

	private Path findModuleRoot(Path file) {
		if (file == null) {
			return null;
		}
		Path current = file.toAbsolutePath().normalize();
		if (!Files.isDirectory(current)) {
			current = current.getParent();
		}
		while (current != null) {
			if (Files.exists(current.resolve("pom.xml"))) {
				return current;
			}
			current = current.getParent();
		}
		return null;
	}

	private PomInfo loadPom(Path pomPath) throws Exception {
		if (!Files.exists(pomPath)) {
			throw new IllegalArgumentException("Missing pom.xml at " + pomPath);
		}
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setNamespaceAware(true);
		DocumentBuilder builder = factory.newDocumentBuilder();
		org.w3c.dom.Document document = builder.parse(pomPath.toFile());
		document.getDocumentElement().normalize();
		Element projectElement = document.getDocumentElement();
		String groupId = textOfDirectChild(projectElement, "groupId");
		String artifactId = textOfDirectChild(projectElement, "artifactId");
		String version = textOfDirectChild(projectElement, "version");
		String packaging = textOfDirectChild(projectElement, "packaging");
		Element parentElement = findDirectChild(projectElement, "parent");
		if ((groupId == null || groupId.isEmpty()) && parentElement != null) {
			groupId = textOfDirectChild(parentElement, "groupId");
		}
		if ((version == null || version.isEmpty()) && parentElement != null) {
			version = textOfDirectChild(parentElement, "version");
		}
		return new PomInfo(pomPath, document, projectElement, groupId, artifactId, version, packaging);
	}

	private boolean addDependencyIfMissing(PomInfo targetPom, PomInfo dependencyPom) {
		if (dependencyPom.groupId == null || dependencyPom.groupId.isEmpty()
			|| dependencyPom.artifactId == null || dependencyPom.artifactId.isEmpty()) {
			logger.warn("Cannot add dependency; missing coordinates in {}", dependencyPom.path);
			return false;
		}
		if (dependencyPom.groupId.equals(targetPom.groupId) && dependencyPom.artifactId.equals(targetPom.artifactId)) {
			return false;
		}
		if (hasDirectDependency(targetPom.projectElement, dependencyPom.groupId, dependencyPom.artifactId)) {
			return false;
		}
		Element dependenciesElement = ensureDependenciesElement(targetPom);
		Element dependency = targetPom.document.createElement("dependency");
		appendChildWithText(targetPom.document, dependency, "groupId", dependencyPom.groupId);
		appendChildWithText(targetPom.document, dependency, "artifactId", dependencyPom.artifactId);
		if (dependencyPom.version != null && !dependencyPom.version.isEmpty()) {
			appendChildWithText(targetPom.document, dependency, "version", dependencyPom.version);
		}
		dependenciesElement.appendChild(dependency);
		return true;
	}

	private boolean hasDirectDependency(Element projectElement, String groupId, String artifactId) {
		List<Element> dependenciesBlocks = findDirectChildren(projectElement, "dependencies");
		for (Element block : dependenciesBlocks) {
			NodeList children = block.getChildNodes();
			for (int i = 0; i < children.getLength(); i++) {
				Node node = children.item(i);
				if (!(node instanceof Element)) {
					continue;
				}
				Element dep = (Element) node;
				if (!matchesName(dep, "dependency")) {
					continue;
				}
				String existingGroup = textOfDirectChild(dep, "groupId");
				String existingArtifact = textOfDirectChild(dep, "artifactId");
				if (groupId.equals(existingGroup) && artifactId.equals(existingArtifact)) {
					return true;
				}
			}
		}
		return false;
	}

	private Element ensureDependenciesElement(PomInfo pom) {
		Element existing = findDirectChild(pom.projectElement, "dependencies");
		if (existing != null) {
			return existing;
		}
		Element created = pom.document.createElement("dependencies");
		pom.projectElement.appendChild(created);
		return created;
	}

	private void appendChildWithText(org.w3c.dom.Document document, Element parent, String name, String value) {
		Element child = document.createElement(name);
		child.setTextContent(value);
		parent.appendChild(child);
	}

	private Element findDirectChild(Element parent, String name) {
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node instanceof Element && matchesName(node, name)) {
				return (Element) node;
			}
		}
		return null;
	}

	private List<Element> findDirectChildren(Element parent, String name) {
		List<Element> result = new ArrayList<>();
		NodeList nodes = parent.getChildNodes();
		for (int i = 0; i < nodes.getLength(); i++) {
			Node node = nodes.item(i);
			if (node instanceof Element && matchesName(node, name)) {
				result.add((Element) node);
			}
		}
		return result;
	}

	private boolean matchesName(Node node, String name) {
		String local = node.getLocalName();
		String actual = local != null ? local : node.getNodeName();
		return name.equals(actual);
	}

	private String textOfDirectChild(Element parent, String name) {
		Element child = findDirectChild(parent, name);
		if (child == null) {
			return null;
		}
		String value = child.getTextContent();
		return value == null ? null : value.trim();
	}

	private void writePom(PomInfo pomInfo) throws Exception {
		TransformerFactory transformerFactory = TransformerFactory.newInstance();
		Transformer transformer = transformerFactory.newTransformer();
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
		transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
		StringWriter writer = new StringWriter();
		transformer.transform(new DOMSource(pomInfo.document), new StreamResult(writer));
		Files.writeString(pomInfo.path, writer.toString(), StandardCharsets.UTF_8);
	}

	private static final class ModuleGraph {
		private final Map<Path, ModuleInfo> modulesByRoot;
		private final Map<ModuleCoordinate, ModuleInfo> modulesByCoordinate;

		ModuleGraph(Map<Path, ModuleInfo> modulesByRoot, Map<ModuleCoordinate, ModuleInfo> modulesByCoordinate) {
			this.modulesByRoot = modulesByRoot;
			this.modulesByCoordinate = modulesByCoordinate;
		}

		void resolveLinks() {
			for (ModuleInfo module : modulesByRoot.values()) {
				module.linkDependencies(modulesByCoordinate);
			}
		}

		boolean isEmpty() {
			return modulesByRoot.isEmpty();
		}

		ModuleInfo findByRoot(Path root) {
			if (root == null) {
				return null;
			}
			Path normalized = root.toAbsolutePath().normalize();
			return modulesByRoot.get(normalized);
		}

		ModuleInfo selectBestCommonUpstream(List<ModuleInfo> targets) {
			if (targets == null || targets.isEmpty()) {
				return null;
			}
			List<Map<ModuleInfo, Integer>> distanceMaps = new ArrayList<>();
			for (ModuleInfo module : targets) {
				distanceMaps.add(computeDistances(module));
			}
			Set<ModuleInfo> candidates = new LinkedHashSet<>(distanceMaps.get(0).keySet());
			for (int i = 1; i < distanceMaps.size(); i++) {
				candidates.retainAll(distanceMaps.get(i).keySet());
				if (candidates.isEmpty()) {
					return null;
				}
			}
			candidates.removeIf(module -> !module.isEligibleForSuperclass());
			if (candidates.isEmpty()) {
				return null;
			}
			ModuleInfo primaryTarget = targets.get(0);
			ModuleInfo best = null;
			int bestWorst = Integer.MAX_VALUE;
			int bestTotal = Integer.MAX_VALUE;
			for (ModuleInfo candidate : candidates) {
				boolean cycleRisk = false;
				for (ModuleInfo target : targets) {
					if (candidate != target && hasPath(candidate, target)) {
						cycleRisk = true;
						break;
					}
				}
				if (cycleRisk) {
					continue;
				}
				int worst = 0;
				int total = 0;
				boolean missingDistance = false;
				for (Map<ModuleInfo, Integer> distances : distanceMaps) {
					Integer distance = distances.get(candidate);
					if (distance == null) {
						missingDistance = true;
						break;
					}
					worst = Math.max(worst, distance);
					total += distance;
				}
				if (missingDistance) {
					continue;
				}
				boolean candidateHasSources = candidate.hasJavaSources;
				if (best == null) {
					best = candidate;
					bestWorst = worst;
					bestTotal = total;
					continue;
				}
				boolean bestHasSources = best.hasJavaSources;
				if (bestHasSources && !candidateHasSources) {
					continue;
				}
				if (!bestHasSources && candidateHasSources) {
					best = candidate;
					bestWorst = worst;
					bestTotal = total;
					continue;
				}
				if (worst < bestWorst
					|| (worst == bestWorst && total < bestTotal)
					|| (worst == bestWorst && total == bestTotal && candidate == primaryTarget)
					|| (worst == bestWorst && total == bestTotal && best != primaryTarget
						&& candidate.root.toString().compareTo(best.root.toString()) < 0)) {
					best = candidate;
					bestWorst = worst;
					bestTotal = total;
				}
			}
			return best;
		}

		boolean hasPathBetween(ModuleInfo from, ModuleInfo to) {
			if (from == null || to == null) {
				return false;
			}
			return hasPath(from, to);
		}

		private Map<ModuleInfo, Integer> computeDistances(ModuleInfo start) {
			Map<ModuleInfo, Integer> distances = new LinkedHashMap<>();
			ArrayDeque<ModuleInfo> queue = new ArrayDeque<>();
			queue.add(start);
			distances.put(start, 0);
			while (!queue.isEmpty()) {
				ModuleInfo current = queue.removeFirst();
				int nextDistance = distances.get(current) + 1;
				for (ModuleInfo dependency : current.dependencies) {
					if (!distances.containsKey(dependency)) {
						distances.put(dependency, nextDistance);
						queue.add(dependency);
					}
				}
			}
			return distances;
		}

		private boolean hasPath(ModuleInfo from, ModuleInfo to) {
			if (from == to) {
				return true;
			}
			Set<ModuleInfo> visited = new HashSet<>();
			ArrayDeque<ModuleInfo> queue = new ArrayDeque<>();
			queue.add(from);
			visited.add(from);
			while (!queue.isEmpty()) {
				ModuleInfo current = queue.removeFirst();
				for (ModuleInfo dependency : current.dependencies) {
					if (dependency == to) {
						return true;
					}
					if (visited.add(dependency)) {
						queue.add(dependency);
					}
				}
			}
			return false;
		}
	}

	private static final class ModuleInfo {
		final Path root;
		final PomInfo pom;
		final ModuleCoordinate coordinate;
		final String packaging;
		final Set<ModuleCoordinate> declaredDependencyCoords;
		final Set<ModuleInfo> dependencies = new LinkedHashSet<>();
		final boolean hasJavaSources;

		ModuleInfo(Path root, PomInfo pom, ModuleCoordinate coordinate, String packaging,
		           Set<ModuleCoordinate> declaredDependencyCoords, boolean hasJavaSources) {
			this.root = root;
			this.pom = pom;
			this.coordinate = coordinate;
			this.packaging = packaging;
			this.declaredDependencyCoords = declaredDependencyCoords;
			this.hasJavaSources = hasJavaSources;
		}

		void linkDependencies(Map<ModuleCoordinate, ModuleInfo> registry) {
			for (ModuleCoordinate coordinate : declaredDependencyCoords) {
				ModuleInfo target = registry.get(coordinate);
				if (target != null && target != this) {
					dependencies.add(target);
				}
			}
		}

		boolean isEligibleForSuperclass() {
			if (coordinate == null || !coordinate.isValid()) {
				return false;
			}
			if (packaging != null && "pom".equalsIgnoreCase(packaging.trim())) {
				return false;
			}
			return true;
		}

		String describe() {
			if (coordinate != null && coordinate.isValid()) {
				return coordinate.toString();
			}
			return root.toString();
		}
	}

	private static final class ModuleCoordinate {
		final String groupId;
		final String artifactId;

		private ModuleCoordinate(String groupId, String artifactId) {
			this.groupId = groupId == null ? "" : groupId.trim();
			this.artifactId = artifactId == null ? "" : artifactId.trim();
		}

		static ModuleCoordinate from(String groupId, String artifactId) {
			if (groupId == null || groupId.trim().isEmpty() || artifactId == null || artifactId.trim().isEmpty()) {
				return null;
			}
			return new ModuleCoordinate(groupId, artifactId);
		}

		boolean isValid() {
			return !groupId.isEmpty() && !artifactId.isEmpty();
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (!(obj instanceof ModuleCoordinate)) return false;
			ModuleCoordinate other = (ModuleCoordinate) obj;
			return groupId.equals(other.groupId) && artifactId.equals(other.artifactId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(groupId, artifactId);
		}

		@Override
		public String toString() {
			return groupId + ":" + artifactId;
		}
	}

	private static final class PomInfo {
		final Path path;
		final org.w3c.dom.Document document;
		final Element projectElement;
		final String groupId;
		final String artifactId;
		final String version;
		final String packaging;

		PomInfo(Path path, org.w3c.dom.Document document, Element projectElement, String groupId, String artifactId, String version, String packaging) {
			this.path = path;
			this.document = document;
			this.projectElement = projectElement;
			this.groupId = groupId;
			this.artifactId = artifactId;
			this.version = version;
			this.packaging = packaging;
		}
	}
}
