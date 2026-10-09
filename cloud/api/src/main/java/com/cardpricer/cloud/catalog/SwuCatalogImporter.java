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
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads Star Wars: Unlimited from swu-db (api.swu-db.com) into {@code swu_cards} for the free price check: every set
 * it lists, promo sets included, one request per set. Treatments and printed numbers follow CardBox Club's
 * {@code swu_catalog.py}, so the two apps name a printing the same way.
 */
@Service
public class SwuCatalogImporter {
    public static final String GAME = "star-wars-unlimited";
    private static final Logger log = LoggerFactory.getLogger(SwuCatalogImporter.class);
    private static final String UPSERT = """
            INSERT INTO swu_cards (set_code, source_number, set_name, collector_number, treatment, variant, name, subtitle,
                                   card_type, rarity, image, tcgplayer_id, swu_cid, market, low, price_observed_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (set_code, source_number) DO UPDATE SET set_name = EXCLUDED.set_name,
                collector_number = EXCLUDED.collector_number, treatment = EXCLUDED.treatment, variant = EXCLUDED.variant,
                name = EXCLUDED.name, subtitle = EXCLUDED.subtitle, card_type = EXCLUDED.card_type, rarity = EXCLUDED.rarity,
                image = EXCLUDED.image, tcgplayer_id = EXCLUDED.tcgplayer_id, swu_cid = EXCLUDED.swu_cid,
                market = COALESCE(EXCLUDED.market, swu_cards.market), low = COALESCE(EXCLUDED.low, swu_cards.low),
                price_observed_at = CASE WHEN EXCLUDED.market IS NULL THEN swu_cards.price_observed_at
                                         ELSE EXCLUDED.price_observed_at END,
                updated_at = now()""";

    /** swu-db's VariantType -> Club's canonical treatment. Anything else is slugged (see {@link #treatment}). */
    static final Map<String, String> TREATMENTS = Map.of(
            "Normal", "normal", "Foil", "foil", "Hyperspace", "hyperspace", "Hyperspace Foil", "hyperspace_foil",
            "Showcase", "showcase", "Prestige", "prestige", "Prestige Foil", "prestige_foil",
            "Prestige Serialized", "prestige_serialized", "OP Promo", "op_promo");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String apiBase;
    private final String userAgent;
    private final long pauseMs;
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();

