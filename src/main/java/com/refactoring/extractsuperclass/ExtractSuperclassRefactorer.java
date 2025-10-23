package com.refactoring.extractsuperclass;

import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ExtractSuperclassRefactorer {
	private static final Logger logger = LoggerFactory.getLogger(ExtractSuperclassRefactorer.class);

	private final List<File> projectRoots;

	public ExtractSuperclassRefactorer(List<File> projectRoots) {
		this.projectRoots = new ArrayList<>(projectRoots);
	}

	public ExtractSuperclassResult performRefactoring(ExtractSuperclassRequest request) {
		long start = System.currentTimeMillis();
		try {
			RefEnv env = RefEnv.build(projectRoots);
			List<String> resolvedFqns = resolveInputClassNames(env, request.classNames());
			List<TargetType> targets = resolveTargets(env, resolvedFqns);
			if (targets.size() < 2) {
				return ExtractSuperclassResult.failure("Could not resolve two or more classes").executionTimeMs(elapsed(start)).build();
			}

			// Determine existing superclass situation
			SuperSituation situation = analyzeSuperSituation(targets);

			List<String> modified = new ArrayList<>();
			String resultingSuperName = null;

			if (situation.kind == SuperSituationKind.ALL_NONE) {
				// Create new abstract superclass and set for all
				NameParts plannedName = planSuperclassName(request, targets);
				if (plannedName.simple == null || plannedName.simple.isEmpty()) {
					return ExtractSuperclassResult.failure("Invalid superclass name plan").executionTimeMs(elapsed(start)).build();
				}
				SuperclassPlacement placement = planSuperclassPlacement(env, request, plannedName);
				NameParts name = placement.name;
				resultingSuperName = name.qualified();
				Path superFile = null;
				if (!request.dryRun()) {
					TargetType anchor = targets.isEmpty() ? null : targets.get(0);
					superFile = ensureSuperclassFile(env, placement, anchor, null);
					if (superFile != null) modified.add(superFile.toString());
					for (TargetType t : targets) {
						Path p = t.filePath;
						String original = Files.readString(p, StandardCharsets.UTF_8);
						String updated = rewriteTypeToExtend(original, t.typeDecl, name.qualified(), /*allowReplace*/ false);
						if (!Objects.equals(original, updated)) {
							Files.writeString(p, updated, StandardCharsets.UTF_8);
							modified.add(p.toString());
							try { organizeImports(env, p, updated, Collections.emptyList()); } catch (Throwable ex) { logger.debug("Import organization skipped for {}: {}", p, String.valueOf(ex.getMessage())); }
						}
					}
					try {
						List<Path> updatedPoms = ensureModuleDependencies(superFile, targets);
						for (Path pomPath : updatedPoms) {
							modified.add(pomPath.toString());
						}
					} catch (Exception ex) {
						logger.warn("Failed to update module dependencies", ex);
				}
				}
			} else if (situation.kind == SuperSituationKind.EXACTLY_ONE_HAS) {
				// Do not create a new class; make those without extends directly extend the superclass of the one that has it
				TargetType pivot = situation.oneWith;
				// Determine pivot's current superclass name (prefer FQN if resolvable)
				String pivotSuperSimple = pivot.typeDecl.getSuperclassType() != null ? pivot.typeDecl.getSuperclassType().toString() : null;
				String pivotSuperFqn = resolveExistingSuperclassQualifiedName(env, pivot);
				if (pivotSuperFqn == null) {
					pivotSuperFqn = resolveTypeNameToFqn(env, pivotSuperSimple, pivot.packageName);
				}
				resultingSuperName = pivotSuperFqn != null ? pivotSuperFqn : pivotSuperSimple;
				if (!request.dryRun()) {
					for (TargetType t : targets) {
						if (t == pivot) continue;
						// Only update if the target currently has no superclass
						if (t.typeDecl.getSuperclassType() != null) continue;
						Path p = t.filePath;
						String original = Files.readString(p, StandardCharsets.UTF_8);
						String superNameToUse;
						if (pivotSuperFqn != null) {
							NameParts parts = NameParts.fromQualified(pivotSuperFqn);
							superNameToUse = parts.simple;
						} else {
							superNameToUse = pivotSuperSimple;
						}
						if (superNameToUse == null || superNameToUse.isEmpty()) {
							continue; // nothing to do if we cannot determine a superclass
						}
						String updated = rewriteTypeToExtend(original, t.typeDecl, superNameToUse, /*allowReplace*/ false);
						if (!Objects.equals(original, updated)) {
							Files.writeString(p, updated, StandardCharsets.UTF_8);
							modified.add(p.toString());
							try {
								List<String> ensureImports = Collections.emptyList();
								if (pivotSuperFqn != null) {
									NameParts parts = NameParts.fromQualified(pivotSuperFqn);
									if (!parts.pkg.isEmpty() && !parts.pkg.equals(t.packageName)) {
										ensureImports = Collections.singletonList(pivotSuperFqn);
									}
								}
								organizeImports(env, p, updated, ensureImports);
							} catch (Throwable ex) { logger.debug("Import organization skipped for {}: {}", p, String.valueOf(ex.getMessage())); }
						}
					}
				}
			} else {
				String sharedSuper = findCommonSuperclassQualifiedName(env, targets);
				if (sharedSuper != null) {
					NameParts plannedName = planSuperclassName(request, targets);
					if (plannedName.simple == null || plannedName.simple.isEmpty()) {
						return ExtractSuperclassResult.failure("Invalid superclass name plan").executionTimeMs(elapsed(start)).build();
					}
					SuperclassPlacement placement = planSuperclassPlacement(env, request, plannedName);
					NameParts name = placement.name;
					resultingSuperName = name.qualified();
					Path superFile = null;
					if (!request.dryRun()) {
						TargetType anchor = targets.isEmpty() ? null : targets.get(0);
						superFile = ensureSuperclassFile(env, placement, anchor, sharedSuper);
						if (superFile != null) {
							modified.add(superFile.toString());
						}
						for (TargetType t : targets) {
							Path p = t.filePath;
							String original = Files.readString(p, StandardCharsets.UTF_8);
							String updated = rewriteTypeToExtend(original, t.typeDecl, name.simple, /*allowReplace*/ true);
							if (!Objects.equals(original, updated)) {
								Files.writeString(p, updated, StandardCharsets.UTF_8);
								modified.add(p.toString());
								try {
									List<String> ensureImports = Collections.emptyList();
									if (!name.pkg.isEmpty() && !name.pkg.equals(t.packageName)) {
										ensureImports = Collections.singletonList(name.qualified());
									}
									organizeImports(env, p, updated, ensureImports);
								} catch (Throwable ex) {
									logger.debug("Import organization skipped for {}: {}", p, String.valueOf(ex.getMessage()));
								}
							}
						}
						try {
							List<Path> updatedPoms = ensureModuleDependencies(superFile, targets);
							for (Path pomPath : updatedPoms) {
								modified.add(pomPath.toString());
							}
						} catch (Exception ex) {
							logger.warn("Failed to update module dependencies", ex);
						}
					}
				} else {
					// TWO_OR_MORE_HAVE with different supers: do nothing
					resultingSuperName = null;
				}
			}

			return ExtractSuperclassResult.success()
				.superclassQualifiedName(resultingSuperName)
				.modifiedFiles(modified)
				.executionTimeMs(elapsed(start))
				.build();
		} catch (Exception e) {
			logger.error("extractsuperclass failed", e);
			return ExtractSuperclassResult.failure(e.getMessage()).executionTimeMs(elapsed(start)).build();
		}
	}

	private static long elapsed(long start) { return Math.max(1, System.currentTimeMillis() - start); }

	private static List<TargetType> resolveTargets(RefEnv env, List<String> fqns) {
		List<TargetType> list = new ArrayList<>();
		for (String fqn : fqns) {
			TargetType t = env.findTypeByFqn(fqn);
			if (t != null) list.add(t);
		}
		return list;
	}

	/**
	 * Resolve user-provided class names that may be fully qualified or simple names.
	 * If a simple name matches multiple FQNs, prefer one under the most common package of all indexed types.
	 */
	private static List<String> resolveInputClassNames(RefEnv env, List<String> inputNames) {
		List<String> result = new ArrayList<>();
		if (inputNames == null) return result;
		Map<String, List<String>> simpleToFqns = new HashMap<>();
		for (String fqn : env.fqnToType.keySet()) {
			String simple = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
			simpleToFqns.computeIfAbsent(simple, k -> new ArrayList<>()).add(fqn);
		}

		String preferredPackage = mostFrequentPackage(env.fqnToType.keySet());
		logger.info("Available classes: {}", env.fqnToType.keySet());
		logger.info("Preferred package: {}", preferredPackage);

		for (String name : inputNames) {
			logger.info("Resolving class name: {}", name);
			if (name.contains(".")) {
				result.add(name);
				logger.info("Added FQN: {}", name);
				continue;
			}
			List<String> candidates = simpleToFqns.getOrDefault(name, Collections.emptyList());
			logger.info("Candidates for {}: {}", name, candidates);
			if (candidates.isEmpty()) {
				logger.warn("No candidates found for class name: {}", name);
				continue;
			}
			if (candidates.size() == 1) {
				result.add(candidates.get(0));
				logger.info("Added single candidate: {}", candidates.get(0));
			} else {
				// prefer one under preferredPackage, else first
				String pick = candidates.stream()
					.filter(f -> preferredPackage.isEmpty() || f.startsWith(preferredPackage + "."))
					.findFirst().orElse(candidates.get(0));
				result.add(pick);
				logger.info("Picked from multiple candidates: {}", pick);
			}
		}
		return result;
	}

	private static String mostFrequentPackage(Collection<String> fqns) {
		Map<String, Integer> count = new HashMap<>();
		for (String fqn : fqns) {
			int i = fqn.lastIndexOf('.');
			String pkg = i > 0 ? fqn.substring(0, i) : "";
			count.merge(pkg, 1, Integer::sum);
		}
		return count.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
	}

	private static NameParts planSuperclassName(ExtractSuperclassRequest req, List<TargetType> targets) {
		if (req.superQualifiedName() != null && !req.superQualifiedName().isEmpty()) {
			return NameParts.fromQualified(req.superQualifiedName());
		}
		// Always use the package of the first target class
		String pkg = targets.get(0).packageName;
		String base = commonSimplePrefix(targets);
		String simple = (base.isEmpty() ? "AbstractBase" : "Abstract" + base);
		return new NameParts(pkg, simple);
	}

	private static String commonPackage(List<TargetType> types) {
		Map<String, Long> counts = types.stream()
			.collect(Collectors.groupingBy(t -> t.packageName, Collectors.counting()));
		return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
	}

	private static String commonSimplePrefix(List<TargetType> types) {
		List<String> names = types.stream().map(t -> t.simpleName).collect(Collectors.toList());
		if (names.isEmpty()) return "";
		String prefix = names.get(0);
		for (int i = 1; i < names.size(); i++) {
			prefix = commonPrefix(prefix, names.get(i));
			if (prefix.isEmpty()) break;
		}
		while (!prefix.isEmpty() && !Character.isUpperCase(prefix.charAt(prefix.length()-1))) {
			prefix = prefix.substring(0, prefix.length()-1);
		}
		return prefix;
	}

	private static String commonPrefix(String a, String b) {
		int n = Math.min(a.length(), b.length());
		int i = 0;
		for (; i < n; i++) {
			if (a.charAt(i) != b.charAt(i)) break;
		}
		return a.substring(0, i);
	}

	private String findCommonSuperclassQualifiedName(RefEnv env, List<TargetType> targets) {
		if (targets == null || targets.isEmpty()) {
			return null;
		}
		String common = null;
		for (TargetType target : targets) {
			if (target == null || target.typeDecl == null || target.typeDecl.getSuperclassType() == null) {
				return null;
			}
			String identifier = determineSuperclassIdentifier(env, target);
			if (identifier == null || identifier.isEmpty()) {
				return null;
			}
			if (common == null) {
				common = identifier;
			} else if (!common.equals(identifier)) {
				return null;
			}
		}
		return common;
	}

	private String determineSuperclassIdentifier(RefEnv env, TargetType target) {
		String resolved = resolveExistingSuperclassQualifiedName(env, target);
		if (resolved != null && !resolved.isEmpty()) {
			return resolved;
		}
		Type superType = target.typeDecl.getSuperclassType();
		if (superType == null) {
			return null;
		}
		String raw = baseTypeName(superType.toString());
		if (raw == null || raw.isEmpty()) {
			return null;
		}
		String attempt = resolveTypeNameToFqn(env, raw, target.packageName);
		if (attempt != null && !attempt.isEmpty()) {
			return attempt;
		}
		return raw;
	}

	private SuperclassPlacement planSuperclassPlacement(RefEnv env, ExtractSuperclassRequest req, NameParts planned) {
		if (req.absoluteOutputPath() == null || req.absoluteOutputPath().trim().isEmpty()) {
			return new SuperclassPlacement(planned, null);
		}
		Path requested = java.nio.file.Paths.get(req.absoluteOutputPath().trim()).toAbsolutePath().normalize();
		boolean exists = Files.exists(requested);
		boolean isDirectory = exists && Files.isDirectory(requested);
		Path filePath;
		String simple = planned.simple;
		if (isDirectory) {
			filePath = requested.resolve(simple + ".java");
		} else {
			filePath = requested;
			String fileName = filePath.getFileName() != null ? filePath.getFileName().toString() : "";
			if (fileName.isEmpty()) {
				fileName = simple + ".java";
				filePath = filePath.resolve(fileName);
			}
			if (!fileName.toLowerCase(Locale.ROOT).endsWith(".java")) {
				fileName = fileName + ".java";
				filePath = filePath.resolveSibling(fileName);
			}
			String trimmed = fileName.substring(0, Math.max(0, fileName.length() - 5));
			if (!trimmed.isEmpty()) {
				simple = trimmed;
			}
		}
		String pkg = planned.pkg == null ? "" : planned.pkg;
		if (req.superQualifiedName() == null || req.superQualifiedName().isEmpty()) {
			String inferred = inferPackageFromAbsolutePath(env, filePath.getParent());
			if (inferred != null) {
				pkg = inferred;
			}
		}
		NameParts effective = new NameParts(pkg, simple);
		logger.debug("Planned superclass placement: name={}, path={}", effective.qualified(), filePath);
		return new SuperclassPlacement(effective, filePath);
	}

	private String inferPackageFromAbsolutePath(RefEnv env, Path directory) {
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
				StringBuilder builder = new StringBuilder();
				for (Path part : relative) {
					if (builder.length() > 0) {
						builder.append('.');
					}
					builder.append(part.toString());
				}
				String pkg = builder.toString();
				logger.debug("Inferred package {} for directory {}", pkg, normalized);
				return pkg;
			}
		}
		logger.debug("Unable to infer package for {}; falling back to planned value", normalized);
		return null;
	}

	private Path ensureSuperclassFile(RefEnv env, SuperclassPlacement placement, TargetType anchor, String extendsQualifiedName) throws Exception {
		if (placement.explicitPath != null) {
			return ensureSuperclassFileAtExplicitPath(placement, extendsQualifiedName);
		}
		return ensureSuperclassFileNearType(env, placement.name, anchor, extendsQualifiedName);
	}

	private Path ensureSuperclassFileAtExplicitPath(SuperclassPlacement placement, String extendsQualifiedName) throws Exception {
		Path file = placement.explicitPath;
		Path parent = file.getParent();
		if (parent == null) {
			throw new IllegalArgumentException("absolute_output_path must include a directory where the superclass can be created");
		}
		Files.createDirectories(parent);
		if (!Files.exists(file)) {
			String content = renderAbstractSuperclass(placement.name, extendsQualifiedName);
			Files.writeString(file, content, StandardCharsets.UTF_8);
			logger.info("Created superclass file at explicit path: {}", file);
		} else {
			logger.info("Superclass already exists at explicit path: {}", file);
		}
		return file;
	}

	/** Place the new superclass in the same directory as the first target class when no explicit path is provided. */
	private Path ensureSuperclassFileNearType(RefEnv env, NameParts superName, TargetType anchor, String extendsQualifiedName) throws Exception {
		Path pkgDir = env.resolvePackageDir(superName.pkg, anchor);
		logger.info("Resolved superclass directory for {} to {}", superName.qualified(), pkgDir);
		Files.createDirectories(pkgDir);
		Path file = pkgDir.resolve(superName.simple + ".java");
		if (!Files.exists(file)) {
			String content = renderAbstractSuperclass(superName, extendsQualifiedName);
			Files.writeString(file, content, StandardCharsets.UTF_8);
			logger.info("Created superclass file: {}", file);
		} else {
			logger.info("Superclass already exists: {}", file);
		}
		return file;
	}


	private String envFirstSourcePathOrCwd() {
		try { return new java.io.File(".").getCanonicalPath(); } catch (Exception e) { return new java.io.File(".").getAbsolutePath(); }
	}

	private String renderAbstractSuperclass(NameParts name, String extendsQualifiedName) {
		String pkgLine = name.pkg.isEmpty() ? "" : ("package " + name.pkg + ";\n\n");
		String extendsClause = (extendsQualifiedName != null && !extendsQualifiedName.isEmpty())
			? " extends " + extendsQualifiedName
			: "";
		return pkgLine + "public abstract class " + name.simple + extendsClause + " {\n}\n";
	}

	private List<Path> ensureModuleDependencies(Path superFile, List<TargetType> targets) throws Exception {
		if (superFile == null) return Collections.emptyList();
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
		if (superPom.groupId == null || superPom.groupId.isEmpty() || superPom.artifactId == null || superPom.artifactId.isEmpty()) {
			logger.warn("Anchor module pom {} is missing groupId or artifactId; skipping dependency update", superPomPath);
			return Collections.emptyList();
		}
		Set<Path> targetModules = new LinkedHashSet<>();
		for (TargetType target : targets) {
			Path moduleRoot = findModuleRoot(target.filePath);
			if (moduleRoot == null || moduleRoot.equals(superModule)) continue;
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
			if (addDependencyIfMissing(targetPom, superPom)) {
				writePom(targetPom);
				changed.add(pomPath);
			}
		}
		return changed;
	}

	private Path findModuleRoot(Path file) {
		if (file == null) return null;
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
		Element parentElement = findDirectChild(projectElement, "parent");
		if ((groupId == null || groupId.isEmpty()) && parentElement != null) {
			groupId = textOfDirectChild(parentElement, "groupId");
		}
		if ((version == null || version.isEmpty()) && parentElement != null) {
			version = textOfDirectChild(parentElement, "version");
		}
		return new PomInfo(pomPath, document, projectElement, groupId, artifactId, version);
	}

	private boolean addDependencyIfMissing(PomInfo targetPom, PomInfo dependencyPom) {
		if (dependencyPom.groupId == null || dependencyPom.groupId.isEmpty() || dependencyPom.artifactId == null || dependencyPom.artifactId.isEmpty()) {
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
				if (!(node instanceof Element)) continue;
				Element dep = (Element) node;
				if (!matchesName(dep, "dependency")) continue;
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
		if (child == null) return null;
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

	private String rewriteTypeToExtend(String original, TypeDeclaration typeDecl, String superQualifiedName, boolean allowReplace) {
		int start = typeDecl.getStartPosition();
		int len = typeDecl.getLength();
		int end = Math.min(original.length(), start + len);
		String header = original.substring(start, end);
		int classIdx = header.indexOf("class " + typeDecl.getName().getIdentifier());
		if (classIdx < 0) return original;
		int braceIdx = header.indexOf('{', classIdx);
		if (braceIdx < 0) return original;
		String declPart = header.substring(classIdx, braceIdx);
		if (declPart.contains(" extends ")) {
			if (!allowReplace) return original; // respect existing superclass per rule
			String replaced = declPart.replaceFirst("extends\\s+[^\\s{]+", "extends " + superQualifiedName);
			String newHeader = header.substring(0, classIdx) + replaced + header.substring(braceIdx);
			return original.substring(0, start) + newHeader + original.substring(start + header.length());
		} else {
			int implIdx = declPart.indexOf(" implements ");
			String newDecl;
			if (implIdx >= 0) {
				newDecl = declPart.substring(0, implIdx) + " extends " + superQualifiedName + declPart.substring(implIdx);
			} else {
				newDecl = declPart + " extends " + superQualifiedName;
			}
			String newHeader = header.substring(0, classIdx) + newDecl + header.substring(braceIdx);
			return original.substring(0, start) + newHeader + original.substring(start + header.length());
		}
	}

	private void organizeImports(RefEnv env, Path filePath, String updated, Collection<String> ensureImports) throws Exception {
		try {
			CompilationUnit cu = parseWithEnv(env, updated, filePath);
			ImportRewrite rewrite = ImportRewrite.create(cu, true);
			rewrite.setOnDemandImportThreshold(99);
			rewrite.setStaticOnDemandImportThreshold(2);
			if (ensureImports != null) {
				for (String qualified : ensureImports) {
					if (qualified == null || qualified.isEmpty()) continue;
					if (!qualified.contains(".")) continue;
					rewrite.addImport(qualified);
				}
			}
			TextEdit edit = rewrite.rewriteImports(null);
			Document doc = new Document(updated);
			edit.apply(doc);
			String after = doc.get();
			if (!Objects.equals(updated, after)) {
				Files.writeString(filePath, after, StandardCharsets.UTF_8);
			}
		} catch (RuntimeException ex) {
			if (!attemptManualImportInsertion(filePath, updated, ensureImports)) {
				throw ex;
			}
		}
	}

	private CompilationUnit parseWithEnv(RefEnv env, String source, Path unitPath) {
		ASTParser parser = ASTParser.newParser(AST.JLS17);
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setSource(source.toCharArray());
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		parser.setEnvironment(env.classpath, env.sourcepaths, null, true);
		parser.setUnitName(unitPath.getFileName().toString());
		return (CompilationUnit) parser.createAST(null);
	}

	private static final class RefEnv {
		final String[] classpath;
		final String[] sourcepaths;
		final Map<String, TargetType> fqnToType;

		private RefEnv(String[] cp, String[] sp, Map<String, TargetType> map) {
			this.classpath = cp;
			this.sourcepaths = sp;
			this.fqnToType = map;
		}

		static RefEnv build(List<File> roots) throws Exception {
			List<String> cpList = new ArrayList<>();
			List<String> spList = new ArrayList<>();
			for (File r : roots) {
				spList.add(r.getAbsolutePath());
				collectCp(r, cpList);
			}
			Map<String, TargetType> map = indexTypes(roots, cpList, spList);
			return new RefEnv(cpList.toArray(new String[0]), spList.toArray(new String[0]), map);
		}

		TargetType findTypeByFqn(String fqn) {
			return fqnToType.get(fqn);
		}
		private Path sourceRootFor(Path file) {
			if (file == null) return null;
			Path abs = file.toAbsolutePath().normalize();
			for (String sp : sourcepaths) {
				Path root = new File(sp).toPath().toAbsolutePath().normalize();
				if (abs.startsWith(root)) {
					return root;
				}
			}
			return null;
		}


		Path resolvePackageDir(String pkg, TargetType anchor) {
			String[] segments = (pkg == null || pkg.isEmpty()) ? new String[0] : pkg.split("\\.");
			if (segments.length > 0) {
				for (TargetType t : fqnToType.values()) {
					if (pkg.equals(t.packageName)) {
						return t.filePath.getParent();
					}
				}
			} else {
				Path anchorRoot = anchor != null ? sourceRootFor(anchor.filePath) : null;
				if (anchorRoot != null) {
					return anchorRoot;
				}
				return new File(sourcepaths[0]).toPath().toAbsolutePath().normalize();
			}

			Path anchorRoot = anchor != null ? sourceRootFor(anchor.filePath) : null;
			Path bestRoot = null;
			int bestDepth = -1;
			for (String sp : sourcepaths) {
				Path root = new File(sp).toPath().toAbsolutePath().normalize();
				Path current = root;
				int depth = 0;
				for (String seg : segments) {
					current = current.resolve(seg);
					if (Files.exists(current)) {
						depth++;
					} else {
						break;
					}
				}
				if (depth > bestDepth || (depth == bestDepth && anchorRoot != null && anchorRoot.equals(root))) {
					bestDepth = depth;
					bestRoot = root;
				}
			}

			if (bestRoot != null) {
				Path candidate = bestRoot;
				for (String seg : segments) {
					candidate = candidate.resolve(seg);
				}
				return candidate;
			}

			if (anchorRoot != null) {
				Path candidate = anchorRoot;
				for (String seg : segments) {
					candidate = candidate.resolve(seg);
				}
				return candidate;
			}

			Path fallbackRoot = new File(sourcepaths[0]).toPath().toAbsolutePath().normalize();
			Path candidate = fallbackRoot;
			for (String seg : segments) {
				candidate = candidate.resolve(seg);
			}
			return candidate;
		}
	}

	private static void collectCp(File root, List<String> cp) {
		addIfExists(cp, new File(root, "target/classes"));
		addIfExists(cp, new File(root, "target/test-classes"));
		addIfExists(cp, new File(root, "build/classes/java/main"));
		addIfExists(cp, new File(root, "build/classes/java/test"));
		addIfExists(cp, new File(root, "out/production"));
		addIfExists(cp, new File(root, "out/test"));
		addJarsUnder(new File(root, "target"), cp);
		addJarsUnder(new File(root, "lib"), cp);
		String javaHome = System.getProperty("java.home");
		if (javaHome != null) {
			File modules = new File(javaHome, "jmods");
			if (modules.exists()) {
				File[] jmods = modules.listFiles((d,n) -> n.endsWith(".jmod"));
				if (jmods != null) for (File f : jmods) cp.add(f.getAbsolutePath());
			}
			File rtJar = new File(javaHome, "lib/rt.jar");
			if (rtJar.exists()) cp.add(rtJar.getAbsolutePath());
		}
		String systemClasspath = System.getProperty("java.class.path");
		if (systemClasspath != null) cp.addAll(Arrays.asList(systemClasspath.split(File.pathSeparator)));
	}

	private static void addIfExists(List<String> cp, File f) { if (f.exists()) cp.add(f.getAbsolutePath()); }
	private static void addJarsUnder(File dir, List<String> cp) {
		if (!dir.exists() || !dir.isDirectory()) return;
		File[] jars = dir.listFiles((d,n) -> n.endsWith(".jar"));
		if (jars != null) for (File j : jars) cp.add(j.getAbsolutePath());
		File[] subs = dir.listFiles(File::isDirectory);
		if (subs != null) for (File sd : subs) {
			File[] js = sd.listFiles((d,n) -> n.endsWith(".jar"));
			if (js != null) for (File j : js) cp.add(j.getAbsolutePath());
		}
	}

	private static Map<String, TargetType> indexTypes(List<File> roots, List<String> cp, List<String> sp) throws Exception {
		Map<String, TargetType> map = new HashMap<>();
		for (File root : roots) {
			List<Path> files = new ArrayList<>();
			try (Stream<Path> stream = Files.walk(root.toPath())) {
				stream.filter(p -> p.toString().endsWith(".java")).forEach(files::add);
			}
			for (Path p : files) {
				String src = Files.readString(p, StandardCharsets.UTF_8);
				ASTParser parser = ASTParser.newParser(AST.JLS17);
				parser.setKind(ASTParser.K_COMPILATION_UNIT);
				parser.setSource(src.toCharArray());
				parser.setResolveBindings(true);
				parser.setBindingsRecovery(true);
				parser.setEnvironment(cp.toArray(new String[0]), sp.toArray(new String[0]), null, true);
				parser.setUnitName(p.getFileName().toString());
				CompilationUnit cu = (CompilationUnit) parser.createAST(null);
				cu.accept(new ASTVisitor(true) {
					@Override public boolean visit(TypeDeclaration node) {
						if (node.isInterface()) return true;
						String pkg = cu.getPackage() != null ? cu.getPackage().getName().getFullyQualifiedName() : "";
						String simple = node.getName().getIdentifier();
						String fqn = pkg.isEmpty() ? simple : pkg + "." + simple;
						map.put(fqn, new TargetType(fqn, pkg, simple, p, node));
						return true;
					}
				});
			}
		}
		return map;
	}

	private static final class TargetType {
		final String fqn; final String packageName; final String simpleName; final Path filePath; final TypeDeclaration typeDecl;
		TargetType(String fqn, String pkg, String simple, Path file, TypeDeclaration decl) { this.fqn=fqn; this.packageName=pkg; this.simpleName=simple; this.filePath=file; this.typeDecl=decl; }
	}

	private static final class PomInfo {
		final Path path;
		final org.w3c.dom.Document document;
		final Element projectElement;
		final String groupId;
		final String artifactId;
		final String version;

		PomInfo(Path path, org.w3c.dom.Document document, Element projectElement, String groupId, String artifactId, String version) {
			this.path = path;
			this.document = document;
			this.projectElement = projectElement;
			this.groupId = groupId;
			this.artifactId = artifactId;
			this.version = version;
		}
	}

	private enum SuperSituationKind { ALL_NONE, EXACTLY_ONE_HAS, TWO_OR_MORE_HAVE }

	private static final class SuperSituation {
		final SuperSituationKind kind; final TargetType oneWith;
		SuperSituation(SuperSituationKind k, TargetType o) { this.kind=k; this.oneWith=o; }
	}

	private static final class SuperclassPlacement {
		final NameParts name;
		final Path explicitPath;
		SuperclassPlacement(NameParts name, Path explicitPath) {
			this.name = name;
			this.explicitPath = explicitPath == null ? null : explicitPath.toAbsolutePath().normalize();
		}
	}

	private SuperSituation analyzeSuperSituation(List<TargetType> targets) {
		int has = 0; TargetType one = null;
		for (TargetType t : targets) {
			if (t.typeDecl.getSuperclassType() != null) { has++; if (one == null) one = t; }
		}
		if (has == 0) return new SuperSituation(SuperSituationKind.ALL_NONE, null);
		if (has == 1) return new SuperSituation(SuperSituationKind.EXACTLY_ONE_HAS, one);
		return new SuperSituation(SuperSituationKind.TWO_OR_MORE_HAVE, null);
	}

	private static final class NameParts {
		final String pkg; final String simple;
		NameParts(String pkg, String simple) { this.pkg = pkg == null ? "" : pkg; this.simple = simple; }
		String qualified() { return pkg.isEmpty() ? simple : (pkg + "." + simple); }
		static NameParts fromQualified(String q) {
			int i = q.lastIndexOf('.');
			if (i < 0) return new NameParts("", q);
			return new NameParts(q.substring(0, i), q.substring(i+1));
		}
	}

	/** Resolve a simple or qualified type name to an FQN based on indexed types and a preferred package context. */
	private String resolveTypeNameToFqn(RefEnv env, String typeName, String preferredPackage) {
		if (typeName == null || typeName.isEmpty()) return null;
		if (typeName.contains(".")) return typeName; // already FQN
		// Try preferred package first
		if (preferredPackage != null && !preferredPackage.isEmpty()) {
			String candidate = preferredPackage + "." + typeName;
			if (env.fqnToType.containsKey(candidate)) return candidate;
		}
		// Fallback: any matching simple name
		for (String fqn : env.fqnToType.keySet()) {
			if (fqn.endsWith("." + typeName) || fqn.equals(typeName)) return fqn;
		}
		return null;
	}

	private String resolveExistingSuperclassQualifiedName(RefEnv env, TargetType type) {
		if (type == null || type.typeDecl.getSuperclassType() == null) return null;
		Type superType = type.typeDecl.getSuperclassType();
		ITypeBinding binding = superType.resolveBinding();
		if (binding != null) {
			String qualified = binding.getQualifiedName();
			if (qualified != null && !qualified.isEmpty()) {
				return qualified;
			}
		}
		String rawName = baseTypeName(superType.toString());
		if (rawName == null || rawName.isEmpty()) {
			return null;
		}
		String resolved = resolveTypeNameToFqn(env, rawName, type.packageName);
		if (resolved != null) {
			return resolved;
		}
		ASTNode root = type.typeDecl.getRoot();
		if (root instanceof CompilationUnit) {
			CompilationUnit cu = (CompilationUnit) root;
			List<?> imports = cu.imports();
			String simple = extractSimpleName(rawName);
			for (Object obj : imports) {
				if (!(obj instanceof ImportDeclaration)) continue;
				ImportDeclaration imp = (ImportDeclaration) obj;
				if (imp.isOnDemand()) continue;
				String fqn = imp.getName().getFullyQualifiedName();
				if (fqn.equals(rawName)) {
					return fqn;
				}
				if (simple != null && fqn.endsWith("." + simple)) {
					return fqn;
				}
			}
		}
		return null;
	}

	private String baseTypeName(String typeName) {
		if (typeName == null) return null;
		String stripped = typeName.trim();
		int genericIdx = stripped.indexOf('<');
		if (genericIdx >= 0) stripped = stripped.substring(0, genericIdx);
		int arrayIdx = stripped.indexOf('[');
		if (arrayIdx >= 0) stripped = stripped.substring(0, arrayIdx);
		return stripped.trim();
	}

	private String extractSimpleName(String typeName) {
		if (typeName == null) return null;
		String stripped = baseTypeName(typeName);
		if (stripped == null) return null;
		int dotIdx = stripped.lastIndexOf('.');
		if (dotIdx >= 0 && dotIdx + 1 < stripped.length()) {
			return stripped.substring(dotIdx + 1);
		}
		return stripped;
	}

	private boolean attemptManualImportInsertion(Path filePath, String updated, Collection<String> ensureImports) throws Exception {
		if (ensureImports == null || ensureImports.isEmpty()) {
			return false;
		}
		String content = updated;
		List<String> toAdd = new ArrayList<>();
		for (String qualified : ensureImports) {
			if (qualified == null || qualified.isEmpty()) continue;
			String statement = "import " + qualified + ";";
			if (content.contains(statement)) {
				continue;
			}
			if (!qualified.contains(".")) {
				continue;
			}
			toAdd.add(qualified);
		}
		if (toAdd.isEmpty()) {
			return true; // imports already present
		}
		int lastImportEnd = -1;
		int searchIdx = 0;
		while (true) {
			int idx = content.indexOf("import ", searchIdx);
			if (idx < 0) break;
			int semi = content.indexOf(';', idx);
			if (semi < 0) break;
			lastImportEnd = semi + 1;
			searchIdx = semi + 1;
		}
		StringBuilder builder = new StringBuilder();
		if (lastImportEnd >= 0) {
			builder.append(content, 0, lastImportEnd);
			if (lastImportEnd == content.length() || content.charAt(lastImportEnd) != '\n') {
				builder.append('\n');
			}
			for (String qualified : toAdd) {
				builder.append("import ").append(qualified).append(";\n");
			}
			if (lastImportEnd < content.length()) {
				if (content.charAt(lastImportEnd) != '\n') {
					builder.append('\n');
				}
				builder.append(content.substring(lastImportEnd));
			}
		} else {
			int pkgIdx = content.indexOf("package ");
			int insertIdx = 0;
			if (pkgIdx >= 0) {
				int semi = content.indexOf(';', pkgIdx);
				if (semi >= 0) {
					insertIdx = semi + 1;
					while (insertIdx < content.length()) {
						char ch = content.charAt(insertIdx);
						if (ch == '\r' || ch == '\n') {
							insertIdx++;
						} else {
							break;
						}
					}
				}
			}
			builder.append(content, 0, insertIdx);
			if (insertIdx > 0) {
				builder.append('\n').append('\n');
			}
			for (String qualified : toAdd) {
				builder.append("import ").append(qualified).append(";\n");
			}
			if (insertIdx == 0) {
				builder.append('\n');
			}
			builder.append(content.substring(insertIdx));
		}
		Files.writeString(filePath, builder.toString(), StandardCharsets.UTF_8);
		return true;
	}
}
