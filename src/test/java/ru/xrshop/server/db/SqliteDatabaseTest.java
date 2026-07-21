package ru.xrshop.server.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.xrshop.common.dto.PurchaseResultCode;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SqliteDatabaseTest {
    @TempDir Path temp;
    private SqliteDatabase db;
    private DatabaseExecutor executor;
    private UUID player;

    @BeforeEach void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC"); player = UUID.randomUUID();
        db = new SqliteDatabase(temp.resolve("xrshop.db"), 1_000, true, 5_000); db.initialize();
        executor = new DatabaseExecutor(32);
    }
    @AfterEach void tearDown() { executor.close(); db.close(); }

    @Test void insufficientBalanceDoesNotDebit() throws Exception {
        var result = db.reserve(UUID.randomUUID(), player, "resources", "diamond", 10, 1, 0, 0, 0);
        assertEquals(PurchaseResultCode.INSUFFICIENT_FUNDS, result.code()); assertEquals(0, db.balance(player));
    }

    @Test void longOverflowAndMaximumAreRejected() throws Exception {
        db.setBalance(player, 1_000, "test", "TEST");
        assertThrows(ArithmeticException.class, () -> db.changeBalance(player, Long.MAX_VALUE, "overflow", "TEST", null));
        assertEquals(1_000, db.balance(player));
    }

    @Test void concurrentDebitThroughBoundedExecutorOnlySucceedsOnce() throws Exception {
        db.changeBalance(player, 100, "seed", "TEST", null);
        CompletableFuture<SqliteDatabase.ReserveResult> first = executor.submit(() -> db.reserve(UUID.randomUUID(), player, "c", "p", 80, 1, 0, 0, 0));
        CompletableFuture<SqliteDatabase.ReserveResult> second = executor.submit(() -> db.reserve(UUID.randomUUID(), player, "c", "p", 80, 1, 0, 0, 0));
        var a = first.get(); var b = second.get();
        assertEquals(1, java.util.stream.Stream.of(a, b).filter(r -> r.code() == PurchaseResultCode.SUCCESS).count());
        assertEquals(20, db.balance(player));
    }

    @Test void repeatedExternalTransactionIsIdempotent() throws Exception {
        assertTrue(db.changeBalance(player, 50, "payment", "SITE", "order-1").applied());
        assertFalse(db.changeBalance(player, 50, "payment", "SITE", "order-1").applied());
        assertEquals(50, db.balance(player));
    }

    @Test void partialCommandSequenceBecomesReviewRequired() throws Exception {
        db.changeBalance(player, 100, "seed", "TEST", null); UUID tx = UUID.randomUUID();
        assertEquals(PurchaseResultCode.SUCCESS, db.reserve(tx, player, "c", "p", 20, 1, 0, 0, 0).code());
        db.markExecuting(tx); db.recordCommand(tx, 0, "say one", true, "result=1"); db.recordCommand(tx, 1, "bad", false, "result=0");
        db.reviewRequired(tx, "частичное выполнение");
        assertEquals(TransactionState.REVIEW_REQUIRED, db.purchase(tx).orElseThrow().state());
    }

    @Test void executingRecoveryNeverRunsCommandsAgain() throws Exception {
        db.changeBalance(player, 100, "seed", "TEST", null); UUID tx = UUID.randomUUID();
        db.reserve(tx, player, "c", "p", 20, 1, 0, 0, 0); db.markExecuting(tx);
        assertEquals(1, db.recoverExecuting()); assertEquals(TransactionState.REVIEW_REQUIRED, db.purchase(tx).orElseThrow().state());
    }
}
