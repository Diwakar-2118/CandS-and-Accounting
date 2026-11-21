package in.toucan.monitor.backend.service;

import in.toucan.monitor.backend.domain.ApiCallEntity;
import in.toucan.monitor.backend.repo.Repos;
import in.toucan.monitor.collector.ApiCall;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/** Persists every outbound call against the current run (network log; no bodies, no tokens). */
@Component
public class ApiCallRecorder implements Consumer<ApiCall> {

    private final Repos.ApiCallRepo repo;
    private volatile Long currentRunId;

    public ApiCallRecorder(Repos.ApiCallRepo repo) {
        this.repo = repo;
    }

    public void bind(Long runId) {
        this.currentRunId = runId;
    }

    @Override
    public void accept(ApiCall c) {
        ApiCallEntity e = new ApiCallEntity();
        e.runId = currentRunId;
        e.at = c.at();
        e.systemName = c.system().name();
        e.method = c.method();
        e.path = c.path();
        e.httpStatus = c.httpStatus();
        e.envelopeSuccess = c.envelopeSuccess();
        e.envelopeStatusCode = c.envelopeStatusCode();
        e.durationMs = c.durationMs();
        e.blocked = c.blocked();
        e.error = c.error();
        repo.save(e);
    }
}
