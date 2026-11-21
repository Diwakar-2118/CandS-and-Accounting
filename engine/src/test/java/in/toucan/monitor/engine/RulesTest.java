package in.toucan.monitor.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.toucan.monitor.engine.Model.*;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static in.toucan.monitor.collector.Datasets.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Fixtures reproduce values observed on the UAT screens on 20 Nov 2026. */
class RulesTest {

    static final ObjectMapper OM = new ObjectMapper();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-20T06:30:00Z"), ZoneOffset.UTC);

    static class MemView implements DataView {
        final Map<String, List<Rec>> data = new HashMap<>();
        final Set<String> down = new java.util.HashSet<>();
        final List<TtMapping> mappings = new ArrayList<>();

        MemView put(String ds, String... json) {
            List<Rec> l = data.computeIfAbsent(ds, k -> new ArrayList<>());
            for (String j : json) {
                try {
                    JsonNode n = OM.readTree(j);
                    l.add(new Rec(new SourceRef("X", ds, "/api", "#/", ds + "#" + l.size(), null, CLOCK.instant()), n));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
            return this;
        }

        public DatasetState state(String ds) {
            return down.contains(ds) ? DatasetState.unavailable("HTTP 200 success=false NOTUSED")
                    : new DatasetState(true, null, CLOCK.instant(), false);
        }

        public List<Rec> records(String ds) {
            return data.getOrDefault(ds, List.of());
        }

        public List<TtMapping> mappings() {
            return mappings;
        }

        public List<ReconTotals> payuRecon() {
            return List.of();
        }

        public boolean payuReconConfigured() {
            return false;
        }
    }

    static RuleContext ctx(DataView v, EngineConfig c) {
        return new RuleContext(v, c, CLOCK, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 20));
    }

    static List<RuleResult> run(String id, DataView v, EngineConfig c) {
        return RuleEngine.standard().run(ctx(v, c), Set.of(id));
    }

    static RuleResult summary(List<RuleResult> rs) {
        return rs.stream().filter(r -> r.subjectKey().equals("SUMMARY")).findFirst().orElseThrow();
    }

    @Test
    void r12_trialBalanceDifferenceObservedInUatIsAFailureWithEvidence() {
        MemView v = new MemView().put(ACC_TRIAL_BALANCE, """
                {"query":{"category":"group","fromDate":"2026-11-01","toDate":"2026-11-20"},
                 "rows":[{"accountGroup":"ASSETS","debit":"993023886.31","credit":0},
                         {"accountGroup":"LIABILITIES","debit":0,"credit":992839306.82}]}""");
        List<RuleResult> rs = run("R12", v, EngineConfig.defaults());
        RuleResult fail = rs.stream().filter(r -> r.outcome() == Outcome.FAIL).findFirst().orElseThrow();
        assertThat(fail.message()).contains("184579.49");
        assertThat(fail.evidence().details()).containsEntry("difference", "184579.49");
        assertThat(fail.evidence().sources()).isNotEmpty();
    }

    @Test
    void r13_unbalancedAdjustmentObservedInUat() {
        MemView v = new MemView().put(ACC_LEDGER_ADJUSTMENTS,
                "{\"id\":7,\"txnArray\":[{\"merchantNumber\":\"0987654321345678\",\"rrn\":\"45566879845\",\"creditAmount\":456.00,\"debitAmount\":3453.00}]}",
                "{\"id\":8,\"txnArray\":[{\"merchantNumber\":\"1\",\"rrn\":\"2\",\"creditAmount\":10,\"debitAmount\":10}]}");
        List<RuleResult> rs = run("R13", v, EngineConfig.defaults());
        assertThat(rs).filteredOn(r -> r.outcome() == Outcome.FAIL && !r.subjectKey().equals("SUMMARY")).singleElement()
                .satisfies(r -> assertThat(r.message()).contains("0987654321345678").contains("45566879845")
                        .contains("3453").contains("456"));
        assertThat(summary(rs).evidence().details()).containsEntry("passed", 1);
    }

