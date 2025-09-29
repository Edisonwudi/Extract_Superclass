package com.refactoring.extractsuperclass;

import java.util.List;

/**
 * Request object for Extract Superclass refactoring operation.
 */
public final class ExtractSuperclassRequest {
    private final List<String> classNames;
    private final String superQualifiedName;
    private final String targetPackage;
    private final boolean dryRun;
    private final boolean verbose;

    /**
     * Creates a new ExtractSuperclassRequest with the given parameters.
     * 
     * @param classNames List of class names to extract superclass for
     * @param superQualifiedName Optional fully qualified name for the superclass
     * @param targetPackage Optional target package for the superclass
     * @param dryRun If true, perform analysis without making changes
     * @param verbose If true, enable verbose logging
     */
    public ExtractSuperclassRequest(List<String> classNames, String superQualifiedName, String targetPackage, boolean dryRun, boolean verbose) {
        if (classNames == null || classNames.isEmpty()) {
            throw new IllegalArgumentException("classNames cannot be null or empty");
        }
        this.classNames = classNames;
        this.superQualifiedName = superQualifiedName;
        this.targetPackage = targetPackage;
        this.dryRun = dryRun;
        this.verbose = verbose;
    }

    public List<String> classNames() {
        return classNames;
    }

    public String superQualifiedName() {
        return superQualifiedName;
    }

    public String targetPackage() {
        return targetPackage;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public boolean verbose() {
        return verbose;
    }
}
