package com.cardpricer.cloud.catalog;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

/**
 * Loads Scryfall's "default cards" bulk file (one entry per printing) into the shared catalog.
 * Runs nightly as a Container Apps job; Scryfall publishes prices once a day.
 */
@Service
public class CatalogImporter {
    private static final Logger log = LoggerFactory.getLogger(CatalogImporter.class);
    private static final int BATCH = 1000;
    private static final String UPSERT = """
            INSERT INTO cards (id, name, set_code, set_name, collector_number, rarity, lang, released_at,
                               usd, usd_foil, usd_etched, image_small, type_line, oracle_text, flavor_text, artist,
                               colors, color_identity, mana_value, set_type, treatments, eur, eur_foil, tcgplayer_id, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, set_code = EXCLUDED.set_code,
                set_name = EXCLUDED.set_name, collector_number = EXCLUDED.collector_number, rarity = EXCLUDED.rarity,
                lang = EXCLUDED.lang, released_at = EXCLUDED.released_at, usd = EXCLUDED.usd,
                usd_foil = EXCLUDED.usd_foil, usd_etched = EXCLUDED.usd_etched, image_small = EXCLUDED.image_small,
                type_line = EXCLUDED.type_line, oracle_text = EXCLUDED.oracle_text, flavor_text = EXCLUDED.flavor_text,
                artist = EXCLUDED.artist, colors = EXCLUDED.colors, color_identity = EXCLUDED.color_identity,
                mana_value = EXCLUDED.mana_value, set_type = EXCLUDED.set_type, treatments = EXCLUDED.treatments,
                eur = EXCLUDED.eur, eur_foil = EXCLUDED.eur_foil, tcgplayer_id = EXCLUDED.tcgplayer_id, updated_at = now()""";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String bulkIndexUrl;
    private final String userAgent;
    private final HttpClient http = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build();

