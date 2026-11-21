package in.toucan.monitor.collector;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.Clock;
import java.util.function.Consumer;

/** Wiring helper for non-Spring callers (CLI, TestNG suites). */
public final class ToucanClients {

    public final ToucanConfig config;
    public final ObjectMapper mapper;
    public final TokenProvider tokens;
    public final ToucanApiClient api;
    public final DatasetCollector collector;

    public ToucanClients(ToucanConfig config, Consumer<ApiCall> callListener) {
        this(config, new RestAssuredTransport(config), Clock.systemUTC(), callListener);
    }

    public ToucanClients(ToucanConfig config, HttpTransport transport, Clock clock, Consumer<ApiCall> callListener) {
        this.config = config;
        this.mapper = defaultMapper();
        this.tokens = new TokenProvider(config, transport, mapper, clock, callListener);
        this.api = new ToucanApiClient(config, transport, tokens, mapper, clock, callListener);
        this.collector = new DatasetCollector(api, clock);
    }

    public static ObjectMapper defaultMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, true);
    }
}
