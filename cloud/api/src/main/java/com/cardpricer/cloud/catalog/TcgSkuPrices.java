package com.cardpricer.cloud.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * TCGplayer's price per condition and language, its active listing count and Mana Pool's price, for the cards stores
 * hold or have traded, from TCGTracking's per-set {@code /skus} files ({@code tcg_sku_prices}, V28). One request per set
 * that holds such a card, {@code pauseMs} apart, after the night's other imports; a set that fails keeps last night's
 * rows (their observed time says how old they are). Magic and SWU sets are found by their code in TCGTracking's set
 * listing; the other games' products already carry their set.
 */
@Service
public class TcgSkuPrices {
    private static final Logger log = LoggerFactory.getLogger(TcgSkuPrices.class);
    static final int MAGIC = 1;

    private static final String HELD = """
            WITH held AS (SELECT card_id FROM inventory_items UNION SELECT card_id FROM trade_lines)
            SELECT 1 AS category_id, c.tcgplayer_id::int AS product_id, upper(c.set_code) AS set_code, NULL::int AS set_id
            FROM cards c WHERE c.tcgplayer_id ~ '^[0-9]{1,9}$' AND c.id IN (SELECT card_id FROM held)
            UNION
            SELECT 79, s.tcgplayer_id::int, upper(s.set_code), NULL FROM swu_cards s
            WHERE s.tcgplayer_id ~ '^[0-9]{1,9}$' AND s.id IN (SELECT card_id FROM held)
            UNION
            SELECT p.category_id, p.product_id, NULL, p.set_id FROM tcg_products p WHERE p.id IN (SELECT card_id FROM held)""";
    private static final String UPSERT = """
            INSERT INTO tcg_sku_prices (category_id, product_id, variant, condition, language, market, low, high, listings,
                                        manapool, observed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (category_id, product_id, variant, condition, language) DO UPDATE SET market = EXCLUDED.market,
                low = EXCLUDED.low, high = EXCLUDED.high, listings = EXCLUDED.listings, manapool = EXCLUDED.manapool,
                observed_at = EXCLUDED.observed_at""";

    /** One SKU's prices, as TCGTracking's /skus file gives them. */
    public record Sku(String variant, String condition, String language, java.math.BigDecimal market,
                      java.math.BigDecimal low, java.math.BigDecimal high, Integer listings, java.math.BigDecimal manapool) {}

    public record Result(int sets, int rows, int failed) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String base;
    private final String userAgent;
    private final long pauseMs;
    private final int maxSets;
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();

