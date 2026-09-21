package com.autotest.platform.importer;

public final class ImportParseException extends IllegalArgumentException {
    private final String path;

    public ImportParseException(String path, String message) {
        super(message);
        this.path = path == null || path.isBlank() ? "$" : path;
    }

    public String path() {
        return path;
    }
}
