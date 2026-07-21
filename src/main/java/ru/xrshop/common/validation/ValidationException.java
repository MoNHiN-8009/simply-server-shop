package ru.xrshop.common.validation;

import java.util.List;

public final class ValidationException extends Exception {
    private final List<ValidationIssue> issues;

    public ValidationException(List<ValidationIssue> issues) {
        super(issues.isEmpty() ? "Неизвестная ошибка конфигурации" : issues.get(0).toString());
        this.issues = List.copyOf(issues);
    }

    public List<ValidationIssue> issues() {
        return issues;
    }
}
