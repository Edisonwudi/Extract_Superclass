package com.refactoring.extractsuperclass;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Command-line interface for Extract Superclass refactoring tool.
 * 
 * Usage: extractsuperclass --projectRoot /path/to/project --classNames com.example.ClassA,com.example.ClassB
 */
@Command(
    name = "extractsuperclass",
    description = "Extract Superclass refactoring tool - creates a common abstract superclass for selected classes. The superclass will be placed in the same directory as the first target class.",
    mixinStandardHelpOptions = true,
    version = "1.0.0"
)
public class ExtractSuperclassCLI implements Callable<Integer> {
    private static final Logger logger = LoggerFactory.getLogger(ExtractSuperclassCLI.class);

    @Parameters(
        index = "0",
        description = "Project root directory path(s), comma-separated for multiple paths",
        arity = "1..*"
    )
    private String[] projectRoots;

    @Option(
        names = {"--classNames", "-c"},
        description = "Class names to extract superclass for, comma-separated",
        required = true
    )
    private String classNames;

    @Option(
        names = {"--superName", "-s"},
        description = "Fully qualified name for the new superclass (optional)"
    )
    private String superQualifiedName;

    @Option(
        names = {"--absoluteOutputPath", "--absolute-output-path"},
        description = "Absolute directory or file path for the generated superclass",
        required = true
    )
    private String absoluteOutputPath;

    @Option(
        names = {"--dryRun", "-d"},
        description = "Perform analysis without making changes"
    )
    private boolean dryRun = false;

    @Option(
        names = {"--verbose", "-v"},
        description = "Enable verbose logging"
    )
    private boolean verbose = false;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new ExtractSuperclassCLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        try {
            // Parse project roots
            List<File> projectRootFiles = new ArrayList<>();
            for (String root : projectRoots) {
                File projectRoot = new File(root);
                if (!projectRoot.exists()) {
                    logger.error("Project root does not exist: {}", root);
                    return 1;
                }
                if (!projectRoot.isDirectory()) {
                    logger.error("Project root is not a directory: {}", root);
                    return 1;
                }
                projectRootFiles.add(projectRoot);
            }

            // Parse class names
            List<String> classNamesList = Arrays.asList(classNames.split(","));
            for (String className : classNamesList) {
                if (className.trim().isEmpty()) {
                    logger.error("Empty class name found in classNames");
                    return 1;
                }
            }

            // Create request
            if (absoluteOutputPath == null || absoluteOutputPath.trim().isEmpty()) {
                logger.error("absoluteOutputPath must be provided and cannot be empty");
                return 1;
            }
            absoluteOutputPath = absoluteOutputPath.trim();

            ExtractSuperclassRequest request = new ExtractSuperclassRequest(
                classNamesList,
                superQualifiedName,
                absoluteOutputPath,
                dryRun,
                verbose
            );

            // Perform refactoring
            ExtractSuperclassRefactorer refactorer = new ExtractSuperclassRefactorer(projectRootFiles);
            ExtractSuperclassResult result = refactorer.performRefactoring(request);

            // Output results
            if (result.isSuccess()) {
                logger.info("Extract Superclass refactoring completed successfully");
                if (result.getSuperclassQualifiedName() != null) {
                    logger.info("Superclass: {}", result.getSuperclassQualifiedName());
                }
                if (result.getModifiedFiles() != null && !result.getModifiedFiles().isEmpty()) {
                    logger.info("Modified files:");
                    for (String file : result.getModifiedFiles()) {
                        logger.info("  - {}", file);
                    }
                }
                logger.info("Execution time: {}ms", result.getExecutionTimeMs());
                return 0;
            } else {
                logger.error("Extract Superclass refactoring failed: {}", result.getErrorMessage());
                return 1;
            }

        } catch (Exception e) {
            logger.error("Unexpected error during refactoring", e);
            return 1;
        }
    }
}
