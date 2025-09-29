package com.refactoring.extractsuperclass;

import java.util.List;

/**
 * Result object for Extract Superclass refactoring operation.
 */
public final class ExtractSuperclassResult {
    private final boolean success;
    private final String errorMessage;
    private final String superclassQualifiedName;
    private final List<String> modifiedFiles;
    private final long executionTimeMs;

    private ExtractSuperclassResult(Builder builder) {
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
        this.superclassQualifiedName = builder.superclassQualifiedName;
        this.modifiedFiles = builder.modifiedFiles;
        this.executionTimeMs = builder.executionTimeMs;
    }

    /**
     * Creates a successful result.
     */
    public static Builder success() {
        return new Builder().success(true);
    }

    /**
     * Creates a failed result with the given error message.
     */
    public static Builder failure(String errorMessage) {
        return new Builder().success(false).errorMessage(errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getSuperclassQualifiedName() {
        return superclassQualifiedName;
    }

    public List<String> getModifiedFiles() {
        return modifiedFiles;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    /**
     * Builder for ExtractSuperclassResult.
     */
    public static class Builder {
        private boolean success;
        private String errorMessage;
        private String superclassQualifiedName;
        private List<String> modifiedFiles;
        private long executionTimeMs;

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder superclassQualifiedName(String superclassQualifiedName) {
            this.superclassQualifiedName = superclassQualifiedName;
            return this;
        }

        public Builder modifiedFiles(List<String> modifiedFiles) {
            this.modifiedFiles = modifiedFiles;
            return this;
        }

        public Builder executionTimeMs(long executionTimeMs) {
            this.executionTimeMs = executionTimeMs;
            return this;
        }

        public ExtractSuperclassResult build() {
            return new ExtractSuperclassResult(this);
        }
    }
}

