package in.stockline.service.inventory;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

import in.stockline.persistence.repository.Store;
import static in.stockline.domain.model.Models.*;

/** Records one-time opening balances for databases created before the movement ledger existed. */
@Component
public class InventoryLedgerBootstrap implements ApplicationRunner {
    private final Store store;
    private final TransactionTemplate transaction;

    public InventoryLedgerBootstrap(Store store, PlatformTransactionManager manager) {
        this.store = store;
        this.transaction = new TransactionTemplate(manager);
    }

    @Override
    public void run(ApplicationArguments args) {
        initialize();
    }

    public void initialize() {
        transaction.executeWithoutResult(status -> {
            for (Stock stock : store.inventory()) {
                UUID operationId = UUID.nameUUIDFromBytes(("opening|" + stock.sku() + "|" + stock.warehouseCode())
                    .getBytes(StandardCharsets.UTF_8));
                OffsetDateTime now = InventoryService.now();
                if (!store.hasMovements(stock.sku(), stock.warehouseCode()) && !store.hasOperation(operationId)) {
                    String baseline = stock.toString();
                    store.insertOperation(operationId, null, "INITIAL_STOCK", hash(baseline), now);
                    addOpeningMovement(stock, StockBucket.AVAILABLE, stock.available(), operationId, now);
                    addOpeningMovement(stock, StockBucket.RESERVED, stock.reserved(), operationId, now);
                    addOpeningMovement(stock, StockBucket.DAMAGED, stock.damaged(), operationId, now);
                    addOpeningMovement(stock, StockBucket.SHIPPED, stock.shipped(), operationId, now);
                    addOpeningMovement(stock, StockBucket.WRITTEN_OFF, stock.writtenOff(), operationId, now);
                }
                store.ensureLowStockState(stock.sku(), stock.warehouseCode(), now);
                store.synchronizeLowStockState(stock.sku(), stock.warehouseCode(), stock.available(),
                    stock.minimumAvailable(), operationId, now);
            }
        });
    }

    private void addOpeningMovement(Stock stock, StockBucket bucket, long quantity,
                                    UUID operationId, OffsetDateTime now) {
        if (quantity == 0) return;
        store.insertMovement(new InventoryMovement(UUID.randomUUID(), stock.sku(), stock.warehouseCode(),
            MovementType.INITIAL_STOCK, bucket, quantity, 0, quantity, null, null, null,
            "Opening balance at ledger introduction", "system", now, operationId));
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