    public SwuCatalogImporter(JdbcTemplate jdbc, ObjectMapper mapper,
                              @Value("${app.swu.api-base:https://api.swu-db.com}") String apiBase,
                              @Value("${app.catalog.user-agent:CardBoxTrading/0.1}") String userAgent,
                              @Value("${app.swu.pause-ms:250}") long pauseMs) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
        this.userAgent = userAgent;
        this.pauseMs = pauseMs;
    }

    /**
     * Every set swu-db lists. A set that fails to download is skipped and named in the run's error, so its cards keep
     * their last prices and the page's "updated" date stays at the last complete run.
     */
    public int importFromSwuDb() throws IOException, InterruptedException {
        JsonNode sets = get("/sets");
        Map<String, JsonNode> cards = new LinkedHashMap<>();
        List<String> failed = new ArrayList<>();
        for (JsonNode set : sets) {
            String code = set.path("setId").asText("");
            if (code.isBlank()) continue;
            try {
                cards.put(code, get("/cards/" + URLEncoder.encode(code, StandardCharsets.UTF_8)));
            } catch (IOException e) {
                log.warn("swu-db set {} failed: {}", code, e.toString());
                failed.add(code);
            }
            Thread.sleep(pauseMs);
        }
        if (cards.isEmpty()) throw new IOException("swu-db returned no sets");
        return importSets(sets, cards, apiBase, failed);
    }

    /** {@code sets} is swu-db's /sets payload; {@code cards} maps a set code to its /cards/{set} payload. */
    public int importSets(JsonNode sets, Map<String, JsonNode> cards, String source, List<String> failedSets) {
        Long run = jdbc.queryForObject("INSERT INTO catalog_imports (source, game) VALUES (?, ?) RETURNING id",
                Long.class, source, GAME);
        Map<String, String> names = new LinkedHashMap<>();
        for (JsonNode set : sets) names.put(set.path("setId").asText(""), set.path("fullName").asText(""));
        Timestamp now = Timestamp.from(Instant.now());
        int count = 0;
        List<String> problems = new ArrayList<>(failedSets.stream().map(s -> "set " + s + " not downloaded").toList());
        try {
            for (Map.Entry<String, JsonNode> entry : cards.entrySet()) {
                List<Object[]> batch = new ArrayList<>();
                for (JsonNode record : entry.getValue().path("data")) {
                    try {
                        batch.add(toRow(record, names.getOrDefault(entry.getKey(), entry.getKey()), now));
                    } catch (IllegalArgumentException e) {
                        problems.add(entry.getKey() + ": " + e.getMessage());
                    }
                }
                if (!batch.isEmpty()) jdbc.batchUpdate(UPSERT, batch);
                count += batch.size();
            }
        } catch (RuntimeException e) {
            jdbc.update("UPDATE catalog_imports SET finished_at = now(), cards = ?, error = ? WHERE id = ?", count, e.toString(), run);
            throw e;
        }
        String error = failedSets.isEmpty() ? null : String.join("; ", problems);
        if (!problems.isEmpty()) log.warn("swu-db import: {}", String.join("; ", problems.subList(0, Math.min(20, problems.size()))));
        jdbc.update("UPDATE catalog_imports SET finished_at = now(), cards = ?, error = ? WHERE id = ?", count, error, run);
        log.info("Imported {} SWU printings from {}", count, source);
        return count;
    }

    static Object[] toRow(JsonNode record, String setName, Timestamp observedAt) {
        String set = record.path("Set").asText("").trim().toUpperCase(Locale.ROOT);
        String number = record.path("Number").asText("").trim();
        String name = record.path("Name").asText("").trim();
        String variant = record.path("VariantType").asText("").trim();
        if (set.isEmpty() || number.isEmpty() || name.isEmpty()) throw new IllegalArgumentException("record without set, number or name");
        if (variant.isEmpty()) throw new IllegalArgumentException(set + " " + number + " has no variant");
        String tcgplayer = blankToNull(record.path("tcgplayerId").asText(""));
        BigDecimal market = price(record.path("MarketPrice"));
        BigDecimal low = price(record.path("LowPrice"));
        if (tokenBackedBase(record)) {
            // TCGplayer sells a common Base with a Token, one product per pairing; swu-db's id and price name just one
            // pairing, so they are not this printing's price (Club's rule too).
            tcgplayer = null;
            market = null;
            low = null;
        }
        String image = tcgplayer != null ? "https://tcgplayer-cdn.tcgplayer.com/product/" + tcgplayer + "_200w.jpg"
                : blankToNull(record.path("FrontArt").asText(""));
        return new Object[]{set, number, setName, printedNumber(number), treatment(variant), variant, name,
                blankToNull(record.path("Subtitle").asText("")), blankToNull(record.path("Type").asText("")),
                record.path("Rarity").asText(""), image, tcgplayer, blankToNull(record.path("cid").asText("")),
                market, low, market == null ? null : observedAt};
    }

    /** Club's canonical treatment: the known finishes by name, anything else (OP and event promos) slugged. */
    static String treatment(String variant) {
        String known = TREATMENTS.get(variant);
        if (known != null) return known;
        String slug = variant.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (slug.isEmpty()) throw new IllegalArgumentException("unknown variant " + variant);
        return slug;
    }

    /** True for foil finishes; serialized Prestige cards are priced on their own and are not "foil" here. */
    static boolean foil(String treatment) {
        return treatment.contains("foil");
    }

    /** swu-db's F suffix tells foils apart and is not printed on the card; numbers print zero-padded to three. */
    static String printedNumber(String number) {
        String bare = number.toUpperCase(Locale.ROOT).endsWith("F") ? number.substring(0, number.length() - 1) : number;
        return bare.matches("\\d+") && bare.length() < 3 ? "0".repeat(3 - bare.length()) + bare : bare;
    }

    static boolean tokenBackedBase(JsonNode record) {
        return "base".equalsIgnoreCase(record.path("Type").asText("").trim())
                && record.path("Rarity").asText("").trim().toLowerCase(Locale.ROOT).matches("c|common");
    }

    /** swu-db reports a missing price as "" or "0.00"; neither is a price. */
    static BigDecimal price(JsonNode value) {
        String text = value.asText("").trim();
        if (text.isEmpty()) return null;
        try {
            BigDecimal price = new BigDecimal(text);
            return price.signum() > 0 ? price : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiBase + path)).timeout(Duration.ofMinutes(2))
                .header("User-Agent", userAgent).header("Accept", "application/json").GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("GET " + path + " returned HTTP " + response.statusCode());
        return mapper.readTree(response.body());
    }
}
