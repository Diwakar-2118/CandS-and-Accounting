package in.toucan.monitor.backend.config;

import in.toucan.monitor.backend.service.ApiCallRecorder;
import in.toucan.monitor.collector.ToucanClients;
import in.toucan.monitor.collector.ToucanConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ToucanBeans {

    @Bean
    ToucanConfig toucanConfig() {
        return ToucanConfig.fromEnv();
    }

    @Bean
    ToucanClients toucanClients(ToucanConfig cfg, ApiCallRecorder recorder) {
        return new ToucanClients(cfg, recorder);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
