package com.cardpricer.cloud.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TCGplayer prices for SWU, read from TCGTracking's Open TCG API (openapi.tcgtracking.com) and, when that fails, from
 * TCGCSV (tcgcsv.com); both re-host TCGplayer's own prices by product id and subtype, so either fills the same
 * columns. The run's {@code catalog_imports.source} says which one served. Prices are matched to {@code swu_cards} by
 * the TCGplayer product id swu-db gives each printing, and by finish: TCGplayer lists a foil either as the "Foil" price of its non-foil product (SOR through SEC)
 * or as its own product with only a "Foil" price (LAW on), so a foil printing reads "Foil" and every other printing
 * reads "Normal", never the other finish.
 */
@Service
public class SwuTcgplayerPrices {
    static final int CATEGORY = 79;
    private static final Logger log = LoggerFactory.getLogger(SwuTcgplayerPrices.class);
    /** Which mirror of TCGplayer's prices served a printing's price ({@code swu_cards.tcgplayer_source}). */
    public static final String TCGTRACKING = "tcgtracking", TCGCSV = "tcgcsv";
    /** Leaves a price the source no longer has in place; its observed time says how old it is. */
    private static final String APPLY = """
            UPDATE swu_cards SET tcgplayer_market = ?, tcgplayer_low = COALESCE(?, tcgplayer_low), tcgplayer_mid = ?,
                tcgplayer_high = ?, tcgplayer_direct_low = ?, tcgplayer_observed_at = ?, tcgplayer_source = ?
            WHERE tcgplayer_id = ? AND (treatment LIKE '%foil%') = ?""";
    private static final String DISAGREE = """
            UPDATE swu_cards SET price_disagrees = market IS NOT NULL AND tcgplayer_market IS NOT NULL
                AND abs(market - tcgplayer_market) > 2 AND abs(market - tcgplayer_market) > 0.5 * least(market, tcgplayer_market)""";

