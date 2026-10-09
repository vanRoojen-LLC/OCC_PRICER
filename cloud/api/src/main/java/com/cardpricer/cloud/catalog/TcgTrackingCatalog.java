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
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every game TCGplayer lists, from TCGTracking's Open TCG API (openapi.tcgtracking.com), into {@code tcg_games},
 * {@code tcg_sets} and {@code tcg_products}. TCGTracking is a free, keyless service run by one person, serving
 * TCGplayer's catalog and prices as static JSON from Cloudflare's edge; the owner's rule is not to crush it, so the
 * sync is deliberately slow and incremental:
 * <ul>
 * <li>One request at a time, {@code app.tcgtracking.pause-ms} (1 s) apart, each conditional on the ETag we stored, so
 *     an unchanged file costs a 304 and no body.</li>
 * <li>/categories and each enabled game's /sets once per run. A set's /cards is read again only when the listing's
 *     products_modified moved past the value we last read it at, its /pricing when pricing_modified did, or when our
 *     copy of its prices is older than {@code app.tcgtracking.pricing-max-age-hours} (20 hours for a set holding a card
 *     a store stocks or traded): the /sets listing is itself edge-cached for days, so its pricing_modified can lag the
 *     daily price refresh (~9:35 AM ET).</li>
 * <li>Time-boxed by {@code app.tcgtracking.budget-minutes} (20), most-stale sets first, stopping cleanly when the time
 *     is spent: the first sync of ~3,400 sets spreads over several nights and later nights only fetch what changed.</li>
 * <li>A set that fails keeps its last data, records last_error and is retried next run; the run fails only when every
 *     request failed (TCGTracking down, or blocking us).</li>
 * </ul>
 * Magic (1) and Star Wars: Unlimited (79) have their own catalogs and are not synced here; SWU's prices come from
 * TCGTracking through {@link SwuTcgplayerPrices}.
 */
@Service
public class TcgTrackingCatalog {
    public static final String GAME = "tcgtracking";
    private static final Logger log = LoggerFactory.getLogger(TcgTrackingCatalog.class);
    /** Served natively (cards, swu_cards). */
    static final Set<Integer> NATIVE = Set.of(1, 79);
    /** Accessories and bulk, not games; and categories TCGplayer lists with no products. */
    static final Set<Integer> NOT_GAMES = Set.of(31, 32, 35, 49, 50, 55, 56, 84);
    /**
     * CardBox Club's segment key for the games it already knows, so a card is filed under the same game in both apps.
     * Pokemon Japan is the same game as Pokemon, in Japanese. Every other category takes {@link #slug} of its name.
     */
    static final Map<Integer, String> SEGMENTS = Map.of(1, "magic-the-gathering", 2, "yugioh", 3, "pokemon", 85, "pokemon",
            24, "final-fantasy-tcg", 62, "flesh-and-blood", 68, "one-piece", 71, "lorcana", 79, "star-wars-unlimited",
            89, "riftbound");
    static final Map<Integer, String> LANGUAGES = Map.of(85, "ja");

