package com.example.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mechanical (momentum/trend) stock signals + a CSV-backed portfolio with PnL.
 * Prices come from Stooq's free, keyless CSV feed (end-of-day / delayed).
 * NOTE: this produces mechanical signals only — it is NOT financial advice.
 */
@Service
public class StockService {

    public record Suggestion(int rank, String ticker, String name, double price, double score, String rationale) {}
    public record Position(String ticker, double quantity, double avgPrice, Double price,
                           Double value, double cost, Double pnl, Double pnlPct,
                           String signal, String signalReason) {}
    public record Portfolio(List<Position> positions, double totalCost, Double totalValue,
                            Double totalPnl, Double totalPnlPct, boolean priced, String asOf,
                            String dataSource) {}

    private static final List<String> WATCHLIST =
        List.of("AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "AMD");
    private static final Map<String, String> NAMES = Map.ofEntries(
        Map.entry("AAPL", "Apple"), Map.entry("MSFT", "Microsoft"), Map.entry("NVDA", "Nvidia"),
        Map.entry("AMZN", "Amazon"), Map.entry("GOOGL", "Alphabet"), Map.entry("META", "Meta"),
        Map.entry("TSLA", "Tesla"), Map.entry("AMD", "AMD"));

    // sell-signal thresholds (mechanical, not advice)
    private static final double TAKE_PROFIT = 0.25;   // +25%
    private static final double STOP_LOSS   = -0.12;  // -12%

    private final RestTemplate http = new RestTemplate();
    private final Path csv;

    private record Cached(long at, double[] closes) {}
    private final Map<String, Cached> histCache = new ConcurrentHashMap<>();
    private static final long HIST_TTL_MS = 30 * 60 * 1000L;

    public StockService(@Value("${portfolio.csv.path:portfolio.csv}") String csvPath) {
        this.csv = Paths.get(csvPath);
    }

    // ---------- market data ----------
    private String fetch(String url) {
        HttpHeaders h = new HttpHeaders();
        h.set("User-Agent", "Mozilla/5.0 (spring-stock-advisor)");
        ResponseEntity<String> r = http.exchange(url, HttpMethod.GET, new HttpEntity<>(h), String.class);
        return r.getBody();
    }

    private static String stooqSymbol(String ticker) { return ticker.toLowerCase() + ".us"; }

    /** Daily close history (~7 months), oldest -> newest. Empty array if unavailable. */
    private double[] history(String ticker) {
        Cached c = histCache.get(ticker);
        if (c != null && System.currentTimeMillis() - c.at() < HIST_TTL_MS) return c.closes();
        double[] closes = new double[0];
        try {
            DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMdd");
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(220);
            String url = "https://stooq.com/q/d/l/?s=" + stooqSymbol(ticker)
                + "&i=d&d1=" + start.format(f) + "&d2=" + end.format(f);
            String body = fetch(url);
            if (body != null && !body.isBlank()) {
                String[] lines = body.trim().split("\\r?\\n");
                List<Double> list = new ArrayList<>();
                for (int i = 1; i < lines.length; i++) {            // skip header
                    String[] col = lines[i].split(",");
                    if (col.length >= 5) {
                        try { list.add(Double.parseDouble(col[4])); } catch (NumberFormatException ignore) {}
                    }
                }
                closes = list.stream().mapToDouble(Double::doubleValue).toArray();
            }
        } catch (Exception ignore) {
            // network/parse failure -> empty, callers degrade gracefully
        }
        histCache.put(ticker, new Cached(System.currentTimeMillis(), closes));
        return closes;
    }

    private static Double lastClose(double[] c) { return c.length == 0 ? null : c[c.length - 1]; }

    private static double avgLast(double[] c, int n) {
        int from = Math.max(0, c.length - n);
        double sum = 0; int cnt = 0;
        for (int i = from; i < c.length; i++) { sum += c[i]; cnt++; }
        return cnt == 0 ? 0 : sum / cnt;
    }

    private static double round(double v, int dp) {
        double f = Math.pow(10, dp);
        return Math.round(v * f) / f;
    }

    // ---------- suggestions ----------
    public List<Suggestion> suggestions() {
        List<Suggestion> scored = new ArrayList<>();
        for (String t : WATCHLIST) {
            double[] c = history(t);
            if (c.length < 61) continue;                 // need enough data
            double last = c[c.length - 1];
            double ret20 = last / c[c.length - 21] - 1;
            double ret60 = last / c[c.length - 61] - 1;
            double sma50 = avgLast(c, 50);
            double trend = last / sma50 - 1;
            double score = 100 * (0.5 * ret20 + 0.3 * ret60 + 0.2 * trend);
            String rationale = String.format("20d %+.1f%%, 60d %+.1f%%, %s 50-day avg",
                ret20 * 100, ret60 * 100, last >= sma50 ? "above" : "below");
            scored.add(new Suggestion(0, t, NAMES.getOrDefault(t, t), round(last, 2), round(score, 2), rationale));
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<Suggestion> top = new ArrayList<>();
        for (int i = 0; i < Math.min(3, scored.size()); i++) {
            Suggestion s = scored.get(i);
            top.add(new Suggestion(i + 1, s.ticker(), s.name(), s.price(), s.score(), s.rationale()));
        }
        return top;
    }

    // ---------- portfolio + PnL ----------
    public Portfolio portfolio() {
        Map<String, double[]> holdings = readCsv();   // ticker -> [qty, avgPrice]
        List<Position> positions = new ArrayList<>();
        double totalCost = 0, totalValue = 0;
        boolean allPriced = true;

        for (Map.Entry<String, double[]> e : holdings.entrySet()) {
            String ticker = e.getKey();
            double qty = e.getValue()[0], avg = e.getValue()[1];
            double cost = qty * avg;
            totalCost += cost;

            double[] c = history(ticker);
            Double price = lastClose(c);
            Double value = null, pnl = null, pnlPct = null;
            String signal = "N/A", reason = "Price unavailable";

            if (price != null) {
                value = round(qty * price, 2);
                pnl = round(value - cost, 2);
                pnlPct = cost != 0 ? round((value - cost) / cost * 100, 2) : null;
                totalValue += value;
                double[] sig = sellSignal(price, avg, c);
                signal = sig[0] == 1 ? "SELL" : sig[0] == 0.5 ? "TRIM" : "HOLD";
                reason = signalReason(price, avg, c);
            } else {
                allPriced = false;
            }
            positions.add(new Position(ticker, round(qty, 4), round(avg, 2), price == null ? null : round(price, 2),
                value, round(cost, 2), pnl, pnlPct, signal, reason));
        }

        positions.sort((a, b) -> a.ticker().compareTo(b.ticker()));
        boolean priced = allPriced && !positions.isEmpty();
        Double tv = priced ? round(totalValue, 2) : null;
        Double tp = priced ? round(totalValue - totalCost, 2) : null;
        Double tpp = priced && totalCost != 0 ? round((totalValue - totalCost) / totalCost * 100, 2) : null;
        String asOf = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        return new Portfolio(positions, round(totalCost, 2), tv, tp, tpp, priced, asOf,
            "Stooq (end-of-day / delayed)");
    }

    /** returns [signalCode] where 1=SELL, 0.5=TRIM, 0=HOLD */
    private double[] sellSignal(double price, double avg, double[] c) {
        double ret = price / avg - 1;
        if (ret >= TAKE_PROFIT) return new double[]{1};
        if (ret <= STOP_LOSS)   return new double[]{1};
        if (c.length >= 51) {
            double sma50 = avgLast(c, 50);
            double ret20 = c.length >= 21 ? price / c[c.length - 21] - 1 : 0;
            if (price < sma50 && ret20 < 0) return new double[]{0.5};
        }
        return new double[]{0};
    }

    private String signalReason(double price, double avg, double[] c) {
        double ret = price / avg - 1;
        if (ret >= TAKE_PROFIT) return String.format("Up %.0f%% — consider taking profit", ret * 100);
        if (ret <= STOP_LOSS)   return String.format("Down %.0f%% — consider stop-loss", ret * 100);
        if (c.length >= 51) {
            double sma50 = avgLast(c, 50);
            double ret20 = c.length >= 21 ? price / c[c.length - 21] - 1 : 0;
            if (price < sma50 && ret20 < 0) return "Below 50-day avg and weakening";
        }
        return String.format("Holding (%+.0f%%), trend intact", ret * 100);
    }

    // ---------- trades / CSV persistence ----------
    public synchronized Portfolio recordTrade(String tickerRaw, double quantity, double price, String action) {
        String ticker = tickerRaw == null ? "" : tickerRaw.trim().toUpperCase();
        if (ticker.isEmpty() || quantity <= 0) return portfolio();
        boolean sell = "sell".equalsIgnoreCase(action);

        Map<String, double[]> holdings = readCsv();
        double[] cur = holdings.getOrDefault(ticker, new double[]{0, 0});
        double qty = cur[0], avg = cur[1];

        if (sell) {
            double newQty = qty - quantity;
            if (newQty <= 1e-9) holdings.remove(ticker);
            else holdings.put(ticker, new double[]{newQty, avg});   // avg cost basis unchanged on sells
        } else {
            double newQty = qty + quantity;
            double newAvg = newQty == 0 ? price : (qty * avg + quantity * price) / newQty;
            holdings.put(ticker, new double[]{newQty, newAvg});
        }
        writeCsv(holdings);
        return portfolio();
    }

    public synchronized Portfolio removeHolding(String tickerRaw) {
        String ticker = tickerRaw == null ? "" : tickerRaw.trim().toUpperCase();
        Map<String, double[]> holdings = readCsv();
        holdings.remove(ticker);
        writeCsv(holdings);
        return portfolio();
    }

    private Map<String, double[]> readCsv() {
        Map<String, double[]> map = new LinkedHashMap<>();
        try {
            if (!Files.exists(csv)) return map;
            List<String> lines = Files.readAllLines(csv);
            for (String line : lines) {
                if (line.isBlank() || line.toLowerCase().startsWith("ticker")) continue;
                String[] col = line.split(",");
                if (col.length >= 3) {
                    try {
                        map.put(col[0].trim().toUpperCase(),
                            new double[]{Double.parseDouble(col[1].trim()), Double.parseDouble(col[2].trim())});
                    } catch (NumberFormatException ignore) {}
                }
            }
        } catch (Exception ignore) {}
        return map;
    }

    private void writeCsv(Map<String, double[]> holdings) {
        StringBuilder sb = new StringBuilder("ticker,quantity,avgPrice\n");
        for (Map.Entry<String, double[]> e : holdings.entrySet()) {
            sb.append(e.getKey()).append(',')
              .append(e.getValue()[0]).append(',')
              .append(e.getValue()[1]).append('\n');
        }
        try {
            if (csv.getParent() != null) Files.createDirectories(csv.getParent());
            Files.writeString(csv, sb.toString());
        } catch (Exception ignore) {}
    }
}
