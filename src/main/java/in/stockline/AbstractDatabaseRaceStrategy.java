package in.stockline;

import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

import static in.stockline.Models.*;

abstract class AbstractDatabaseRaceStrategy implements RaceStrategy {
    record Reservation(Stock stock, int quantity) {}

    boolean transact(RaceSimulationDatabase database, OrderInput order, Runnable afterRead,
                    RaceCounters counters, boolean lockRows, boolean unsafeAbsoluteWrite) {
        TransactionTemplate transaction = new TransactionTemplate(database.transactionManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        long transactionStarted = System.nanoTime();
        try {
            Boolean accepted = transaction.execute(status -> {
                long readStarted = System.nanoTime();
                List<Stock> stock;
                try {
                    stock = lockRows
                        ? database.store.stockForSkuForUpdate("RACE-01")
                        : database.store.stockForSku("RACE-01");
                } finally {
                    if (lockRows) counters.lockWaitNanos.add(System.nanoTime() - readStarted);
                }
                List<Reservation> plan = plan(stock, order.lines().get(0).quantity());
                afterRead.run();
                if (plan == null) return false;

                UUID orderId = UUID.randomUUID();
                UUID requestKey = order.requestKey();
                OffsetDateTime now = InventoryService.now();
                String customer = order.customer();
                String fingerprint = fingerprint(customer + "|RACE-01|" + order.lines().get(0).quantity());
                database.store.insertOrder(orderId, requestKey, fingerprint, customer, now);
                database.store.insertOperation(orderId, null, "ORDER_RESERVATION", fingerprint, now);
                for (Reservation reservation : plan) {
                    Stock before = reservation.stock();
                    int quantity = reservation.quantity();
                    if (unsafeAbsoluteWrite) {
                        database.store.updateStockAbsolute(before, before.available() - quantity,
                            before.reserved() + quantity);
                    } else if (!database.store.updateStock(before, -quantity, quantity, 0, 0, 0)) {
                        throw new IllegalStateException("Locked stock row changed before reservation.");
                    }
                    database.store.insertAllocation(orderId,
                        new Allocation("RACE-01", before.warehouseCode(), quantity));
                    database.store.insertMovement(new InventoryMovement(UUID.randomUUID(), "RACE-01",
                        before.warehouseCode(), MovementType.RESERVATION, StockBucket.AVAILABLE, -quantity,
                        before.available(), before.available() - quantity, orderId, null, null,
                        "Isolated concurrency simulation reservation", "simulation", now, orderId));
                    database.store.insertMovement(new InventoryMovement(UUID.randomUUID(), "RACE-01",
                        before.warehouseCode(), MovementType.RESERVATION, StockBucket.RESERVED, quantity,
                        before.reserved(), before.reserved() + quantity, orderId, null, null,
                        "Isolated concurrency simulation reservation", "simulation", now, orderId));
                }
                database.store.enqueue(orderId, now);
                return true;
            });
            return Boolean.TRUE.equals(accepted);
        } finally {
            counters.databaseNanos.add(System.nanoTime() - transactionStarted);
        }
    }

    List<Reservation> plan(List<Stock> candidates, int requested) {
        int remaining = requested;
        List<Reservation> reservations = new ArrayList<>();
        for (Stock stock : candidates) {
            int quantity = (int) Math.min(remaining, stock.available());
            if (quantity > 0) {
                reservations.add(new Reservation(stock, quantity));
                remaining -= quantity;
            }
            if (remaining == 0) return reservations;
        }
        return null;
    }

    static String fingerprint(String payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static void backoff(int attempt) {
        long cap = Math.min(100_000_000L, 2_000_000L << Math.min(attempt - 1, 5));
        LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(cap + 1));
    }

    static boolean isDeadlock(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof java.sql.SQLException sql && "40001".equals(sql.getSQLState())) return true;
            String message = current.getMessage();
            if (message != null && message.toLowerCase().contains("deadlock")) return true;
        }
        return false;
    }

    static boolean retryable(RuntimeException failure) {
        return failure instanceof ConcurrencyFailureException || failure instanceof QueryTimeoutException;
    }
}
