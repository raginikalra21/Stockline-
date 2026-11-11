package in.stockline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class StocklineApplication {
    public static void main(String[] args) {
        SpringApplication.run(StocklineApplication.class, args);
    }
}
