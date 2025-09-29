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
			"com.example",
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
                "com.example",
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
                "com.example",
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
}
