package in.toucan.monitor.collector;

/**
 * The request-body conventions used by the Toucan Angular apps (observed in Phase 1 bundles).
 */
public enum BodyStyle {
    /** postService.doPostGetAll: {messageID, requestType:"LIST", requestObject, pageNo, pageSize}. */
    LIST_ENVELOPE,
    /** postService.getAll: {pageNo, pageSize, sortField, sortOrder, requestObject?} posted as-is. */
    PAGED_FLAT,
    /** doPostGetAllWithSearchObj: {requestObject, page, size, sortOrder, useOrOperator:false}. */
    SEARCH_OBJ,
    /** doPostGetAllWithUPISummarySearch: {messageID, requestType:"LIST", searchQuery, pageNo, pageSize}. */
    UPI_SEARCH,
    /** Accounting doPostDownload (used by Trial Balance to list): {messageID, downloadType, requestObject, pageNo, pageSize, sortOrder, sortField}. */
    DOWNLOAD_LIST
}
