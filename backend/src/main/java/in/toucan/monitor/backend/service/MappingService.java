package in.toucan.monitor.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import in.toucan.monitor.backend.config.MonitorProperties;
import in.toucan.monitor.backend.domain.SourceRecordEntity;
import in.toucan.monitor.backend.domain.TtMappingEntity;
import in.toucan.monitor.backend.repo.Repos;
import in.toucan.monitor.collector.Datasets;
import in.toucan.monitor.engine.Fields;
import in.toucan.monitor.engine.Model;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Transaction-type mappings are loaded from Accounting's own Transaction Types (never typed into code).
 * Every code starts UNCONFIRMED; an OPERATOR confirms it after comparing with the business brief.
 * If Accounting later changes a confirmed code's G/L lines, it drops back to UNCONFIRMED.
 */
@Service
public class MappingService {

    private final Repos.MappingRepo repo;
    private final Repos.SourceRecordRepo records;
    private final ObjectMapper om;
    private final Fields f;
    private final AuditService audit;

    public MappingService(Repos.MappingRepo repo, Repos.SourceRecordRepo records, ObjectMapper om,
                          MonitorProperties props, AuditService audit) {
        this.repo = repo;
        this.records = records;
        this.om = om;
        this.f = props.toEngineConfig().f();
        this.audit = audit;
    }

    @Transactional
    public int syncFromAccounting() {
        int n = 0;
        for (SourceRecordEntity r : records.findByDataset(Datasets.ACC_TXN_TYPES)) {
            JsonNode p = read(r.payload);
            String code = f.text(p, "tt.code");
            if (code == null) continue;
            ArrayNode lines = om.createArrayNode();
            JsonNode src = f.node(p, "tt.lines");
            if (src.isArray()) {
                for (JsonNode l : src) {
                    String side = normaliseSide(f.text(l, "tt.line.side"));
                    lines.addObject().put("account", f.text(l, "tt.line.account")).put("side", side);
                }
            }
            String linesJson = lines.toString();
            TtMappingEntity m = repo.findById(code).orElseGet(TtMappingEntity::new);
            boolean isNew = m.code == null;
            m.code = code;
            m.name = f.text(p, "tt.name");
            if (!isNew && "CONFIRMED".equals(m.status) && !linesJson.equals(m.glLines)) {
                m.status = "UNCONFIRMED";
                m.note = "G/L lines changed in Accounting at " + r.retrievedAt + "; re-confirmation required";
            }
            if (isNew) m.status = "UNCONFIRMED";
            m.glLines = linesJson;
            m.sourceRecordKey = r.naturalKey;
            m.sourceHash = r.contentHash;
            m.retrievedAt = r.retrievedAt;
            repo.save(m);
            n++;
        }
        return n;
    }

    @Transactional
    public TtMappingEntity setStatus(String code, boolean confirmed, String note) {
        TtMappingEntity m = repo.findById(code).orElseThrow(() -> new IllegalArgumentException("Unknown TT " + code));
        m.status = confirmed ? "CONFIRMED" : "UNCONFIRMED";
        m.confirmedBy = AuditService.currentUser();
        m.confirmedAt = Instant.now();
        m.note = note;
        audit.log(confirmed ? "MAPPING_CONFIRM" : "MAPPING_UNCONFIRM", code + (note == null ? "" : ": " + note));
        return repo.save(m);
    }

    public List<Model.TtMapping> engineMappings() {
        List<Model.TtMapping> out = new ArrayList<>();
        for (TtMappingEntity m : repo.findAll()) {
            List<Model.GlLine> lines = new ArrayList<>();
            for (JsonNode l : read(m.glLines)) {
                Optional.ofNullable(l.path("side").asText(null)).filter(s -> s.equals("DR") || s.equals("CR"))
                        .ifPresent(s -> lines.add(new Model.GlLine(l.path("account").asText(), Model.Side.valueOf(s))));
            }
            out.add(new Model.TtMapping(m.code, m.name, lines, Model.MappingStatus.valueOf(m.status),
                    new Model.SourceRef("ACCOUNTING", Datasets.ACC_TXN_TYPES, "/api/transactiontype/getall",
                            "#/configurations/transactiontype-getall", m.sourceRecordKey, null, m.retrievedAt)));
        }
        return out;
    }

    static String normaliseSide(String s) {
        if (s == null) return null;
        String u = s.trim().toUpperCase();
        if (u.startsWith("D")) return "DR";
        if (u.startsWith("C")) return "CR";
        return u;
    }

    private JsonNode read(String s) {
        try {
            return om.readTree(s);
        } catch (Exception e) {
            return om.createObjectNode();
        }
    }
}
