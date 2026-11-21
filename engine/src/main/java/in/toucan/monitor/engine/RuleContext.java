package in.toucan.monitor.engine;

import in.toucan.monitor.engine.Model.Rec;
import in.toucan.monitor.engine.Model.SourceRef;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Inputs for one evaluation: data, config, clock and the business-date window. */
public record RuleContext(DataView data, EngineConfig config, Clock clock, LocalDate from, LocalDate to) {

    public Fields f() {
        return config.f();
    }

    /** Records of a dataset, or empty if unavailable (callers check {@link #unavailable}). */
    public List<Rec> records(String dataset) {
        return data.state(dataset).available() ? data.records(dataset) : List.of();
    }

    /** Reason a dataset cannot be used, or empty if it is usable. */
    public Optional<String> unavailable(String... datasets) {
        for (String d : datasets) {
            Model.DatasetState s = data.state(d);
            if (!s.available()) {
                return Optional.of("Source not available: " + d + (s.error() == null ? "" : " (" + s.error() + ")"));
            }
        }
        return Optional.empty();
    }

    public SourceRef datasetRef(String dataset) {
        Model.DatasetState s = data.state(dataset);
        var spec = in.toucan.monitor.collector.Datasets.find(dataset);
        return new SourceRef(spec.map(x -> x.system().name()).orElse("EXTERNAL"), dataset,
                spec.map(x -> x.path()).orElse(null), spec.map(x -> x.screen()).orElse(null), null, null,
                s.lastSuccessAt());
    }

    public boolean inWindow(LocalDate d) {
        return d != null && !d.isBefore(from) && !d.isAfter(to);
    }

    public String processor(com.fasterxml.jackson.databind.JsonNode txn) {
        return f().text(txn, "txn.platform");
    }
}
