package com.refactoring.extractsuperclass;

import java.util.List;

/**
 * Request object for Extract Superclass refactoring operation.
 */
public final class ExtractSuperclassRequest {
    private final List<String> classNames;
    private final String superQualifiedName;
    private final String absoluteOutputPath;
    private final boolean dryRun;
    private final boolean verbose;

    /**
     * Creates a new ExtractSuperclassRequest with the given parameters.
     * 
     * @param classNames List of class names to extract superclass for
     * @param superQualifiedName Optional fully qualified name for the superclass
     * @param absoluteOutputPath Required absolute path for placing the generated superclass file
     * @param dryRun If true, perform analysis without making changes
     * @param verbose If true, enable verbose logging
     */
    public ExtractSuperclassRequest(List<String> classNames, String superQualifiedName, String absoluteOutputPath, boolean dryRun, boolean verbose) {
        if (classNames == null || classNames.isEmpty()) {
            throw new IllegalArgumentException("classNames cannot be null or empty");
        }
        if (absoluteOutputPath == null || absoluteOutputPath.trim().isEmpty()) {
            throw new IllegalArgumentException("absoluteOutputPath cannot be null or empty");
        }
        this.classNames = classNames;
        this.superQualifiedName = superQualifiedName;
        this.absoluteOutputPath = absoluteOutputPath.trim();
        this.dryRun = dryRun;
        this.verbose = verbose;
    }

    public List<String> classNames() {
        return classNames;
    }

    public String superQualifiedName() {
        return superQualifiedName;
    }

    public String absoluteOutputPath() {
        return absoluteOutputPath;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public boolean verbose() {
        return verbose;
    }
}