    public CatalogImporter(JdbcTemplate jdbc, ObjectMapper mapper,
                           @Value("${app.catalog.bulk-index-url:https://api.scryfall.com/bulk-data/default-cards}") String bulkIndexUrl,
                           @Value("${app.catalog.user-agent:CardBoxTrading/0.1}") String userAgent) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.bulkIndexUrl = bulkIndexUrl;
        this.userAgent = userAgent;
    }

    public int importFromScryfall() throws IOException, InterruptedException {
        JsonNode index = mapper.readTree(get(bulkIndexUrl));
        // Scryfall now publishes gzipped JSON Lines; older indexes only had a JSON array download_uri.
        String download = index.path("jsonl_download_uri").asText("");
        if (download.isBlank()) download = index.path("download_uri").asText("");
        if (download.isBlank()) throw new IOException("Scryfall bulk index has no download URI");
        log.info("Downloading Scryfall bulk data from {}", download);
        try (InputStream in = get(download)) {
            return importStream(in, download);
        }
    }

    public int importFile(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return importStream(in, file.toString());
        }
    }

    public int importStream(InputStream in, String source) throws IOException {
        Long run = jdbc.queryForObject("INSERT INTO catalog_imports (source) VALUES (?) RETURNING id", Long.class, source);
        int count = 0;
        try (JsonParser parser = mapper.getFactory().createParser(ungzip(in))) {
            // Accepts either a JSON array of cards or JSON Lines (one card object per line).
            JsonToken token = parser.nextToken();
            boolean array = token == JsonToken.START_ARRAY;
            if (array) token = parser.nextToken();
            List<Object[]> batch = new ArrayList<>(BATCH);
            for (; token == JsonToken.START_OBJECT; token = parser.nextToken()) {
                Object[] row = toRow(mapper.readTree(parser));
                if (row == null) continue;
                batch.add(row);
                if (batch.size() == BATCH) {
                    jdbc.batchUpdate(UPSERT, batch);
                    count += batch.size();
                    batch.clear();
                }
            }
            if (token != null && !(array && token == JsonToken.END_ARRAY)) {
                throw new IOException("Expected a JSON array or JSON Lines of cards, found " + token);
            }
            if (!batch.isEmpty()) {
                jdbc.batchUpdate(UPSERT, batch);
                count += batch.size();
            }
        } catch (IOException | RuntimeException e) {
            jdbc.update("UPDATE catalog_imports SET finished_at = now(), cards = ?, error = ? WHERE id = ?", count, e.toString(), run);
            throw e;
        }
        jdbc.update("UPDATE catalog_imports SET finished_at = now(), cards = ? WHERE id = ?", count, run);
        log.info("Imported {} printings from {}", count, source);
        return count;
    }

    /** Bulk files are served as application/gzip rather than with Content-Encoding, so sniff the magic bytes. */
    private static InputStream ungzip(InputStream in) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(in, 65536);
        buffered.mark(2);
        int b1 = buffered.read(), b2 = buffered.read();
        buffered.reset();
        return b1 == 0x1f && b2 == 0x8b ? new GZIPInputStream(buffered, 65536) : buffered;
    }

    /** Paper printings only; the store trades physical cards. */
    static Object[] toRow(JsonNode card) {
        if (!"card".equals(card.path("object").asText("card"))) return null;
        JsonNode games = card.path("games");
        if (games.isArray() && games.size() > 0) {
            boolean paper = false;
            for (JsonNode game : games) paper |= "paper".equals(game.asText());
            if (!paper) return null;
        }
        String image = card.path("image_uris").path("small").asText(null);
        if (image == null) image = card.path("card_faces").path(0).path("image_uris").path("small").asText(null);
        String released = card.path("released_at").asText(null);
        JsonNode prices = card.path("prices");
        return new Object[]{
                UUID.fromString(card.path("id").asText()),
                card.path("name").asText(),
                card.path("set").asText().toUpperCase(java.util.Locale.ROOT),
                card.path("set_name").asText(""),
                card.path("collector_number").asText(),
                card.path("rarity").asText("common"),
                card.path("lang").asText("en"),
                released == null ? null : Date.valueOf(LocalDate.parse(released)),
                price(prices, "usd"), price(prices, "usd_foil"), price(prices, "usd_etched"),
                image,
                text(card, "type_line"), text(card, "oracle_text"), text(card, "flavor_text"), text(card, "artist"),
                colors(card), strings(card.path("color_identity")),
                card.path("cmc").isNumber() ? card.path("cmc").decimalValue() : null,
                card.path("set_type").asText(null), treatments(card),
                price(prices, "eur"), price(prices, "eur_foil"),
                card.path("tcgplayer_id").isNumber() ? card.path("tcgplayer_id").asText() : null};
    }

    /** The card's colors, or every face's for double-faced cards, in WUBRG order. Empty is colorless. */
    static String[] colors(JsonNode card) {
        if (card.path("colors").isArray()) return strings(card.path("colors"));
        java.util.Set<String> faces = new java.util.HashSet<>();
        for (JsonNode face : card.path("card_faces")) for (JsonNode c : face.path("colors")) faces.add(c.asText());
        return "WUBRG".chars().mapToObj(c -> String.valueOf((char) c)).filter(faces::contains).toArray(String[]::new);
    }

    /**
     * How this printing looks beyond its finish, in the words stores sort by: showcase, extended-art, borderless,
     * full-art, retro-frame, textless, serialized, promo.
     */
    static String[] treatments(JsonNode card) {
        List<String> out = new ArrayList<>();
        List<String> effects = new ArrayList<>();
        card.path("frame_effects").forEach(e -> effects.add(e.asText()));
        List<String> promos = new ArrayList<>();
        card.path("promo_types").forEach(e -> promos.add(e.asText()));
        if (effects.contains("showcase")) out.add("showcase");
        if (effects.contains("extendedart")) out.add("extended-art");
        if ("borderless".equals(card.path("border_color").asText())) out.add("borderless");
        if (card.path("full_art").asBoolean(false)) out.add("full-art");
        String frame = card.path("frame").asText("");
        if ((frame.equals("1993") || frame.equals("1997")) && card.path("released_at").asText("").compareTo("2003") > 0) out.add("retro-frame");
        if (card.path("textless").asBoolean(false)) out.add("textless");
        if (promos.contains("serialized")) out.add("serialized");
        if (card.path("promo").asBoolean(false)) out.add("promo");
        return out.toArray(String[]::new);
    }

    private static String[] strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(v -> out.add(v.asText()));
        return out.toArray(String[]::new);
    }

    /** A card field, or each face's value joined with " // " for double-faced and split cards. */
    private static String text(JsonNode card, String field) {
        String value = card.path(field).asText(null);
        if (value != null) return value;
        List<String> faces = new ArrayList<>();
        for (JsonNode face : card.path("card_faces")) {
            String part = face.path(field).asText(null);
            if (part != null && !faces.contains(part)) faces.add(part);
        }
        return faces.isEmpty() ? null : String.join(" // ", faces);
    }

    private static BigDecimal price(JsonNode prices, String field) {
        JsonNode value = prices.path(field);
        return value.isTextual() && !value.asText().isBlank() ? new BigDecimal(value.asText()) : null;
    }

    private InputStream get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10))
                .header("User-Agent", userAgent).header("Accept", "application/json;q=0.9,*/*;q=0.8")
                .header("Accept-Encoding", "gzip").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("GET " + url + " returned HTTP " + response.statusCode());
        }
        boolean gzip = response.headers().firstValue("Content-Encoding").map("gzip"::equalsIgnoreCase).orElse(false);
        return gzip ? new GZIPInputStream(response.body()) : response.body();
    }
}