    /**
     * One finish's prices for one product: TCGplayer's market price (from recent sales), and its lowest, median and
     * highest listing and lowest TCGplayer Direct listing. TCGCSV publishes nothing else per product.
     */
    public record Price(BigDecimal market, BigDecimal low, BigDecimal mid, BigDecimal high, BigDecimal directLow) {
        public Price(BigDecimal market, BigDecimal low) {
            this(market, low, null, null, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String base;
    private final String trackingBase;
    private final String userAgent;
    private final long pauseMs;
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();

    public SwuTcgplayerPrices(JdbcTemplate jdbc, ObjectMapper mapper,
                              @Value("${app.swu.tcgcsv-base:https://tcgcsv.com}") String base,
                              @Value("${app.tcgtracking.base:https://openapi.tcgtracking.com/v1}") String trackingBase,
                              @Value("${app.catalog.user-agent:CardBoxTrading/0.1}") String userAgent,
                              @Value("${app.swu.pause-ms:250}") long pauseMs) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.base = base.replaceAll("/+$", "");
        this.trackingBase = trackingBase.replaceAll("/+$", "");
        this.userAgent = userAgent;
        this.pauseMs = pauseMs;
    }

    /**
     * Tonight's SWU prices: TCGTracking first, TCGCSV if TCGTracking fails. Each attempt is its own run in
     * {@code catalog_imports}, its source the base URL that was read, so a fallback night shows as a failed TCGTracking
     * run followed by a good TCGCSV one.
     */
    public int importPrices() throws IOException, InterruptedException {
        try {
            return importFromTcgTracking();
        } catch (IOException | RuntimeException e) {
            log.warn("TCGTracking SWU prices failed, falling back to TCGCSV: {}", e.toString());
            return importFromTcgcsv();
        }
    }

    /**
     * Every SWU set's /pricing from TCGTracking (about 35 small static files, {@code pauseMs} apart). A set that fails
     * fails the attempt, so the fallback reads a complete night from TCGCSV instead of mixing the two.
     */
    public int importFromTcgTracking() throws IOException, InterruptedException {
        return logged(trackingBase, () -> {
            Map<String, Map<String, Price>> prices = new HashMap<>();
            Instant observed = null;
            JsonNode sets = mapper.readTree(get(trackingBase, "/" + CATEGORY + "/sets")).path("sets");
            if (sets.isEmpty()) throw new IOException("TCGTracking listed no SWU sets");
            for (JsonNode set : sets) {
                if (set.path("product_count").asInt(1) == 0) continue;
                Thread.sleep(pauseMs);
                JsonNode payload = mapper.readTree(get(trackingBase, "/" + CATEGORY + "/sets/" + set.path("id").asText() + "/pricing"));
                prices.putAll(TcgTrackingCatalog.parsePricing(payload));
                Instant updated = TcgTrackingCatalog.updated(payload).orElse(null);
                if (updated != null && (observed == null || updated.isAfter(observed))) observed = updated;
            }
            return apply(prices, observed == null ? Instant.now() : observed, TCGTRACKING);
        });
    }

    /**
     * Every SWU group's prices from TCGCSV, logged in {@code catalog_imports}. A group that fails is skipped (its cards
     * keep their last price) and fails the run.
     */
    public int importFromTcgcsv() throws IOException, InterruptedException {
        return logged(base, this::download);
    }

    private interface Download {
        int run() throws IOException, InterruptedException;
    }

    private int logged(String source, Download download) throws IOException, InterruptedException {
        Long run = jdbc.queryForObject("INSERT INTO catalog_imports (source, game) VALUES (?, ?) RETURNING id",
                Long.class, source, SwuCatalogImporter.GAME);
        try {
            int updated = download.run();
            jdbc.update("UPDATE catalog_imports SET finished_at = now(), cards = ? WHERE id = ?", updated, run);
            return updated;
        } catch (IOException | RuntimeException e) {
            jdbc.update("UPDATE catalog_imports SET finished_at = now(), error = ? WHERE id = ?", e.toString(), run);
            throw e;
        }
    }

    private int download() throws IOException, InterruptedException {
        Instant observed = lastUpdated();
        Map<String, Map<String, Price>> prices = new HashMap<>();
        List<String> failed = new ArrayList<>();
        for (JsonNode group : json("/tcgplayer/" + CATEGORY + "/groups").path("results")) {
            String id = group.path("groupId").asText("");
            try {
                prices.putAll(parse(json("/tcgplayer/" + CATEGORY + "/" + id + "/prices")));
            } catch (IOException e) {
                log.warn("TCGCSV group {} failed: {}", id, e.toString());
                failed.add(group.path("abbreviation").asText(id));
            }
            Thread.sleep(pauseMs);
        }
        int updated = apply(prices, observed);
        if (!failed.isEmpty()) throw new IOException("TCGCSV groups not downloaded: " + String.join(", ", failed));
        return updated;
    }

    /** productId -> subTypeName -> price, from a TCGCSV {@code /prices} payload. */
    public static Map<String, Map<String, Price>> parse(JsonNode payload) {
        Map<String, Map<String, Price>> out = new HashMap<>();
        for (JsonNode row : payload.path("results")) {
            out.computeIfAbsent(row.path("productId").asText(), k -> new HashMap<>())
                    .put(row.path("subTypeName").asText(), new Price(price(row.path("marketPrice")), price(row.path("lowPrice")),
                            price(row.path("midPrice")), price(row.path("highPrice")), price(row.path("directLowPrice"))));
        }
        return out;
    }

    /** TCGCSV's prices; see {@link #apply(Map, Instant, String)}. */
    public int apply(Map<String, Map<String, Price>> prices, Instant observed) {
        return apply(prices, observed, TCGCSV);
    }

    /**
     * Writes the matching finish's price onto every printing of each product, noting which mirror it came from (price
     * history and the evidence panel name it), then re-marks disagreements.
     */
    public int apply(Map<String, Map<String, Price>> prices, Instant observed, String source) {
        List<Object[]> batch = new ArrayList<>();
        Timestamp at = Timestamp.from(observed);
        prices.forEach((product, finishes) -> {
            Price normal = finishes.get("Normal"), foil = finishes.get("Foil");
            if (normal != null && normal.market() != null) batch.add(row(normal, at, source, product, false));
            if (foil != null && foil.market() != null) batch.add(row(foil, at, source, product, true));
        });
        int updated = 0;
        for (int n : jdbc.batchUpdate(APPLY, batch)) updated += Math.max(n, 0);
        jdbc.update(DISAGREE);
        int disagreeing = jdbc.queryForObject("SELECT count(*) FROM swu_cards WHERE price_disagrees", Integer.class);
        if (disagreeing > 0) log.warn("{} SWU printings have {} and swu-db prices that disagree", disagreeing, source);
        log.info("Applied TCGplayer prices from {} to {} SWU printings", source, updated);
        return updated;
    }

    private static Object[] row(Price p, Timestamp at, String source, String product, boolean foil) {
        return new Object[]{p.market(), p.low(), p.mid(), p.high(), p.directLow(), at, source, product, foil};
    }

    /** When TCGCSV last refreshed from TCGplayer; now if it does not say. */
    private Instant lastUpdated() {
        try {
            String text = get("/last-updated.txt").trim();
            return OffsetDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxx")).toInstant();
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static BigDecimal price(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) return null;
        try {
            BigDecimal price = new BigDecimal(value.asText());
            return price.signum() > 0 ? price : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private JsonNode json(String path) throws IOException, InterruptedException {
        return mapper.readTree(get(path));
    }

    private String get(String path) throws IOException, InterruptedException {
        return get(base, path);
    }

    private String get(String base, String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", userAgent).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("GET " + path + " returned HTTP " + response.statusCode());
        return response.body();
    }
}
