package ru.xrshop.common.validation;

public record ValidationIssue(String path, String message) {
    @Override
    public String toString() {
        return path + ": " + message;
    }
}
