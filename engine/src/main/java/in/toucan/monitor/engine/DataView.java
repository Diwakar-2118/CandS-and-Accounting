package in.toucan.monitor.engine;

import java.util.List;

/** Read access to stored source data for one evaluation window. */
public interface DataView {

    Model.DatasetState state(String dataset);

    List<Model.Rec> records(String dataset);

    /** Transaction-type mappings with their confirmation status (loaded from Accounting, stored in the DB). */
    List<Model.TtMapping> mappings();

    /** External PayU recon totals; empty when the source is not configured. */
    List<Model.ReconTotals> payuRecon();

    /** True when a PayU recon source has been configured at all. */
    boolean payuReconConfigured();
}