    @Test
    void r01_feeArithmeticNotEvaluatedUntilFieldNamesConfirmed() {
        MemView v = new MemView().put(CS_TRANSACTIONS, "{\"id\":1,\"txnAmount\":{\"value\":500}}");
        RuleResult s = summary(run("R01", v, EngineConfig.defaults()));
        assertThat(s.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(s.message()).contains("not confirmed");
    }

    @Test
    void r01_feeArithmeticOnObservedExampleOnceConfigured() {
        Map<String, List<String>> fields = EngineConfig.observedFieldDefaults();
        fields.put("txn.mdrAmount", List.of("mdr"));
        fields.put("txn.mdrCgst", List.of("mdrCgst"));
        fields.put("txn.mdrSgst", List.of("mdrSgst"));
        fields.put("txn.feeAmount", List.of("fee"));
        fields.put("txn.feeCgst", List.of("feeCgst"));
        fields.put("txn.feeSgst", List.of("feeSgst"));
        fields.put("txn.paymentAmount", List.of("pay"));
        EngineConfig base = EngineConfig.defaults();
        EngineConfig c = new EngineConfig(fields, base.tolerance(), null, null, Map.of(), base.fileSuccessPatterns(),
                List.of(), base.payuPlatformValues());
        MemView v = new MemView().put(CS_TRANSACTIONS,
                "{\"id\":1,\"txnAmount\":{\"value\":500},\"mdr\":10,\"mdrCgst\":0.90,\"mdrSgst\":0.90,\"fee\":0.45,\"feeCgst\":0.04,\"feeSgst\":0.04,\"pay\":487.67}",
                "{\"id\":2,\"txnAmount\":{\"value\":500},\"mdr\":10,\"mdrCgst\":0.90,\"mdrSgst\":0.90,\"fee\":0.45,\"feeCgst\":0.04,\"feeSgst\":0.04,\"pay\":490.00}");
        List<RuleResult> rs = run("R01", v, c);
        assertThat(rs).filteredOn(r -> r.outcome() == Outcome.FAIL && !r.subjectKey().equals("SUMMARY")).singleElement()
                .satisfies(r -> assertThat(r.evidence().expected()).isEqualTo("487.67"));
    }

    @Test
    void r14_periodsObservedInUat() {
        MemView v = new MemView()
                .put(ACC_PERIODS,
                        "{\"periodName\":\"APR-2026\",\"status\":\"CLOSED\",\"startDate\":\"02/04/2026\",\"endDate\":\"30/04/2026\"}",
                        "{\"periodName\":\"JUN-2026\",\"status\":\"OPEN\",\"startDate\":\"01/06/2026\",\"endDate\":\"30/06/2026\"}",
                        "{\"periodName\":\"NOV-2026\",\"status\":\"FUTURE\",\"startDate\":\"01/11/2026\",\"endDate\":\"30/11/2026\"}")
                .put(ACC_FISCAL_YEARS,
                        "{\"fiscalYearCode\":\"FY26-27\",\"startDate\":\"01/04/2026\",\"endDate\":\"31/03/2027\"}");
        List<RuleResult> rs = run("R14", v, EngineConfig.defaults());
        assertThat(rs).filteredOn(r -> r.outcome() == Outcome.FAIL && !r.subjectKey().equals("SUMMARY")).extracting(RuleResult::message)
                .anySatisfy(m -> assertThat(m).contains("No OPEN period covers 2026-11-20").contains("NOV-2026 is FUTURE"))
                .anySatisfy(m -> assertThat(m).contains("APR-2026 starts 2026-04-02"))
                .anySatisfy(m -> assertThat(m).startsWith("Gap between APR-2026"));
    }

    @Test
    void unavailableSourceIsNotEvaluatedNeverPass() {
        MemView v = new MemView();
        v.down.add(CS_JV);
        RuleResult s = summary(run("R04", v, EngineConfig.defaults()));
        assertThat(s.outcome()).isEqualTo(Outcome.NOT_EVALUATED);
        assertThat(s.message()).contains("NOTUSED");
    }

    @Test
    void r08_r10_r11_accountingPostingsByRrn() {
        MemView v = new MemView()
                .put(CS_TRANSACTIONS,
                        "{\"id\":1,\"rrn\":\"A\",\"salesDateTime\":\"2026-11-19T10:00:00\",\"transactionCode\":\"PURCHASE\",\"platformType\":\"PAYU\"}",
                        "{\"id\":2,\"rrn\":\"B\",\"salesDateTime\":\"2026-11-19T10:00:00\",\"transactionCode\":\"PURCHASE\"}",
                        "{\"id\":3,\"rrn\":\"C\",\"salesDateTime\":\"2026-11-10T10:00:00\",\"transactionCode\":\"PURCHASE\"}")
                .put(ACC_RRN_POSTINGS,
                        "{\"query\":{\"rrn\":\"A\"},\"netZero\":true,\"rows\":[{\"subModule\":\"AP001\",\"ledgerName\":\"L1\",\"debitAmount\":100,\"creditAmount\":0},{\"subModule\":\"AP001\",\"ledgerName\":\"L2\",\"debitAmount\":0,\"creditAmount\":100},{\"subModule\":\"AP001\",\"ledgerName\":\"L2\",\"debitAmount\":0,\"creditAmount\":100}]}",
                        "{\"query\":{\"rrn\":\"B\"},\"rows\":[]}",
                        "{\"query\":{\"rrn\":\"C\"},\"rows\":[]}");
        EngineConfig d = EngineConfig.defaults();
        EngineConfig c = new EngineConfig(d.fields(), d.tolerance(), null, 48, Map.of(), d.fileSuccessPatterns(),
                List.of(), d.payuPlatformValues());
        assertThat(run("R08", v, c)).filteredOn(r -> r.subjectKey().equals(CS_TRANSACTIONS + "#0"))
                .singleElement().extracting(RuleResult::outcome).isEqualTo(Outcome.FAIL); // 100 DR vs 200 CR
        List<RuleResult> r10 = run("R10", v, c);
        assertThat(r10).filteredOn(r -> r.subjectKey().equals(CS_TRANSACTIONS + "#1")).singleElement()
                .extracting(RuleResult::outcome).isEqualTo(Outcome.PENDING);
        assertThat(r10).filteredOn(r -> r.subjectKey().equals(CS_TRANSACTIONS + "#2")).singleElement()
                .extracting(RuleResult::outcome).isEqualTo(Outcome.MISSING);
        assertThat(run("R11", v, c)).anyMatch(r -> r.outcome() == Outcome.FAIL && r.message().contains("2x"));
    }

    @Test
    void r09_unconfirmedMappingIsReportedNotValidated() {
        Map<String, List<String>> fields = EngineConfig.observedFieldDefaults();
        fields.put("posting.ttCode", List.of("subModule"));
        EngineConfig d = EngineConfig.defaults();
        EngineConfig c = new EngineConfig(fields, d.tolerance(), null, null, Map.of(), d.fileSuccessPatterns(),
                List.of(), d.payuPlatformValues());
        MemView v = new MemView().put(ACC_RRN_POSTINGS,
                "{\"query\":{\"rrn\":\"A\"},\"rows\":[{\"subModule\":\"PU001\",\"ledgerName\":\"X\",\"debitAmount\":1},{\"subModule\":\"AP001\",\"ledgerName\":\"Wrong\",\"debitAmount\":1}]}");
        SourceRef src = new SourceRef("ACCOUNTING", ACC_TXN_TYPES, "/api/transactiontype/getall", "#/", "PU001", null, CLOCK.instant());
        v.mappings.add(new TtMapping("PU001", "PayU", List.of(new GlLine("X", Side.DR)), MappingStatus.UNCONFIRMED, src));
        v.mappings.add(new TtMapping("AP001", "Merchant Transaction Recording AP",
                List.of(new GlLine("Sundry_Suspense_Merchant_Acquiring_AP", Side.DR),
                        new GlLine("Sundry_Suspense_Merchant_Payable_AP", Side.CR)), MappingStatus.CONFIRMED, src));
        List<RuleResult> rs = run("R09", v, c);
        assertThat(rs).anyMatch(r -> r.ttCode() != null && r.ttCode().equals("PU001")
                && r.outcome() == Outcome.NOT_EVALUATED && r.message().equals("Mapping not confirmed"));
        assertThat(rs).anyMatch(r -> "AP001".equals(r.ttCode()) && r.outcome() == Outcome.FAIL);
    }

    @Test
    void r15_runningBalance() {
        MemView v = new MemView().put(ACC_LEDGER_STATEMENT, """
                {"query":{"entryType":"Bank_Balance_Escrow_AP"},"rows":[
                  {"descriptionType":"Opening Balance","balance":"1,000.00 DR"},
                  {"descriptionType":"Approved Transaction","debitAmount":500,"creditAmount":0,"balance":"1,500.00 DR"},
                  {"descriptionType":"Merchant_transaction_recording","debitAmount":0,"creditAmount":2000,"balance":"400.00 CR"}]}""");
        assertThat(run("R15", v, EngineConfig.defaults())).filteredOn(r -> r.outcome() == Outcome.FAIL && !r.subjectKey().equals("SUMMARY"))
                .singleElement().satisfies(r -> assertThat(r.evidence().expected()).isEqualTo("-500.00"));
    }

    @Test
    void r05_r06_filesAndBdf() {
        MemView v = new MemView()
                .put(CS_SCHEME_FILES,
                        "{\"fileName\":\"a\",\"fileStatus\":\"OUTGOINGFILE_GENERATED_SUCCESSFULLY\",\"localTxnDate\":\"2026-11-19\"}",
                        "{\"fileName\":\"b\",\"fileStatus\":\"OUTGOINGFILE_GENERATION_FAILED\",\"localTxnDate\":\"2026-11-19\"}")
                .put(CS_UPI_SUMMARY_BDF, "{\"fileName\":\"Merchant_Payout_Report_1.csv\",\"isBdfGenerated\":false}",
                        "{\"fileName\":\"Merchant_Payout_Report_2.csv\",\"isBdfGenerated\":true}");
        List<RuleResult> r5 = run("R05", v, EngineConfig.defaults());
        assertThat(r5).filteredOn(r -> r.outcome() == Outcome.FAIL && !r.subjectKey().equals("SUMMARY")).hasSize(1);
        assertThat(summary(r5).message()).contains("calendar not supplied");
        assertThat(run("R06", v, EngineConfig.defaults())).filteredOn(r -> r.outcome() == Outcome.FAIL && !r.subjectKey().equals("SUMMARY")).hasSize(1);
    }
}
