package io.ebeaninternal.server.query;

import io.ebean.QueryIterator;
import io.ebean.Version;
import io.ebean.bean.BeanCollection;
import io.ebean.bean.EntityBean;
import io.ebean.bean.EntityBeanIntercept;
import io.ebean.bean.NodeUsageCollector;
import io.ebean.bean.NodeUsageListener;
import io.ebean.bean.ObjectGraphNode;
import io.ebean.bean.PersistenceContext;
import io.ebean.event.readaudit.ReadEvent;
import io.ebean.util.StringHelper;
import io.ebeaninternal.api.SpiQuery;
import io.ebeaninternal.api.SpiQuery.Mode;
import io.ebeaninternal.api.SpiTransaction;
import io.ebeaninternal.server.autotune.ProfilingListener;
import io.ebeaninternal.server.core.Message;
import io.ebeaninternal.server.core.OrmQueryRequest;
import io.ebeaninternal.server.core.SpiOrmQueryRequest;
import io.ebeaninternal.server.deploy.BeanCollectionHelp;
import io.ebeaninternal.server.deploy.BeanCollectionHelpFactory;
import io.ebeaninternal.server.deploy.BeanDescriptor;
import io.ebeaninternal.server.deploy.BeanPropertyAssocMany;
import io.ebeaninternal.server.deploy.DbReadContext;
import io.ebeaninternal.server.type.DataBind;
import io.ebeaninternal.server.type.DataReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.PersistenceException;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * An object that represents a SqlSelect statement.
 * <p>
 * The SqlSelect is based on a tree (Object Graph). The tree is traversed to see
 * what parts are included in the tree according to the value of
 * find.getInclude();
 * </p>
 * <p>
 * The tree structure is flattened into a SqlSelectChain. The SqlSelectChain is
 * the key object used in reading the flat resultSet back into Objects.
 * </p>
 */
public class CQuery<T> implements DbReadContext, CancelableQuery {

  private static final Logger logger = LoggerFactory.getLogger(CQuery.class);

  private static final int GLOBAL_ROW_LIMIT = Integer.valueOf(System.getProperty("ebean.query.globallimit", "1000000"));

  /**
   * The resultSet rows read.
   */
  private int rowCount;

  /**
   * The number of master EntityBeans loaded.
   */
  private int loadedBeanCount;

  /**
   * Flag set when no more rows are in the resultSet.
   */
  private boolean noMoreRows;

  /**
   * The 'master' bean just loaded.
   */
  private EntityBean nextBean;

  /**
   * Holds the previous loaded bean.
   */
  private EntityBean currentBean;

  /**
   * Caches hasNext for consecutive hasNext() calls.
   */
  private boolean hasNextCache;

  private final BeanPropertyAssocMany<?> lazyLoadManyProperty;

  private Object lazyLoadParentId;

  private EntityBean lazyLoadParentBean;

  /**
   * The 'master' collection being populated.
   */
  private final BeanCollection<T> collection;
  /**
   * The help for the 'master' collection.
   */
  private final BeanCollectionHelp<T> help;

  /**
   * The overall find request wrapper object.
   */
  private final OrmQueryRequest<T> request;

  private final BeanDescriptor<T> desc;

  private final SpiQuery<T> query;

  private final boolean disableLazyLoading;

  private Map<String, String> currentPathMap;

  private String currentPrefix;

  /**
   * Where clause predicates.
   */
  private final CQueryPredicates predicates;

  private final boolean rawSql;

  /**
   * The final sql that is generated.
   */
  private final String sql;

  /**
   * Where clause to show in logs when using an existing query plan.
   */
  private final String logWhereSql;

  /**
   * Set to true if the row number column is included in the sql.
   */
  private final boolean rowNumberIncluded;

  /**
   * Tree that knows how to build the master and detail beans from the
   * resultSet.
   */
  private final SqlTreeNode rootNode;

  /**
   * For master detail query.
   */
  private final BeanPropertyAssocMany<?> manyProperty;

  private final int maxRowsLimit;

  private DataReader dataReader;

  /**
   * The statement used to create the resultSet.
   */
  private PreparedStatement pstmt;

  private boolean cancelled;

  private String bindLog;

  private final CQueryPlan queryPlan;

  private final Mode queryMode;

  private final boolean autoTuneProfiling;

