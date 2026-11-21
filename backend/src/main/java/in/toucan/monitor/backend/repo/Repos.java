package in.toucan.monitor.backend.repo;

import in.toucan.monitor.backend.domain.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** Spring Data repositories (nested; enabled with considerNestedRepositories). */
public final class Repos {

    private Repos() {
    }

    public interface RunRepo extends JpaRepository<RunEntity, Long> {
        Page<RunEntity> findAllByOrderByIdDesc(Pageable p);

        Optional<RunEntity> findFirstByStatusOrderByIdDesc(String status);
    }

    public interface SnapshotRepo extends JpaRepository<DatasetSnapshotEntity, Long> {
        Optional<DatasetSnapshotEntity> findFirstByDatasetOrderByIdDesc(String dataset);

        Optional<DatasetSnapshotEntity> findFirstByDatasetAndOkTrueOrderByIdDesc(String dataset);

        List<DatasetSnapshotEntity> findByRunId(Long runId);

        List<DatasetSnapshotEntity> findByDatasetAndRunId(String dataset, Long runId);

        @Query("select s from DatasetSnapshotEntity s where s.ok = false and s.id in "
                + "(select max(x.id) from DatasetSnapshotEntity x group by x.dataset)")
        List<DatasetSnapshotEntity> latestFailures();
    }

    public interface SourceRecordRepo extends JpaRepository<SourceRecordEntity, Long> {
        Optional<SourceRecordEntity> findByDatasetAndNaturalKey(String dataset, String naturalKey);

        List<SourceRecordEntity> findByDataset(String dataset);

        List<SourceRecordEntity> findByDatasetAndLastRunId(String dataset, Long runId);

        @Query("select r from SourceRecordEntity r where r.dataset = :ds and r.naturalKey in :keys")
        List<SourceRecordEntity> findByDatasetAndKeys(@Param("ds") String ds, @Param("keys") Collection<String> keys);

        long countByDataset(String dataset);
    }

    public interface HistoryRepo extends JpaRepository<SourceRecordHistoryEntity, Long> {
        List<SourceRecordHistoryEntity> findBySourceRecordIdOrderByIdDesc(Long id);
    }

    public interface TxnRepo extends JpaRepository<CsTxnEntity, Long>, JpaSpecificationExecutor<CsTxnEntity> {
        List<CsTxnEntity> findBySalesDateBetween(LocalDate from, LocalDate to);

        List<CsTxnEntity> findByRrn(String rrn);

        List<CsTxnEntity> findByRunNumberAndMerchantNumber(String run, String mid);

        @Query("select t from CsTxnEntity t where t.salesDate between :f and :t and t.rrn is not null "
                + "and (t.accountingStatus is null or t.accountingStatus <> 'COMPLETED') order by t.salesDate asc")
        List<CsTxnEntity> needingAccountingLookup(@Param("f") LocalDate f, @Param("t") LocalDate t, Pageable p);

        @Query("select distinct t.platformType from CsTxnEntity t where t.platformType is not null")
        List<String> distinctPlatforms();

        @Query("select distinct t.schemeCode from CsTxnEntity t where t.schemeCode is not null")
        List<String> distinctSchemes();
    }

    public interface ResultRepo extends JpaRepository<RuleResultEntity, Long> {
        List<RuleResultEntity> findByRunId(Long runId);

        List<RuleResultEntity> findByRunIdAndRuleId(Long runId, String ruleId);

        @Query("select r from RuleResultEntity r where r.runId = :run and (r.subjectKey = :k or r.subjectKey like :prefix)")
        List<RuleResultEntity> findForSubject(@Param("run") Long run, @Param("k") String key,
                                              @Param("prefix") String prefix);

        @Query("select max(r.runId) from RuleResultEntity r where r.ruleId = :rule")
        Long latestRunForRule(@Param("rule") String rule);
    }

    public interface MappingRepo extends JpaRepository<TtMappingEntity, String> {
        List<TtMappingEntity> findAllByOrderByCodeAsc();
    }

    public interface ApiCallRepo extends JpaRepository<ApiCallEntity, Long> {
        List<ApiCallEntity> findByRunIdOrderByIdAsc(Long runId);

        long countByBlockedTrue();

        @Query("select c from ApiCallEntity c where c.envelopeSuccess = false or c.httpStatus >= 400 or c.httpStatus < 0 "
                + "order by c.id desc")
        Page<ApiCallEntity> failures(Pageable p);
    }

    public interface AuditRepo extends JpaRepository<AuditLogEntity, Long> {
        Page<AuditLogEntity> findAllByOrderByIdDesc(Pageable p);
    }

    public interface ProcessorRepo extends JpaRepository<ProcessorEntity, String> {
        List<ProcessorEntity> findAllByOrderByCodeAsc();
    }
}
