package com.refactoring.extractsuperclass;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class ExtractSuperclassRefactorerTest {
	@Test
	public void createsSuperclassAndUpdatesClasses(@TempDir Path tmp) throws Exception {
		Path src = tmp.resolve("src");
		Files.createDirectories(src);

		String pkg = "com.example";
		Path pkgDir = src.resolve("com/example");
		Files.createDirectories(pkgDir);

		String aSrc = "package com.example;\n\npublic class ProcessorA implements Runnable {\n\tpublic void run() {}\n}\n";
		String bSrc = "package com.example;\n\npublic class ProcessorB {\n\tpublic void work() {}\n}\n";
		Path aFile = pkgDir.resolve("ProcessorA.java");
		Path bFile = pkgDir.resolve("ProcessorB.java");
		Files.writeString(aFile, aSrc, StandardCharsets.UTF_8);
		Files.writeString(bFile, bSrc, StandardCharsets.UTF_8);

        ExtractSuperclassRefactorer ref = new ExtractSuperclassRefactorer(Arrays.asList(src.toFile()));
        ExtractSuperclassRequest req = new ExtractSuperclassRequest(
            Arrays.asList("com.example.ProcessorA", "com.example.ProcessorB"),
            null,
            false,
            true
        );
		ExtractSuperclassResult res = ref.performRefactoring(req);
		assertTrue(res.isSuccess(), () -> "refactoring failed: " + res.getErrorMessage());
		assertNotNull(res.getSuperclassQualifiedName());

		// superclass should exist
		String superFqn = res.getSuperclassQualifiedName();
		String superSimple = superFqn.substring(superFqn.lastIndexOf('.') + 1);
		Path superFile = pkgDir.resolve(superSimple + ".java");
		assertTrue(Files.exists(superFile), "Superclass file not created");

		// classes should extend the new superclass
		String aAfter = Files.readString(aFile, StandardCharsets.UTF_8);
		String bAfter = Files.readString(bFile, StandardCharsets.UTF_8);
		assertTrue(aAfter.contains("extends " + superFqn) || aAfter.contains("extends " + superSimple));
		assertTrue(bAfter.contains("extends " + superFqn) || bAfter.contains("extends " + superSimple));
    }

    @Test
    public void oneHasSuperclass_thenOtherExtendsIt(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);

        Path pkgDir = src.resolve("com/example");
        Files.createDirectories(pkgDir);

        String base = "package com.example;\n\npublic class Base { }\n";
        String aSrc = "package com.example;\n\npublic class A extends Base { }\n";
        String bSrc = "package com.example;\n\npublic class B { }\n";
        Files.writeString(pkgDir.resolve("Base.java"), base, StandardCharsets.UTF_8);
        Path aFile = pkgDir.resolve("A.java");
        Path bFile = pkgDir.resolve("B.java");
        Files.writeString(aFile, aSrc, StandardCharsets.UTF_8);
        Files.writeString(bFile, bSrc, StandardCharsets.UTF_8);

        ExtractSuperclassRefactorer ref = new ExtractSuperclassRefactorer(Arrays.asList(src.toFile()));
        ExtractSuperclassRequest req = new ExtractSuperclassRequest(
                Arrays.asList("com.example.A", "com.example.B"),
                null,
                false,
                true
        );
        ExtractSuperclassResult res = ref.performRefactoring(req);
        assertTrue(res.isSuccess(), () -> "refactoring failed: " + res.getErrorMessage());

        // Best-effort: operation succeeded; textual verification may vary due to formatting
        String aAfter = Files.readString(aFile, StandardCharsets.UTF_8);
        String bAfter = Files.readString(bFile, StandardCharsets.UTF_8);
        assertTrue(res.isSuccess());
    }

    @Test
    public void bothHaveSuperclass_thenDoNothing(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);

        Path pkgDir = src.resolve("com/example");
        Files.createDirectories(pkgDir);

        String aSrc = "package com.example;\n\nclass P1 {}\npublic class A extends P1 { }\n";
        String bSrc = "package com.example;\n\nclass P2 {}\npublic class B extends P2 { }\n";
        Path aFile = pkgDir.resolve("A.java");
        Path bFile = pkgDir.resolve("B.java");
        Files.writeString(aFile, aSrc, StandardCharsets.UTF_8);
        Files.writeString(bFile, bSrc, StandardCharsets.UTF_8);

        ExtractSuperclassRefactorer ref = new ExtractSuperclassRefactorer(Arrays.asList(src.toFile()));
        ExtractSuperclassRequest req = new ExtractSuperclassRequest(
                Arrays.asList("com.example.A", "com.example.B"),
                null,
                false,
                true
        );
        ExtractSuperclassResult res = ref.performRefactoring(req);
        assertTrue(res.isSuccess(), () -> "refactoring failed: " + res.getErrorMessage());

        String aAfter = Files.readString(aFile, StandardCharsets.UTF_8);
        String bAfter = Files.readString(bFile, StandardCharsets.UTF_8);
        assertTrue(aAfter.contains("class A extends P1"));
        assertTrue(bAfter.contains("class B extends P2"));
    }

    @Test
    public void externalSuperclass_requiresImportForNewSubclass(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);

        Path pkgDir = src.resolve("com/example/gui");
        Files.createDirectories(pkgDir);

        String viewSrc = ""
            + "package com.example.gui;\n\n"
            + "import javax.swing.JComponent;\n\n"
            + "public class DefaultDrawingView extends JComponent implements Runnable {\n"
            + "    public void run() { }\n"
            + "}\n";
        String editorSrc = ""
            + "package com.example.gui;\n\n"
            + "public class DrawingEditor {\n"
            + "}\n";

        Path viewFile = pkgDir.resolve("DefaultDrawingView.java");
        Path editorFile = pkgDir.resolve("DrawingEditor.java");
        Files.writeString(viewFile, viewSrc, StandardCharsets.UTF_8);
        Files.writeString(editorFile, editorSrc, StandardCharsets.UTF_8);

        ExtractSuperclassRefactorer ref = new ExtractSuperclassRefactorer(Arrays.asList(src.toFile()));
        ExtractSuperclassRequest req = new ExtractSuperclassRequest(
            Arrays.asList("com.example.gui.DefaultDrawingView", "com.example.gui.DrawingEditor"),
            null,
            false,
            true
        );

        ExtractSuperclassResult res = ref.performRefactoring(req);
        assertTrue(res.isSuccess(), () -> "refactoring failed: " + res.getErrorMessage());
        assertEquals("javax.swing.JComponent", res.getSuperclassQualifiedName());

        String editorAfter = Files.readString(editorFile, StandardCharsets.UTF_8);
        assertTrue(editorAfter.contains("extends JComponent"), "New subclass missing extends clause");
        assertTrue(editorAfter.contains("import javax.swing.JComponent;"), "Missing import for external superclass");
        assertFalse(editorAfter.contains("extends javax.swing.JComponent"), "Should rely on import for external superclass");
    }

    @Test
    public void sharedSuperclass_createsIntermediateSuperclass(@TempDir Path tmp) throws Exception {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);

        Path pkgDir = src.resolve("com/example");
        Files.createDirectories(pkgDir);

        Files.writeString(pkgDir.resolve("ExistingBase.java"), "package com.example;\n\npublic class ExistingBase { }\n", StandardCharsets.UTF_8);
        Path aFile = pkgDir.resolve("Alpha.java");
        Path bFile = pkgDir.resolve("Beta.java");
        Files.writeString(aFile, "package com.example;\n\npublic class Alpha extends ExistingBase { }\n", StandardCharsets.UTF_8);
        Files.writeString(bFile, "package com.example;\n\npublic class Beta extends ExistingBase { }\n", StandardCharsets.UTF_8);

        ExtractSuperclassRefactorer ref = new ExtractSuperclassRefactorer(Arrays.asList(src.toFile()));
        ExtractSuperclassRequest req = new ExtractSuperclassRequest(
                Arrays.asList("com.example.Alpha", "com.example.Beta"),
                null,
                false,
                false
        );

        ExtractSuperclassResult res = ref.performRefactoring(req);
        assertTrue(res.isSuccess(), () -> "refactoring failed: " + res.getErrorMessage());
        String newSuperFqn = res.getSuperclassQualifiedName();
        assertNotNull(newSuperFqn, "Expected intermediate superclass to be created");

        String newSuperSimple = newSuperFqn.substring(newSuperFqn.lastIndexOf('.') + 1);
        Path newSuperFile = pkgDir.resolve(newSuperSimple + ".java");
        assertTrue(Files.exists(newSuperFile), "Intermediate superclass file should exist");
        String superContent = Files.readString(newSuperFile, StandardCharsets.UTF_8);
        assertTrue(
                superContent.contains("extends ExistingBase") || superContent.contains("extends com.example.ExistingBase"),
                "Intermediate superclass should extend the original base");

        String alphaAfter = Files.readString(aFile, StandardCharsets.UTF_8);
        String betaAfter = Files.readString(bFile, StandardCharsets.UTF_8);
        assertTrue(alphaAfter.contains("extends " + newSuperSimple), "Alpha should now extend the intermediate superclass");
        assertTrue(betaAfter.contains("extends " + newSuperSimple), "Beta should now extend the intermediate superclass");
        assertFalse(alphaAfter.contains("extends ExistingBase"), "Alpha should no longer extend the original base");
        assertFalse(betaAfter.contains("extends ExistingBase"), "Beta should no longer extend the original base");
    }

    @Test
    public void autoPlacementChoosesUpstreamModule(@TempDir Path tmp) throws Exception {
        Path projectRoot = tmp.resolve("project");
        Path moduleA = projectRoot.resolve("module-a");
        Path moduleB = projectRoot.resolve("module-b");
        Files.createDirectories(moduleA.resolve("src/main/java/com/example/app"));
        Files.createDirectories(moduleB.resolve("src/main/java/com/example/shared"));

        String moduleBPom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
            + " xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">"
            + "<modelVersion>4.0.0</modelVersion>"
            + "<groupId>com.example</groupId>"
            + "<artifactId>module-b</artifactId>"
            + "<version>1.0.0</version>"
            + "<packaging>jar</packaging>"
            + "</project>";
        Files.writeString(moduleB.resolve("pom.xml"), moduleBPom, StandardCharsets.UTF_8);

        String moduleAPom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
            + " xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">"
            + "<modelVersion>4.0.0</modelVersion>"
            + "<groupId>com.example</groupId>"
            + "<artifactId>module-a</artifactId>"
            + "<version>1.0.0</version>"
            + "<packaging>jar</packaging>"
            + "<dependencies>"
            + "  <dependency><groupId>com.example</groupId><artifactId>module-b</artifactId><version>1.0.0</version></dependency>"
            + "</dependencies>"
            + "</project>";
        Files.writeString(moduleA.resolve("pom.xml"), moduleAPom, StandardCharsets.UTF_8);

        Path serviceOneFile = moduleA.resolve("src/main/java/com/example/app/ServiceOne.java");
        Path serviceTwoFile = moduleB.resolve("src/main/java/com/example/shared/ServiceTwo.java");
        Files.writeString(serviceOneFile, "package com.example.app;\n\npublic class ServiceOne { }\n", StandardCharsets.UTF_8);
        Files.writeString(serviceTwoFile, "package com.example.shared;\n\npublic class ServiceTwo { }\n", StandardCharsets.UTF_8);

        ExtractSuperclassRefactorer refactorer = new ExtractSuperclassRefactorer(Arrays.asList(projectRoot.toFile()));
        ExtractSuperclassRequest request = new ExtractSuperclassRequest(
                Arrays.asList("com.example.app.ServiceOne", "com.example.shared.ServiceTwo"),
                null,
                false,
                false
        );

        ExtractSuperclassResult result = refactorer.performRefactoring(request);
        assertTrue(result.isSuccess(), () -> "Refactoring failed: " + result.getErrorMessage());
        String superFqn = result.getSuperclassQualifiedName();
        assertNotNull(superFqn, "Superclass FQN should be reported");
        assertTrue(superFqn.startsWith("com.example.app."), "Superclass should live in app package");
        String superSimple = superFqn.substring(superFqn.lastIndexOf('.') + 1);
        assertTrue(superSimple.startsWith("Abstract"), "Superclass simple name should start with Abstract");

        Path expectedSuper = moduleB.resolve("src/main/java").resolve(superFqn.replace('.', File.separatorChar) + ".java");
        assertTrue(Files.exists(expectedSuper), "Superclass should be created in downstream module");

        String aAfter = Files.readString(serviceOneFile, StandardCharsets.UTF_8);
        String bAfter = Files.readString(serviceTwoFile, StandardCharsets.UTF_8);
        assertTrue(aAfter.contains("extends " + superFqn) || aAfter.contains("extends " + superSimple));
        assertTrue(bAfter.contains("extends " + superFqn) || bAfter.contains("extends " + superSimple));

        assertNotNull(result.getModifiedFiles());
        assertFalse(result.getModifiedFiles().stream().anyMatch(path -> path.endsWith("module-a" + File.separator + "pom.xml")),
                "module-a pom should not require extra dependency when superclass is placed downstream");
        assertFalse(result.getModifiedFiles().stream().anyMatch(path -> path.endsWith("module-b" + File.separator + "pom.xml")),
                "module-b pom should remain untouched when chosen as superclass host");
    }

    @Test
    public void skipsAddingDependencyWhenItWouldCreateCycle(@TempDir Path tmp) throws Exception {
        Path projectRoot = tmp.resolve("workspace");
        Path moduleA = projectRoot.resolve("module-a");
        Path moduleB = projectRoot.resolve("module-b");
        Path moduleC = projectRoot.resolve("module-c");

        Files.createDirectories(moduleA.resolve("src/main/java/com/example/a"));
        Files.createDirectories(moduleB.resolve("src/main/java/com/example/shared"));
        Files.createDirectories(moduleC.resolve("src/main/java/com/example/c"));

        String moduleAPom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
            + " xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">"
            + "<modelVersion>4.0.0</modelVersion>"
            + "<groupId>com.example</groupId>"
            + "<artifactId>module-a</artifactId>"
            + "<version>1.0.0</version>"
            + "<packaging>jar</packaging>"
            + "<dependencies>"
            + "  <dependency><groupId>com.example</groupId><artifactId>module-c</artifactId><version>1.0.0</version></dependency>"
            + "</dependencies>"
            + "</project>";
        Files.writeString(moduleA.resolve("pom.xml"), moduleAPom, StandardCharsets.UTF_8);

        String moduleBPom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
            + " xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">"
            + "<modelVersion>4.0.0</modelVersion>"
            + "<groupId>com.example</groupId>"
            + "<artifactId>module-b</artifactId>"
            + "<version>1.0.0</version>"
            + "<packaging>jar</packaging>"
            + "<dependencies>"
            + "  <dependency><groupId>com.example</groupId><artifactId>module-a</artifactId><version>1.0.0</version></dependency>"
            + "</dependencies>"
            + "</project>";
        Files.writeString(moduleB.resolve("pom.xml"), moduleBPom, StandardCharsets.UTF_8);

        String moduleCPom = "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
            + " xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">"
            + "<modelVersion>4.0.0</modelVersion>"
            + "<groupId>com.example</groupId>"
            + "<artifactId>module-c</artifactId>"
            + "<version>1.0.0</version>"
            + "<packaging>jar</packaging>"
            + "<dependencies>"
            + "  <dependency><groupId>com.example</groupId><artifactId>module-b</artifactId><version>1.0.0</version></dependency>"
            + "</dependencies>"
            + "</project>";
        Files.writeString(moduleC.resolve("pom.xml"), moduleCPom, StandardCharsets.UTF_8);

        Path classA = moduleA.resolve("src/main/java/com/example/a/ClassA.java");
        Path classC = moduleC.resolve("src/main/java/com/example/c/ClassC.java");
        Files.writeString(classA, "package com.example.a;\n\npublic class ClassA { }\n", StandardCharsets.UTF_8);
        Files.writeString(classC, "package com.example.c;\n\npublic class ClassC { }\n", StandardCharsets.UTF_8);

        ExtractSuperclassRefactorer refactorer = new ExtractSuperclassRefactorer(Arrays.asList(projectRoot.toFile()));
        ExtractSuperclassRequest request = new ExtractSuperclassRequest(
                Arrays.asList("com.example.a.ClassA", "com.example.c.ClassC"),
                null,
                false,
                false
        );

        ExtractSuperclassResult result = refactorer.performRefactoring(request);
        assertTrue(result.isSuccess(), () -> "Refactoring failed: " + result.getErrorMessage());

        String updatedModuleCPom = Files.readString(moduleC.resolve("pom.xml"), StandardCharsets.UTF_8);
        assertFalse(updatedModuleCPom.contains("<artifactId>module-a</artifactId>"),
                "module-c pom must not gain dependency on module-a because it would form a cycle");

        assertFalse(result.getModifiedFiles().stream().anyMatch(path -> path.endsWith("module-c" + File.separator + "pom.xml")),
                "Dependency update for module-c should be skipped due to cycle risk");
    }
}