    private static final String UPSERT_GAME = """
            INSERT INTO tcg_games (category_id, segment, name, language, product_count) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (category_id) DO UPDATE SET segment = EXCLUDED.segment, name = EXCLUDED.name,
                language = EXCLUDED.language, product_count = EXCLUDED.product_count, updated_at = now()""";
    private static final String UPSERT_SET = """
            INSERT INTO tcg_sets (set_id, category_id, name, abbreviation, released, product_count, products_modified,
                                  pricing_modified)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (set_id) DO UPDATE SET category_id = EXCLUDED.category_id, name = EXCLUDED.name,
                abbreviation = EXCLUDED.abbreviation, released = EXCLUDED.released, product_count = EXCLUDED.product_count,
                products_modified = EXCLUDED.products_modified, pricing_modified = EXCLUDED.pricing_modified""";
    /**
     * Sets with work to do. Sets holding a card some store stocks or has traded come first and have their prices read
     * daily (price evidence calls a price over three days old untrustworthy); then the rest, most stale first and,
     * among sets never read, the newest first.
     */
    private static final String DUE = """
            SELECT s.set_id, s.category_id, s.name, s.abbreviation, s.products_modified, s.pricing_modified, s.cards_etag,
                   s.pricing_etag,
                   s.cards_version IS NULL OR s.cards_fetched_at IS NULL OR s.products_modified > s.cards_version AS cards_due
            FROM tcg_sets s JOIN tcg_games g ON g.category_id = s.category_id
            CROSS JOIN LATERAL (SELECT coalesce(s.pricing_fetched_at < now() - interval '20 hours', false) AND EXISTS (
                    SELECT 1 FROM tcg_products p WHERE p.set_id = s.set_id
                    AND p.id IN (SELECT card_id FROM inventory_items UNION SELECT card_id FROM trade_lines)) AS held_due) h
            WHERE g.enabled AND coalesce(s.product_count, 1) > 0
              AND (s.cards_version IS NULL OR s.cards_fetched_at IS NULL OR s.products_modified > s.cards_version
                   OR s.pricing_fetched_at IS NULL OR s.pricing_modified > coalesce(s.pricing_version, '-infinity')
                   OR s.pricing_fetched_at < now() - make_interval(hours => ?) OR h.held_due)
            ORDER BY h.held_due DESC,
                     CASE WHEN s.cards_fetched_at IS NULL OR s.pricing_fetched_at IS NULL THEN NULL
                          ELSE least(s.cards_fetched_at, s.pricing_fetched_at) END ASC NULLS FIRST,
                     s.released DESC NULLS LAST, s.set_id""";
    /** Identity onto every subtype row a product already has. */
    private static final String UPDATE_PRODUCT = """
            UPDATE tcg_products SET set_id = ?, set_code = ?, set_name = ?, name = ?, collector_number = ?, rarity = ?,
                image_url = ?, cardmarket_id = ?, cardtrader_id = ?, updated_at = now()
            WHERE category_id = ? AND product_id = ?""";
    /** A product seen for the first time: one unpriced Normal row until its prices arrive. */
    private static final String INSERT_PRODUCT = """
            INSERT INTO tcg_products (category_id, product_id, sub_type, set_id, set_code, set_name, name, collector_number,
                                      rarity, image_url, cardmarket_id, cardtrader_id)
            SELECT ?, ?, 'Normal', ?, ?, ?, ?, ?, ?, ?, ?, ?
            WHERE NOT EXISTS (SELECT 1 FROM tcg_products WHERE category_id = ? AND product_id = ?)""";
    /**
     * One subtype's price, copying the product's identity from a row it already has. A price TCGTracking no longer
     * lists for a subtype is left in place; observed_at says how old it is.
     */
    private static final String UPSERT_PRICE = """
            INSERT INTO tcg_products (category_id, product_id, sub_type, set_id, set_code, set_name, name, collector_number,
                                      rarity, image_url, cardmarket_id, cardtrader_id, market, low, observed_at)
            SELECT category_id, product_id, ?, set_id, set_code, set_name, name, collector_number, rarity, image_url,
                   cardmarket_id, cardtrader_id, ?, ?, ?
            FROM tcg_products WHERE category_id = ? AND product_id = ? ORDER BY sub_type LIMIT 1
            ON CONFLICT (category_id, product_id, sub_type) DO UPDATE SET
                market = coalesce(EXCLUDED.market, tcg_products.market), low = coalesce(EXCLUDED.low, tcg_products.low),
                observed_at = CASE WHEN EXCLUDED.market IS NULL THEN tcg_products.observed_at ELSE EXCLUDED.observed_at END,
                updated_at = now()""";
    /**
     * Drops the unpriced Normal placeholder of a product that turned out to be sold only in other subtypes (a foil-only
     * printing), unless a store already holds or traded it.
     */
    private static final String DROP_PLACEHOLDERS = """
            DELETE FROM tcg_products p WHERE p.set_id = ? AND p.sub_type = 'Normal' AND p.market IS NULL AND p.low IS NULL
              AND EXISTS (SELECT 1 FROM tcg_products o WHERE o.category_id = p.category_id AND o.product_id = p.product_id
                          AND o.sub_type <> 'Normal')
              AND NOT EXISTS (SELECT 1 FROM inventory_items i WHERE i.card_id = p.id)
              AND NOT EXISTS (SELECT 1 FROM trade_lines l WHERE l.card_id = p.id)""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String base;
    private final String userAgent;
    private final long pauseMs;
    private final Duration budget;
    private final int pricingMaxAgeHours;
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();
    /** Per run: when the last request went out, and how many were sent and failed. */
    private long lastRequestNanos;
    private int requests;
    private int failures;

    public TcgTrackingCatalog(JdbcTemplate jdbc, ObjectMapper mapper,
                              @Value("${app.tcgtracking.base:https://openapi.tcgtracking.com/v1}") String base,
                              @Value("${app.catalog.user-agent:CardBoxTrading/0.1}") String userAgent,
                              @Value("${app.tcgtracking.pause-ms:1000}") long pauseMs,
                              @Value("${app.tcgtracking.budget-minutes:20}") long budgetMinutes,
                              @Value("${app.tcgtracking.pricing-max-age-hours:72}") int pricingMaxAgeHours) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.base = base.replaceAll("/+$", "");
        this.userAgent = userAgent;
        this.pauseMs = pauseMs;
        this.budget = Duration.ofMinutes(budgetMinutes);
        this.pricingMaxAgeHours = pricingMaxAgeHours;
    }

    /** What one run did. {@code left} counts the sets still due when the time ran out. */
    public record Result(int products, int setsRead, int setsFailed, int left, int requests) {}

    public Result sync() throws IOException, InterruptedException {
        return sync(budget);
    }

    /**
     * One run, logged in {@code catalog_imports}. The listings are always refreshed; sets are then read until
     * {@code budget} is spent. The log's error names the sets that failed (they are retried next run).
     */
    public synchronized Result sync(Duration budget) throws IOException, InterruptedException {
        Instant deadline = Instant.now().plus(budget);
        lastRequestNanos = 0;
        requests = 0;
        failures = 0;
        Long run = jdbc.queryForObject("INSERT INTO catalog_imports (source, game) VALUES (?, ?) RETURNING id",
                Long.class, base, GAME);
        try {
            refreshGames();
            for (Map<String, Object> game : jdbc.queryForList(
                    "SELECT category_id, sets_etag FROM tcg_games WHERE enabled ORDER BY sort NULLS LAST, product_count DESC NULLS LAST")) {
                refreshSets((Integer) game.get("category_id"), (String) game.get("sets_etag"));
            }
            List<Map<String, Object>> due = jdbc.queryForList(DUE, pricingMaxAgeHours);
            int products = 0, read = 0;
            List<String> failed = new ArrayList<>();
            for (Map<String, Object> set : due) {
                if (!Instant.now().isBefore(deadline)) break;
                try {
                    products += syncSet(set);
                    read++;
                } catch (IOException | RuntimeException e) {
                    log.warn("TCGTracking set {} ({}) failed: {}", set.get("set_id"), set.get("name"), e.toString());
                    jdbc.update("UPDATE tcg_sets SET last_error = ?, last_error_at = now() WHERE set_id = ?",
                            e.toString(), set.get("set_id"));
                    failed.add(set.get("category_id") + "/" + set.get("set_id"));
                }
            }
            int left = due.size() - read - failed.size();
            if (left > 0) log.info("TCGTracking time budget spent; {} sets left for the next run", left);
            if (requests > 0 && failures == requests) throw new IOException("Every TCGTracking request failed");
            String error = failed.isEmpty() ? null
                    : failed.size() + " sets failed: " + String.join(", ", failed.subList(0, Math.min(20, failed.size())));
            jdbc.update("UPDATE catalog_imports SET finished_at = now(), cards = ?, error = ? WHERE id = ?", products, error, run);
            log.info("TCGTracking: {} sets read, {} products updated, {} failed, {} left, {} requests",
                    read, products, failed.size(), left, requests);
            return new Result(products, read, failed.size(), left, requests);
        } catch (IOException | RuntimeException e) {
            jdbc.update("UPDATE catalog_imports SET finished_at = now(), error = ? WHERE id = ?", e.toString(), run);
            throw e;
        }
    }

    /** /categories into tcg_games; a failure leaves the games we already know. */
    private void refreshGames() throws InterruptedException {
        JsonNode body;
        try {
            Fetched fetched = get("/categories", null);
            body = mapper.readTree(fetched.body());
        } catch (IOException e) {
            log.warn("TCGTracking categories failed: {}", e.toString());
            return;
        }
        for (JsonNode category : body.path("categories")) {
            int id = category.path("id").asInt(0);
            String name = category.path("name").asText("").trim();
            if (id <= 0 || name.isEmpty() || NATIVE.contains(id) || NOT_GAMES.contains(id)
                    || category.path("product_count").asInt(0) <= 0) continue;
            String display = category.path("display_name").asText("").trim();
            jdbc.update(UPSERT_GAME, id, segment(id, name), display.isEmpty() ? name : display,
                    LANGUAGES.getOrDefault(id, "en"), category.path("product_count").asInt());
        }
    }

    /** A game's /sets listing into tcg_sets; unchanged (304) means what we stored is current. */
    private void refreshSets(int category, String etag) throws InterruptedException {
        try {
            Fetched fetched = get("/" + category + "/sets", etag);
            if (fetched.status() == 304) {
                jdbc.update("UPDATE tcg_games SET sets_fetched_at = now() WHERE category_id = ?", category);
                return;
            }
            List<Object[]> batch = new ArrayList<>();
            for (JsonNode set : mapper.readTree(fetched.body()).path("sets")) {
                int id = set.path("id").asInt(0);
                if (id <= 0) continue;
                batch.add(new Object[]{id, category, set.path("name").asText(""), blankToNull(set.path("abbreviation").asText("")),
                        date(set.path("published_on")), set.path("product_count").isNumber() ? set.path("product_count").asInt() : null,
                        timestamp(set.path("products_modified")), timestamp(set.path("pricing_modified"))});
            }
            if (!batch.isEmpty()) jdbc.batchUpdate(UPSERT_SET, batch);
            jdbc.update("UPDATE tcg_games SET sets_etag = ?, sets_fetched_at = now() WHERE category_id = ?",
                    fetched.etag(), category);
        } catch (IOException e) {
            log.warn("TCGTracking sets of category {} failed: {}", category, e.toString());
        }
    }

    /** Reads what is due of one set; returns the product rows written. */
    private int syncSet(Map<String, Object> set) throws IOException, InterruptedException {
        int id = (Integer) set.get("set_id");
        int category = (Integer) set.get("category_id");
        String path = "/" + category + "/sets/" + id;
        int written = 0;
        boolean cardsRead = false;
        if (Boolean.TRUE.equals(set.get("cards_due"))) {
            Fetched cards = get(path + "/cards", (String) set.get("cards_etag"));
            if (cards.status() != 304) {
                written += upsertCards(category, id, (String) set.get("name"), (String) set.get("abbreviation"),
                        mapper.readTree(cards.body()));
                cardsRead = true;
            }
            jdbc.update("UPDATE tcg_sets SET cards_version = coalesce(products_modified, now()), cards_fetched_at = now(), cards_etag = ?"
                    + " WHERE set_id = ?", cards.etag() != null ? cards.etag() : set.get("cards_etag"), id);
        }
        // New products need their prices even if the pricing file has not changed, so read it whole after new cards.
        Fetched pricing = get(path + "/pricing", cardsRead ? null : (String) set.get("pricing_etag"));
        if (pricing.status() != 304) written += applyPricing(category, id, mapper.readTree(pricing.body()));
        jdbc.update("UPDATE tcg_sets SET pricing_version = pricing_modified, pricing_fetched_at = now(), pricing_etag = ?,"
                + " last_error = NULL, last_error_at = NULL WHERE set_id = ?",
                pricing.etag() != null ? pricing.etag() : set.get("pricing_etag"), id);
        return written;
    }

    /** A set's /cards payload: every product's identity, and an unpriced Normal row for products new to us. */
    int upsertCards(int category, int setId, String setName, String abbreviation, JsonNode payload) {
        String name = payload.path("set_name").asText(setName);
        String code = blankToNull(payload.path("set_abbr").asText(""));
        if (code == null) code = abbreviation;
        code = code == null ? String.valueOf(setId) : code.toUpperCase(Locale.ROOT);
        List<Object[]> updates = new ArrayList<>(), inserts = new ArrayList<>();
        for (JsonNode product : payload.path("products")) {
            int id = product.path("id").asInt(0);
            String productName = product.path("name").asText("").trim();
            if (id <= 0 || productName.isEmpty()) continue;
            String number = blankToNull(product.path("number").asText(""));
            String rarity = blankToNull(product.path("rarity").asText(""));
            if ("none".equalsIgnoreCase(rarity)) rarity = null;
            String image = image(product.path("image_url").asText(""));
            Integer cardmarket = product.path("cardmarket_id").isNumber() ? product.path("cardmarket_id").asInt() : null;
            Integer cardtrader = product.path("cardtrader_id").isNumber() ? product.path("cardtrader_id").asInt() : null;
            updates.add(new Object[]{setId, code, name, productName, number, rarity, image, cardmarket, cardtrader, category, id});
            inserts.add(new Object[]{category, id, setId, code, name, productName, number, rarity, image, cardmarket, cardtrader,
                    category, id});
        }
        if (!updates.isEmpty()) {
            jdbc.batchUpdate(UPDATE_PRODUCT, updates);
            jdbc.batchUpdate(INSERT_PRODUCT, inserts);
        }
        return updates.size();
    }

    /** A set's /pricing payload onto its products, one row per subtype. */
    int applyPricing(int category, int setId, JsonNode payload) {
        Timestamp observed = Timestamp.from(updated(payload).orElseGet(Instant::now));
        List<Object[]> batch = new ArrayList<>();
        parsePricing(payload).forEach((product, subtypes) -> {
            int id;
            try {
                id = Integer.parseInt(product);
            } catch (NumberFormatException e) {
                return;
            }
            subtypes.forEach((subtype, price) -> {
                if (price.market() != null || price.low() != null)
                    batch.add(new Object[]{subtype, price.market(), price.low(), observed, category, id});
            });
        });
        int written = 0;
        for (int n : jdbc.batchUpdate(UPSERT_PRICE, batch)) written += Math.max(n, 0);
        jdbc.update(DROP_PLACEHOLDERS, setId);
        return written;
    }

    /**
     * productId -> subtype -> TCGplayer price, from a TCGTracking /pricing payload
     * ({@code {"prices": {"533897": {"tcg": {"Normal": {"low": 45, "market": 60.22}}}}}}). Only TCGplayer's ("tcg") prices
     * are read; Magic's Mana Pool prices beside them are not.
     */
    public static Map<String, Map<String, SwuTcgplayerPrices.Price>> parsePricing(JsonNode payload) {
        Map<String, Map<String, SwuTcgplayerPrices.Price>> out = new HashMap<>();
        payload.path("prices").fields().forEachRemaining(product -> {
            Map<String, SwuTcgplayerPrices.Price> subtypes = new HashMap<>();
            product.getValue().path("tcg").fields().forEachRemaining(subtype -> subtypes.put(subtype.getKey(),
                    new SwuTcgplayerPrices.Price(price(subtype.getValue().path("market")), price(subtype.getValue().path("low")))));
            if (!subtypes.isEmpty()) out.put(product.getKey(), subtypes);
        });
        return out;
    }

    /** The payload's "updated" time, when TCGTracking last refreshed these prices from TCGplayer. */
    public static java.util.Optional<Instant> updated(JsonNode payload) {
        return java.util.Optional.ofNullable(timestamp(payload.path("updated"))).map(Timestamp::toInstant);
    }

    /**
     * The game key a category is filed under: Club's segment for the games it knows, otherwise the name lowercased with
     * every run of other characters made one '-' ("Disney Lorcana" -> disney-lorcana).
     */
    public static String segment(int category, String name) {
        String known = SEGMENTS.get(category);
        return known != null ? known : slug(name);
    }

    static String slug(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /** TCGTracking's product images come 200px wide; the same path serves 1000px, which is what we keep. */
    static String image(String url) {
        String trimmed = blankToNull(url);
        return trimmed == null ? null : trimmed.replaceFirst("_200w\\.jpg$", "_1000w.jpg");
    }

    /** Price subtypes whose name says foil (Foil, Holofoil, Reverse Holofoil, Cold Foil, ...); the rest are normal. */
    public static boolean foil(String subType) {
        return subType.toLowerCase(Locale.ROOT).contains("foil");
    }

    static BigDecimal price(JsonNode value) {
        if (!value.isNumber() && !value.isTextual()) return null;
        try {
            BigDecimal price = new BigDecimal(value.asText().trim());
            return price.signum() > 0 ? price : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Timestamp timestamp(JsonNode value) {
        String text = value.asText("").trim();
        if (text.isEmpty() || value.isNull()) return null;
        try {
            return Timestamp.from(OffsetDateTime.parse(text).toInstant());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static Date date(JsonNode value) {
        String text = value.asText("").trim();
        if (text.length() < 10 || value.isNull()) return null;
        try {
            return Date.valueOf(LocalDate.parse(text.substring(0, 10)));
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() || value.equals("null") ? null : value.trim();
    }

    record Fetched(int status, String body, String etag) {}

    /**
     * One GET, at least {@code pauseMs} after the last one, sent with If-None-Match when we hold an ETag. 200 and 304
     * are answers; anything else is an IOException counted as a failed request.
     */
    private Fetched get(String path, String etag) throws IOException, InterruptedException {
        if (lastRequestNanos != 0) {
            long waitMs = pauseMs - (System.nanoTime() - lastRequestNanos) / 1_000_000;
            if (waitMs > 0) Thread.sleep(waitMs);
        }
        requests++;
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", userAgent).header("Accept", "application/json").GET();
        if (etag != null && !etag.isBlank()) request.header("If-None-Match", etag);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            String newEtag = response.headers().firstValue("ETag").orElse(null);
            if (response.statusCode() == 304) return new Fetched(304, null, newEtag != null ? newEtag : etag);
            if (response.statusCode() != 200) throw new IOException("GET " + path + " returned HTTP " + response.statusCode());
            return new Fetched(200, response.body(), newEtag);
        } catch (IOException e) {
            failures++;
            throw e;
        } finally {
            lastRequestNanos = System.nanoTime();
        }
    }
}
