package in.toucan.monitor.backend.service;

import in.toucan.monitor.backend.config.MonitorProperties;
import in.toucan.monitor.engine.Fields;
import in.toucan.monitor.engine.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads PayU recon CSV files from a configured directory and aggregates them per business date.
 * Disabled until the business confirms the source location AND the column names (monitor.payu-recon.columns:
 * date, amount, processingFee, serviceTax, settlementAmount, netAmount). Nothing is assumed.
 */
@Service
public class PayuReconLoader {

    private static final Logger log = LoggerFactory.getLogger(PayuReconLoader.class);
    static final List<String> REQUIRED = List.of("date", "amount");

    private final MonitorProperties.PayuRecon cfg;

    public PayuReconLoader(MonitorProperties props) {
        this.cfg = props.getPayuRecon();
    }

    public boolean configured() {
        return cfg.isEnabled() && cfg.getDirectory() != null
                && REQUIRED.stream().allMatch(k -> cfg.getColumns().containsKey(k));
    }

    public List<Model.ReconTotals> load() {
        if (!configured()) return List.of();
        Map<LocalDate, Agg> byDate = new HashMap<>();
        DateTimeFormatter df = DateTimeFormatter.ofPattern(cfg.getDateFormat());
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(Path.of(cfg.getDirectory()), cfg.getFileGlob())) {
            for (Path file : ds) {
                List<String> lines = Files.readAllLines(file);
                if (lines.isEmpty()) continue;
                List<String> header = split(lines.get(0));
                Map<String, Integer> idx = new HashMap<>();
                cfg.getColumns().forEach((logical, col) -> idx.put(logical, header.indexOf(col)));
                if (idx.get("date") < 0 || idx.get("amount") < 0) {
                    log.warn("PayU recon file {} lacks configured date/amount columns", file.getFileName());
                    continue;
                }
                for (String line : lines.subList(1, lines.size())) {
                    List<String> c = split(line);
                    LocalDate d;
                    try {
                        d = LocalDate.parse(c.get(idx.get("date")).trim().substring(0, cfg.getDateFormat().length()), df);
                    } catch (Exception e) {
                        continue;
                    }
                    Agg a = byDate.computeIfAbsent(d, k -> new Agg(file));
                    a.count++;
                    a.amount = a.amount.add(num(c, idx.get("amount")));
                    a.fees = a.fees.add(num(c, idx.getOrDefault("processingFee", -1)));
                    a.tax = a.tax.add(num(c, idx.getOrDefault("serviceTax", -1)));
                    a.settle = a.settle.add(num(c, idx.getOrDefault("settlementAmount", -1)));
                    a.net = a.net.add(num(c, idx.getOrDefault("netAmount", -1)));
                    a.hasNet |= idx.getOrDefault("netAmount", -1) >= 0;
                }
            }
        } catch (IOException e) {
            log.warn("PayU recon directory unreadable: {}", e.getMessage());
            return List.of();
        }
        List<Model.ReconTotals> out = new ArrayList<>();
        byDate.forEach((d, a) -> out.add(new Model.ReconTotals(d, a.count, a.amount, a.fees, a.tax, a.settle,
                a.hasNet ? a.net : null, new Model.SourceRef("PAYU_RECON", "ext.payu_recon",
                a.file.toString(), null, d.toString(), null, Instant.now()))));
        return out;
    }

    private static BigDecimal num(List<String> c, int i) {
        if (i < 0 || i >= c.size()) return BigDecimal.ZERO;
        return Fields.parseAmount(c.get(i)).orElse(BigDecimal.ZERO);
    }

    /** Minimal CSV split honouring double quotes. */
    static List<String> split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') q = !q;
            else if (ch == ',' && !q) {
                out.add(sb.toString());
                sb.setLength(0);
            } else sb.append(ch);
        }
        out.add(sb.toString());
        return out;
    }

    private static final class Agg {
        final Path file;
        long count;
        BigDecimal amount = BigDecimal.ZERO, fees = BigDecimal.ZERO, tax = BigDecimal.ZERO, settle = BigDecimal.ZERO,
                net = BigDecimal.ZERO;
        boolean hasNet;

        Agg(Path f) {
            file = f;
        }
    }
}
