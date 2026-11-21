package in.toucan.monitor.collector;

import java.util.List;
import java.util.Map;

/**
 * One source dataset: where it lives, how to ask for it, and how to identify a record.
 *
 * @param key               stable dataset key used in storage and in the dashboard
 * @param label             human label
 * @param system            source system
 * @param path              API path (also the messageID)
 * @param style             request-body convention
 * @param baseRequestObject fixed filters the UI itself sends (observed)
 * @param sortField         sort field the UI sends, or null
 * @param sortOrder         ASC/DESC, or null
 * @param fromField         requestObject key for the start date (yyyy-MM-dd), or null if not date-filterable
 * @param toField           requestObject key for the end date, or null
 * @param naturalKey        field names composing the natural key; empty means "content hash"
 * @param screen            the UI screen a user can open to see the same data (evidence link)
 * @param snapshot          true when the whole query result is stored as ONE record keyed by the query
 *                          (trial balance, ledger statements, per-RRN postings). This keeps duplicate rows
 *                          intact, which a per-row content-hash key would silently merge.
 */
public record DatasetSpec(
        String key,
        String label,
        ToucanSystem system,
        String path,
        BodyStyle style,
        Map<String, Object> baseRequestObject,
        String sortField,
        String sortOrder,
        String fromField,
        String toField,
        List<String> naturalKey,
        String screen,
        boolean snapshot) {

    public boolean dateFilterable() {
        return fromField != null && toField != null;
    }
}