  private final ObjectGraphNode objectGraphNode;

  private final ProfilingListener profilingListener;

  private final WeakReference<NodeUsageListener> profilingListenerRef;

  private final Boolean readOnly;

  private long startNano;

  private long executionTimeMicros;

  /**
   * Flag set when findIterate is being read audited.
   */
  private boolean auditFindIterate;

  /**
   * A buffer of Ids collected for findIterate auditing.
   */
  private List<Object> auditFindIterateIds;

  /**
   * Create the Sql select based on the request.
   */
  @SuppressWarnings("unchecked")
  public CQuery(OrmQueryRequest<T> request, CQueryPredicates predicates, CQueryPlan queryPlan) {
    this.request = request;
    this.queryPlan = queryPlan;
    this.query = request.getQuery();
    this.queryMode = query.getMode();
    this.lazyLoadManyProperty = query.getLazyLoadMany();

    this.readOnly = request.isReadOnly();
    this.disableLazyLoading = query.isDisableLazyLoading();

    this.objectGraphNode = query.getParentNode();
    this.profilingListener = query.getProfilingListener();
    this.autoTuneProfiling = profilingListener != null;
    this.profilingListenerRef = autoTuneProfiling ? new WeakReference<>(profilingListener) : null;

    // set the generated sql back to the query
    // so its available to the user...
    query.setGeneratedSql(queryPlan.getSql());

    SqlTree sqlTree = queryPlan.getSqlTree();
    this.rootNode = sqlTree.getRootNode();
    this.manyProperty = sqlTree.getManyProperty();
    this.sql = queryPlan.getSql();
    this.rawSql = queryPlan.isRawSql();
    this.rowNumberIncluded = queryPlan.isRowNumberIncluded();
    this.logWhereSql = queryPlan.getLogWhereSql();
    this.desc = request.getBeanDescriptor();
    this.predicates = predicates;
    this.maxRowsLimit = query.getMaxRows() > 0 ? query.getMaxRows() : GLOBAL_ROW_LIMIT;
    this.help = createHelp(request);
    this.collection = (help != null ? help.createEmptyNoParent() : null);
  }

  private BeanCollectionHelp<T> createHelp(OrmQueryRequest<T> request) {
    if (request.isFindById()) {
      return null;
    } else {
      SpiQuery.Type manyType = request.getQuery().getType();
      if (manyType == null) {
        // subQuery compiled for InQueryExpression
        return null;
      }
      return BeanCollectionHelpFactory.create(request);
    }
  }

  @Override
  public boolean isDraftQuery() {
    return query.isAsDraft();
  }

  @Override
  public boolean isDisableLazyLoading() {
    return disableLazyLoading;
  }

  @Override
  public Boolean isReadOnly() {
    return readOnly;
  }

  @Override
  public void propagateState(Object e) {
    if (Boolean.TRUE.equals(readOnly)) {
      if (e instanceof EntityBean) {
        ((EntityBean) e)._ebean_getIntercept().setReadOnly(true);
      }
    }
  }

  @Override
  public DataReader getDataReader() {
    return dataReader;
  }

  @Override
  public Mode getQueryMode() {
    return queryMode;
  }

  public CQueryPredicates getPredicates() {
    return predicates;
  }

  SpiOrmQueryRequest<?> getQueryRequest() {
    return request;
  }

  @Override
  public void cancel() {
    synchronized (this) {
      this.cancelled = true;
      if (pstmt != null) {
        try {
          pstmt.cancel();
        } catch (SQLException e) {
          String msg = "Error cancelling query";
          throw new PersistenceException(msg, e);
        }
      }
    }
  }

  /**
   * Prepare bind and execute query with Forward only hints.
   */
  boolean prepareBindExecuteQueryForwardOnly(boolean dbPlatformForwardOnlyHint) throws SQLException {
    return prepareBindExecuteQueryWithOption(dbPlatformForwardOnlyHint);
  }

  /**
   * Prepare bind and execute the query normally.
   */
  boolean prepareBindExecuteQuery() throws SQLException {
    return prepareBindExecuteQueryWithOption(false);
  }

