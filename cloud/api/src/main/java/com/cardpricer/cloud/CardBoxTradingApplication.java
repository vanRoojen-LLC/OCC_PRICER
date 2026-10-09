package com.cardpricer.cloud;

import com.cardpricer.cloud.catalog.CatalogImporter;
import com.cardpricer.cloud.catalog.PriceHistory;
import com.cardpricer.cloud.catalog.SwuCatalogImporter;
import com.cardpricer.cloud.catalog.SwuTcgplayerPrices;
import com.cardpricer.cloud.catalog.TcgSkuPrices;
import com.cardpricer.cloud.catalog.TcgTrackingCatalog;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class CardBoxTradingApplication {
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("import-catalog")) {
            // Run as the nightly Container Apps job: import, then exit with a status code.
            var app = new SpringApplication(CardBoxTradingApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            var context = app.run(args);
            int status = 0;
            String file = context.getEnvironment().getProperty("app.catalog.file", "");
            try {
                var importer = context.getBean(CatalogImporter.class);
                if (file.isBlank()) importer.importFromScryfall();
                else importer.importFile(java.nio.file.Path.of(file));
            } catch (Exception e) {
                e.printStackTrace();
                status = 1;
            }
            // Star Wars: Unlimited runs even when Magic failed; either failure fails the job so it is noticed.
            if (file.isBlank()) {
                try {
                    context.getBean(SwuCatalogImporter.class).importFromSwuDb();
                } catch (Exception e) {
                    e.printStackTrace();
                    status = 1;
                }
                // TCGplayer's prices from TCGTracking (TCGCSV if that fails), matched through the product ids swu-db
                // just loaded.
                try {
                    context.getBean(SwuTcgplayerPrices.class).importPrices();
                } catch (Exception e) {
                    e.printStackTrace();
                    status = 1;
                }
                // Every other game, from TCGTracking: time-boxed and incremental, so it picks up where last night stopped.
                try {
                    context.getBean(TcgTrackingCatalog.class).sync();
                } catch (Exception e) {
                    e.printStackTrace();
                    status = 1;
                }
                // Condition prices, listing counts and Mana Pool's price for the cards stores hold or have traded.
                try {
                    context.getBean(TcgSkuPrices.class).sync();
                } catch (Exception e) {
                    e.printStackTrace();
                    status = 1;
                }
                // Whatever refreshed tonight goes into the price history, even if another import failed.
                try {
                    context.getBean(PriceHistory.class).record();
                } catch (Exception e) {
                    e.printStackTrace();
                    status = 1;
                }
            }
            System.exit(SpringApplication.exit(context, () -> 0) + status);
        }
        SpringApplication.run(CardBoxTradingApplication.class, args);
    }
}
