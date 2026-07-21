package ru.xrshop.common.dto;

public enum PurchaseResultCode {
    SUCCESS,
    PROCESSING,
    NO_PERMISSION,
    INVALID_ID,
    NOT_FOUND,
    DISABLED,
    INSUFFICIENT_FUNDS,
    LIMIT_REACHED,
    COOLDOWN,
    ACTIVE_PURCHASE,
    RATE_LIMITED,
    INVALID_COMMAND,
    STORE_UPDATED,
    REVIEW_REQUIRED,
    INTERNAL_ERROR
}