  private boolean prepareBindExecuteQueryWithOption(boolean forwardOnlyHint) throws SQLException {

    synchronized (this) {
      if (cancelled || query.isCancelled()) {
        // cancelled before we started
        cancelled = true;
        return false;
      }

      startNano = System.nanoTime();

      // prepare
      SpiTransaction t = request.getTransaction();
      Connection conn = t.getInternalConnection();

      if (query.isRawSql()) {
        ResultSet suppliedResultSet = query.getRawSql().getResultSet();
        if (suppliedResultSet != null) {
          // this is a user supplied ResultSet so use that
          dataReader = queryPlan.createDataReader(suppliedResultSet);
          bindLog = "";
          return true;
        }
      }

      if (forwardOnlyHint) {
        // Use forward only hints for large resultSet processing (Issue 56, MySql specific)
        pstmt = conn.prepareStatement(sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
        pstmt.setFetchSize(Integer.MIN_VALUE);
      } else {
        pstmt = conn.prepareStatement(sql);
      }

      if (query.getTimeout() > 0) {
        pstmt.setQueryTimeout(query.getTimeout());
      }
      if (query.getBufferFetchSizeHint() > 0) {
        pstmt.setFetchSize(query.getBufferFetchSizeHint());
      }

      DataBind dataBind = queryPlan.bindEncryptedProperties(pstmt, conn);
      bindLog = predicates.bind(dataBind);

      // executeQuery
      ResultSet rset = pstmt.executeQuery();
      dataReader = queryPlan.createDataReader(rset);

      return true;
    }
  }

  /**
   * Close the resources.
   * <p>
   * The JDBC resultSet and statement need to be closed. Its important that this method is called.
   * </p>
   */
  public void close() {
    try {
      if (auditFindIterateIds != null && !auditFindIterateIds.isEmpty()) {
        auditIterateLogMessage();
      }
    } catch (Throwable e) {
      logger.error("Error logging read audit logs", e);
    }
    try {
      if (dataReader != null) {
        dataReader.close();
        dataReader = null;
      }
    } catch (SQLException e) {
      logger.error("Error closing dataReader", e);
    }
    try {
      if (pstmt != null) {
        pstmt.close();
        pstmt = null;
      }
    } catch (SQLException e) {
      logger.error("Error closing preparedStatement", e);
    }
  }

  /**
   * Return the persistence context.
   */
  @Override
  public PersistenceContext getPersistenceContext() {
    return request.getPersistenceContext();
  }

  @Override
  public void setLazyLoadedChildBean(EntityBean bean, Object lazyLoadParentId) {

    if (lazyLoadParentId != null) {
      if (!lazyLoadParentId.equals(this.lazyLoadParentId)) {
        // get the appropriate parent bean from the persistence context
        this.lazyLoadParentBean = (EntityBean) lazyLoadManyProperty.getBeanDescriptor().contextGet(getPersistenceContext(), lazyLoadParentId);
        this.lazyLoadParentId = lazyLoadParentId;
      }

      // add the loadedBean to the appropriate collection of lazyLoadParentBean
      lazyLoadManyProperty.addBeanToCollectionWithCreate(lazyLoadParentBean, bean, true);
    }
  }

  /**
   * Read a row from the result set returning a bean.
   * <p>
   * If the query includes a many then the first object in the returned array is
   * the one/master and the second the many/detail.
   * </p>
   */
  private boolean readNextBean() throws SQLException {

    if (!moveToNextRow()) {
      if (currentBean == null) {
        return false;
      } else {
        // the last bean
        nextBean = currentBean;
        loadedBeanCount++;
        return true;
      }
    }

    loadedBeanCount++;

    if (manyProperty == null) {
      // only single resultSet row required to build object so we are done
      // read a single resultSet row into single bean
      nextBean = rootNode.load(this, null, null);
      return true;
    }

    if (nextBean == null) {
      // very first read
      nextBean = rootNode.load(this, null, null);
    } else {
      // nextBean set to previously read currentBean
      nextBean = currentBean;
      request.persistenceContextAdd(nextBean);
      // check the current row we have just moved to
      if (checkForDifferentBean()) {
        return true;
      }
    }
    readUntilDifferentBeanStarted();
    return true;
  }

  /**
   * Read resultSet rows until we hit the end or get a different bean.
   */
  private void readUntilDifferentBeanStarted() throws SQLException {
    while (moveToNextRow()) {
      if (checkForDifferentBean()) return;
    }
  }

  /**
   * Read the currentBean from the row data returning true if the bean
   * is different to the nextBean (false if we need to read more rows).
   */
  private boolean checkForDifferentBean() throws SQLException {
    currentBean = rootNode.load(this, null, null);
    return currentBean != nextBean;
  }

  /**
   * Return true if we can move to the next resultSet row.
   */
  private boolean moveToNextRow() throws SQLException {

    if (!dataReader.next()) {
      noMoreRows = true;
      return false;
    }

    rowCount++;
    dataReader.resetColumnPosition();
    if (rowNumberIncluded) {
      // row_number() column used for limit features
      dataReader.incrementPos(1);
    }
    return true;
  }

  long getQueryExecutionTimeMicros() {
    return executionTimeMicros;
  }

  boolean readBean() throws SQLException {

    boolean result = hasNext();
    updateExecutionStatistics();
    return result;
  }

  protected EntityBean next() {
    if (auditFindIterate) {
      auditIterateNextBean();
    }
    hasNextCache = false;
    return nextBean;
  }

  protected boolean hasNext() throws SQLException {

    synchronized (this) {
      if (noMoreRows || cancelled || loadedBeanCount >= maxRowsLimit) {
        return false;
      }
      if (hasNextCache) {
        return true;
      }
      hasNextCache = readNextBean();
      return hasNextCache;
    }
  }

  /**
   * Read version beans and their effective dates.
   */
  @SuppressWarnings("unchecked")
  List<Version<T>> readVersions() throws SQLException {

    List<Version<T>> versionList = new ArrayList<>();

    Version<T> version;
    while ((version = readNextVersion()) != null) {
      versionList.add(version);
    }

    updateExecutionStatistics();
    return versionList;
  }

  private Version<T> readNextVersion() throws SQLException {

    if (moveToNextRow()) {
      return rootNode.loadVersion(this);
    }
    return null;
  }

  BeanCollection<T> readCollection() throws SQLException {

    while (hasNext()) {
      EntityBean bean = next();
      help.add(collection, bean, false);
    }

    updateExecutionStatistics();
    return collection;
  }

  void updateExecutionStatistics() {
    try {
      long exeNano = System.nanoTime() - startNano;
      executionTimeMicros = TimeUnit.NANOSECONDS.toMicros(exeNano);

      if (autoTuneProfiling) {
        profilingListener.collectQueryInfo(objectGraphNode, loadedBeanCount, executionTimeMicros);
      }
      queryPlan.executionTime(loadedBeanCount, executionTimeMicros, objectGraphNode);

    } catch (Exception e) {
      logger.error("Error updating execution statistics", e);
    }
  }

  QueryIterator<T> readIterate(int bufferSize, OrmQueryRequest<T> request) {

    if (bufferSize > 0) {
      return new CQueryIteratorWithBuffer<>(this, request, bufferSize);

    } else {
      return new CQueryIteratorSimple<>(this, request);
    }
  }

  String getLoadedRowDetail() {
    if (manyProperty == null) {
      return String.valueOf(rowCount);
    } else {
      return loadedBeanCount + ":" + rowCount;
    }
  }

  @Override
  public void register(String path, EntityBeanIntercept ebi) {

    path = getPath(path);
    request.getGraphContext().register(path, ebi);
  }

  @Override
  public void register(String path, BeanCollection<?> bc) {

    path = getPath(path);
    request.getGraphContext().register(path, bc);
  }

  /**
   * Return true if this is a raw sql query as opposed to Ebean generated sql.
   */
  @Override
  public boolean isRawSql() {
    return rawSql;
  }

  /**
   * Return the where predicate for display in the transaction log.
   */
  String getLogWhereSql() {
    return logWhereSql;
  }

  /**
   * Return the property that is associated with the many. There can only be one
   * per SqlSelect. This can be null.
   */
  @Override
  public BeanPropertyAssocMany<?> getManyProperty() {
    return manyProperty;
  }

  public String getBindLog() {
    return bindLog;
  }

  public SpiTransaction getTransaction() {
    return request.getTransaction();
  }

  /**
   * Return the short bean name.
   */
  String getBeanName() {
    return desc.getName();
  }

  /**
   * Return the generated sql.
   */
  public String getGeneratedSql() {
    return sql;
  }

  /**
   * Create a PersistenceException including interesting information like the bindLog and sql used.
   */
  PersistenceException createPersistenceException(SQLException e) {

    return createPersistenceException(e, getTransaction(), bindLog, sql);
  }

  /**
   * Create a PersistenceException including interesting information like the bindLog and sql used.
   */
  static PersistenceException createPersistenceException(SQLException e, SpiTransaction t, String bindLog, String sql) {

    if (t.isLogSummary()) {
      // log the error to the transaction log
      String errMsg = StringHelper.replaceStringMulti(e.getMessage(), new String[]{"\r", "\n"}, "\\n ");
      String msg = "ERROR executing query:   bindLog[" + bindLog + "] error[" + errMsg + "]";
      t.logSummary(msg);
    }

    // ensure 'rollback' is logged if queryOnly transaction
    t.getConnection();

    // build a decent error message for the exception
    String m = Message.msg("fetch.sqlerror", e.getMessage(), bindLog, sql);
    return new PersistenceException(m, e);
  }

  /**
   * Should we create profileNodes for beans created in this query.
   * <p>
   * This is true for all queries except lazy load bean queries.
   * </p>
   */
  @Override
  public boolean isAutoTuneProfiling() {
    // need query.isProfiling() because we just take the data
    // from the lazy loaded or refreshed beans and put it into the already
    // existing beans which are already collecting usage information
    return autoTuneProfiling && query.isUsageProfiling();
  }

  private String getPath(String propertyName) {

    if (currentPrefix == null) {
      return propertyName;
    } else if (propertyName == null) {
      return currentPrefix;
    }

    String path = currentPathMap.get(propertyName);
    if (path != null) {
      return path;
    } else {
      return currentPrefix + "." + propertyName;
    }
  }

  @Override
  public void profileBean(EntityBeanIntercept ebi, String prefix) {

    ObjectGraphNode node = request.getGraphContext().getObjectGraphNode(prefix);
    ebi.setNodeUsageCollector(new NodeUsageCollector(node, profilingListenerRef));
  }

  @Override
  public void setCurrentPrefix(String currentPrefix, Map<String, String> currentPathMap) {
    this.currentPrefix = currentPrefix;
    this.currentPathMap = currentPathMap;
  }

  /**
   * A find bean query with read auditing so build and log the ReadEvent.
   */
  void auditFind(EntityBean bean) {
    if (bean != null) {
      // only audit when a bean was actually found
      desc.readAuditBean(queryPlan.getAuditQueryKey(), bindLog, bean);
    }
  }

  /**
   * a find many query with read auditing so build the ReadEvent and log it.
   */
  void auditFindMany() {

    if (!collection.isEmpty()) {
      // get the id values of the underlying collection
      List<Object> ids = new ArrayList<>(collection.size());
      Collection<T> underlyingBeans = collection.getActualDetails();
      for (T underlyingBean : underlyingBeans) {
        ids.add(desc.getIdForJson(underlyingBean));
      }
      ReadEvent futureReadEvent = query.getFutureFetchAudit();
      if (futureReadEvent == null) {
        // normal query execution
        desc.readAuditMany(queryPlan.getAuditQueryKey(), bindLog, ids);
      } else {
        // this query was executed via findFutureList() and the prepare()
        // has already been called so set the details and log
        futureReadEvent.setQueryKey(queryPlan.getAuditQueryKey());
        futureReadEvent.setBindLog(bindLog);
        futureReadEvent.setIds(ids);
        desc.readAuditFutureMany(futureReadEvent);
      }
    }
  }

  /**
   * Indicate that read auditing is occurring on a this findIterate query.
   */
  void auditFindIterate() {
    auditFindIterate = true;
  }

  /**
   * Send the current buffer of findIterate collected ids to the audit log.
   */
  private void auditIterateLogMessage() {
    desc.readAuditMany(queryPlan.getAuditQueryKey(), bindLog, auditFindIterateIds);
    // create a new list on demand with the next bean/id
    auditFindIterateIds = null;
  }

  /**
   * Add the id to the audit id buffer and flush if needed in batches of 100.
   */
  private void auditIterateNextBean() {

    if (auditFindIterateIds == null) {
      auditFindIterateIds = new ArrayList<>(100);
    }
    auditFindIterateIds.add(desc.getIdForJson(nextBean));
    if (auditFindIterateIds.size() >= 100) {
      auditIterateLogMessage();
    }
  }

}