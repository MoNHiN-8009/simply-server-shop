package ru.xrshop.server.db;

public enum TransactionState {
    CREATED,
    FUNDS_RESERVED,
    EXECUTING,
    COMPLETED,
    REJECTED,
    REFUNDED,
    REVIEW_REQUIRED
}
