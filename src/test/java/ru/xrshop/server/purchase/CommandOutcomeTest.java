package ru.xrshop.server.purchase;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandOutcomeTest {
    @Test void zeroNumericResultCanStillBeSuccessful() {
        PurchaseService.CommandOutcome outcome = new PurchaseService.CommandOutcome();
        outcome.accept(true, 0);
        assertTrue(outcome.successful());
        assertTrue(outcome.detail(0).contains("successes=1"));
    }

    @Test void missingOrFailedCallbackIsNotSuccessful() {
        PurchaseService.CommandOutcome missing = new PurchaseService.CommandOutcome();
        assertFalse(missing.successful());

        PurchaseService.CommandOutcome mixed = new PurchaseService.CommandOutcome();
        mixed.accept(true, 1);
        mixed.accept(false, 0);
        assertFalse(mixed.successful());
    }
}