    public TcgSkuPrices(JdbcTemplate jdbc, ObjectMapper mapper,
                        @Value("${app.tcgtracking.base:https://openapi.tcgtracking.com/v1}") String base,
                        @Value("${app.catalog.user-agent:CardBoxTrading/0.1}") String userAgent,
                        @Value("${app.tcgtracking.pause-ms:1000}") long pauseMs,
                        @Value("${app.tcgtracking.sku-max-sets:600}") int maxSets) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.base = base.replaceAll("/+$", "");
        this.userAgent = userAgent;
        this.pauseMs = pauseMs;
        this.maxSets = maxSets;
    }

    /** Tonight's SKU prices for every held or traded product. */
    public Result sync() throws InterruptedException {
        Map<Integer, Map<Integer, Set<Integer>>> wanted = new TreeMap<>();   // category -> set -> products
        Map<Integer, Map<String, Set<Integer>>> byCode = new TreeMap<>();     // category -> set code -> products
        jdbc.query(HELD, rs -> {
            int category = rs.getInt(1), product = rs.getInt(2);
            int set = rs.getInt(4);
            if (rs.wasNull()) byCode.computeIfAbsent(category, k -> new HashMap<>())
                    .computeIfAbsent(rs.getString(3), k -> new HashSet<>()).add(product);
            else wanted.computeIfAbsent(category, k -> new TreeMap<>()).computeIfAbsent(set, k -> new HashSet<>()).add(product);
        });
        int failed = 0;
        for (var entry : byCode.entrySet()) {
            try {
                Map<String, Integer> sets = setIds(mapper.readTree(get("/" + entry.getKey() + "/sets")));
                entry.getValue().forEach((code, products) -> {
                    Integer set = sets.get(code);
                    if (set != null) wanted.computeIfAbsent(entry.getKey(), k -> new TreeMap<>())
                            .computeIfAbsent(set, k -> new HashSet<>()).addAll(products);
                });
            } catch (IOException e) {
                log.warn("TCGTracking set listing of category {} failed: {}", entry.getKey(), e.toString());
                failed++;
            }
        }
        int sets = 0, rows = 0;
        outer:
        for (var category : wanted.entrySet()) {
            for (var set : category.getValue().entrySet()) {
                if (sets >= maxSets) {
                    log.warn("TCGTracking SKU prices stopped at {} sets; the rest keep last night's rows", maxSets);
                    break outer;
                }
                sets++;
                try {
                    JsonNode payload = mapper.readTree(get("/" + category.getKey() + "/sets/" + set.getKey() + "/skus"));
                    Instant observed = TcgTrackingCatalog.updated(payload).orElse(Instant.now());
                    rows += write(category.getKey(), parse(payload, set.getValue()), observed);
                } catch (IOException e) {
                    log.warn("TCGTracking SKUs of set {}/{} failed: {}", category.getKey(), set.getKey(), e.toString());
                    failed++;
                }
            }
        }
        log.info("Recorded {} TCGplayer SKU prices from {} TCGTracking sets ({} failed)", rows, sets, failed);
        return new Result(sets, rows, failed);
    }

    /** Set code (upper case) -> TCGTracking set id, for codes only one set in the game uses. */
    static Map<String, Integer> setIds(JsonNode listing) {
        Map<String, Integer> out = new HashMap<>();
        Set<String> repeated = new HashSet<>();
        for (JsonNode set : listing.path("sets")) {
            String code = set.path("abbreviation").asText("").trim().toUpperCase(Locale.ROOT);
            if (code.isEmpty() || code.equals("NULL")) continue;
            if (out.put(code, set.path("id").asInt()) != null) repeated.add(code);
        }
        repeated.forEach(out::remove);
        return out;
    }

    /**
     * productId -> its SKUs, from a /skus payload ({@code {"products": {"557921": {"8264947": {"cnd": "NM", "var":
     * "Normal", "lng": "EN", "mkt": 0.35, "low": 0.13, "hi": 0.99, "cnt": 25, "mp": 0.15}}}}}), for the products asked.
     */
    public static Map<Integer, List<Sku>> parse(JsonNode payload, Set<Integer> products) {
        Map<Integer, List<Sku>> out = new HashMap<>();
        payload.path("products").fields().forEachRemaining(product -> {
            int id;
            try {
                id = Integer.parseInt(product.getKey());
            } catch (NumberFormatException e) {
                return;
            }
            if (products != null && !products.contains(id)) return;
            List<Sku> skus = new ArrayList<>();
            product.getValue().fields().forEachRemaining(sku -> {
                JsonNode s = sku.getValue();
                String variant = s.path("var").asText("").trim(), condition = s.path("cnd").asText("").trim();
                if (variant.isEmpty() || condition.isEmpty()) return;
                JsonNode count = s.path("cnt");
                skus.add(new Sku(variant, condition, s.path("lng").asText("EN").trim().toUpperCase(Locale.ROOT),
                        TcgTrackingCatalog.price(s.path("mkt")), TcgTrackingCatalog.price(s.path("low")),
                        TcgTrackingCatalog.price(s.path("hi")), count.isNumber() ? count.asInt() : null,
                        TcgTrackingCatalog.price(s.path("mp"))));
            });
            if (!skus.isEmpty()) out.put(id, skus);
        });
        return out;
    }

    int write(int category, Map<Integer, List<Sku>> products, Instant observed) {
        List<Object[]> batch = new ArrayList<>();
        Timestamp at = Timestamp.from(observed);
        products.forEach((product, skus) -> {
            for (Sku s : skus)
                batch.add(new Object[]{category, product, s.variant(), s.condition(), s.language(), s.market(), s.low(),
                        s.high(), s.listings(), s.manapool(), at});
        });
        int written = 0;
        for (int n : jdbc.batchUpdate(UPSERT, batch)) written += Math.max(n, 0);
        return written;
    }

    private long lastRequestNanos;

    private String get(String path) throws IOException, InterruptedException {
        if (lastRequestNanos != 0) {
            long waitMs = pauseMs - (System.nanoTime() - lastRequestNanos) / 1_000_000;
            if (waitMs > 0) Thread.sleep(waitMs);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("GET " + path + " returned HTTP " + response.statusCode());
            return response.body();
        } finally {
            lastRequestNanos = System.nanoTime();
        }
    }
}
