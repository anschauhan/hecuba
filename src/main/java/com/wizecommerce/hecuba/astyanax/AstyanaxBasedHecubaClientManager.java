/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.wizecommerce.hecuba.astyanax;

import com.google.common.base.Joiner;
import com.netflix.astyanax.*;
import com.netflix.astyanax.clock.ClockType;
import com.netflix.astyanax.connectionpool.NodeDiscoveryType;
import com.netflix.astyanax.connectionpool.OperationResult;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolConfigurationImpl;
import com.netflix.astyanax.connectionpool.impl.ConnectionPoolType;
import com.netflix.astyanax.connectionpool.impl.CountingConnectionPoolMonitor;
import com.netflix.astyanax.connectionpool.impl.SmaLatencyScoreStrategyImpl;
import com.netflix.astyanax.ddl.KeyspaceDefinition;
import com.netflix.astyanax.impl.AstyanaxConfigurationImpl;
import com.netflix.astyanax.model.*;
import com.netflix.astyanax.query.ColumnQuery;
import com.netflix.astyanax.query.PreparedIndexExpression;
import com.netflix.astyanax.query.RowQuery;
import com.netflix.astyanax.serializers.StringSerializer;
import com.netflix.astyanax.thrift.ThriftFamilyFactory;
import com.wizecommerce.hecuba.*;
import com.wizecommerce.hecuba.util.ConfigUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.exception.ExceptionUtils;

import java.nio.ByteBuffer;
import java.util.*;


/**
 * @author Eran Chinthaka Withana
 */
public class AstyanaxBasedHecubaClientManager<K> extends HecubaClientManager<K> {

	Keyspace keyspace;
	ColumnFamily<K, String> columnFamily;
	ColumnFamily<String, K> secondaryIndexColumnFamily;
	AstyanaxConfigurationImpl astyanaxConfigurationImpl;
	ConnectionPoolConfigurationImpl connectionPoolConfigurationImpl;
	CountingConnectionPoolMonitor connectionPoolMonitor;
	AstyanaxContext<Keyspace> context;
	AstyanaxContext<Cluster> clusterContext;
	Serializer<K> keySerializer;

	private Clock clock = ClockType.ASYNC_MICRO.get();

	public AstyanaxBasedHecubaClientManager() {

	}

	public AstyanaxBasedHecubaClientManager(String clusterName, String locationUrls, String port, String keyspaceName,
											String columnFamily, Serializer<K> keySerializer) {

		super(clusterName, locationUrls, port, keyspaceName, columnFamily);

		initialize(clusterName, locationUrls, port, keyspaceName);

		this.columnFamily = new ColumnFamily<K, String>(columnFamily, keySerializer, StringSerializer.get());
		this.keySerializer = keySerializer;
	}

	public AstyanaxBasedHecubaClientManager(CassandraParamsBean parameters, Serializer<K> keySerializer) {
		super(parameters);
		initialize(getClusterName(), getLocationURL(), getPort(), getKeyspace());
		this.columnFamily = new ColumnFamily<K, String>(getColumnFamilyName(), keySerializer, StringSerializer.get());
		this.keySerializer = keySerializer;

		if (columnsToIndexOnColumnNameAndValue != null && columnsToIndexOnColumnNameAndValue.size() > 0) {
			String secondaryIndexedColumnFamily = ConfigUtils.getInstance().getConfiguration().getString(
					HecubaConstants.GLOBAL_PROP_NAME_PREFIX + "." + columnFamily + ".secondaryIndexCF",
					columnFamily.getName() + HecubaConstants.SECONDARY_INDEX_CF_NAME_SUFFIX);
			this.secondaryIndexColumnFamily = new ColumnFamily<String, K>(secondaryIndexedColumnFamily,
																		  StringSerializer.get(), keySerializer);


		}
	}

	public void initialize(String clusterName, String locationUrls, String port, String keyspaceName) {
		astyanaxConfigurationImpl = new AstyanaxConfigurationImpl();
		final Configuration configuration = ConfigUtils.getInstance().getConfiguration();
		astyanaxConfigurationImpl.setDiscoveryType(NodeDiscoveryType.valueOf(configuration.getString(
				HecubaConstants.ASTYANAX_NODE_DISCOVERY_TYPE, "RING_DESCRIBE")));
		astyanaxConfigurationImpl.setConnectionPoolType(ConnectionPoolType.
																				  valueOf(configuration.getString(
																						  HecubaConstants.ASTYANAX_CONNECTION_POOL_TYPE,
																						  "TOKEN_AWARE")));

		connectionPoolConfigurationImpl = new ConnectionPoolConfigurationImpl("MyConnectionPool").setPort(
				Integer.parseInt(port)).setSeeds(getListOfNodesAndPorts(locationUrls, port)).setMaxConnsPerHost(
				configuration.getInteger(HecubaConstants.ASTYANAX_MAX_CONNS_PER_HOST, 3));

		// Will resort hosts per token partition every 10 seconds
		SmaLatencyScoreStrategyImpl smaLatencyScoreStrategyImpl = new SmaLatencyScoreStrategyImpl(
				configuration.getInteger(HecubaConstants.ASTYANAX_LATENCY_AWARE_UPDATE_INTERVAL, 10000),
				configuration.getInteger(HecubaConstants.ASTYANAX_LATENCY_AWARE_RESET_INTERVAL, 10000),
				configuration.getInteger(HecubaConstants.ASTYANAX_LATENCY_AWARE_WINDOW_SIZE, 100),
				configuration.getFloat(HecubaConstants.ASTYANAX_LATENCY_AWARE_BADNESS_INTERVAL, 0.5f));
		// Enabled SMA. Omit this to use round robin with a token range.
		connectionPoolConfigurationImpl.setLatencyScoreStrategy(smaLatencyScoreStrategyImpl);

		connectionPoolMonitor = new CountingConnectionPoolMonitor();
		context = new AstyanaxContext.Builder().forCluster(clusterName).forKeyspace(keyspaceName)
											   .withAstyanaxConfiguration(astyanaxConfigurationImpl)
											   .withConnectionPoolConfiguration(connectionPoolConfigurationImpl)
											   .withConnectionPoolMonitor(connectionPoolMonitor).buildKeyspace(
						ThriftFamilyFactory.getInstance());

		context.start();
		keyspace = context.getEntity();

		if (clusterContext == null) {
			initiateClusterContext(clusterName);
		}
	}

	@Override
	public void updateString(K key, String columnName, String value, long timestamp, int ttl) {
		MutationBatch m = keyspace.prepareMutationBatch();

		// set the timestamp, if set.
		if (timestamp > 0) {
			m.setTimestamp(timestamp);
		}

		updateSecondaryIndexes(key, columnName, value, ttl, m);
		m.withRow(columnFamily, key).putColumn(columnName, value, ttl > 0 ? ttl : null);

		try {
			m.execute();
		} catch (ConnectionException e) {
			log.error(ExceptionUtils.getStackTrace(e));
		}

	}

	private void updateSecondaryIndexes(K key, String columnName, String value, int ttl, MutationBatch mutationBatch) {
		// first check whether we have secondary indexes enabled for this CF and the given column has a secondary
		// index on it.
		if (isSecondaryIndexByColumnNameAndValueEnabledForColumn(columnName)) {
			// first retrieve the old value of this column to delete it from the secondary index.
			final String oldValue = readString(key, columnName);
			updateSecondaryIndexColumnFamily(key, columnName, value, ttl, mutationBatch, oldValue);
		}

		if (isSecondaryIndexByColumnNameEnabledForColumn(columnName)) {
			updateSecondaryIndexColumnFamily(key, columnName, "", ttl, mutationBatch, "");
		}

	}

	private void updateSecondaryIndexColumnFamily(K key, String columnName, String value, int ttl,
												  MutationBatch mutationBatch, String oldValue) {
		// enqueue a deletion to the secondary index to remove the previous value of this column.
		if (!StringUtils.isBlank(oldValue) && !"null".equalsIgnoreCase(oldValue)) {
			mutationBatch.withRow(secondaryIndexColumnFamily, getSecondaryIndexKey(columnName, oldValue)).deleteColumn(
					key);
		}


		// add the new value to the secondary index CF. Make sure to handle the TTLs properly.
		mutationBatch.withRow(secondaryIndexColumnFamily, getSecondaryIndexKey(columnName, value)).putColumn(key, key,
																											 keySerializer,
																											 ttl > 0 ?
																													 ttl :
																													 null);
	}

	@Override
	public void updateByteBuffer(K key, String columnName, ByteBuffer value) {
		try {
			MutationBatch m = keyspace.prepareMutationBatch();
			m.withRow(columnFamily, key).putColumn(columnName, value, null);
			m.execute();
		} catch (ConnectionException e) {
			log.warn("Couldn't update byte buffer ", e);
		}
	}

	@Override
	public void createKeyspace(String keyspace) {

		createKeyspaceAndColumnFamilies(keyspace, null);
	}

	public AstyanaxContext<Cluster> initiateClusterContext(String clusterName) {
		clusterContext = new AstyanaxContext.Builder().forCluster(clusterName).withAstyanaxConfiguration(
				new AstyanaxConfigurationImpl()).withConnectionPoolConfiguration(new ConnectionPoolConfigurationImpl(
				clusterName).setMaxConnsPerHost(1).setSeeds(getListOfNodesAndPorts(locationURLs, ports)).setPort(
				Integer.parseInt(ports))).withConnectionPoolMonitor(new CountingConnectionPoolMonitor()).buildCluster(
				ThriftFamilyFactory.getInstance());

		clusterContext.start();
		return clusterContext;
	}

	@Override
	public void dropKeyspace(String keyspace) {
		try {
			clusterContext.getEntity().dropKeyspace(keyspace);
		} catch (ConnectionException e) {
			log.warn("Couldn't drop the keyspace");
		}

	}

	@Override
	public void addColumnFamily(String keyspace, String columnFamilyName) {
		try {
			if (keyspace != null) {

				clusterContext.getEntity().addColumnFamily(
						clusterContext.getEntity().makeColumnFamilyDefinition().setName(columnFamilyName)
									  .setComparatorType("UTF8Type").setKeyValidationClass("UTF8Type"));
				Thread.sleep(2000);

			}
		} catch (ConnectionException e) {
			log.warn(String.format("Couldn't add column family %s under %s keyspace", columnFamilyName, keyspace), e);
		} catch (InterruptedException e) {
			log.warn(String.format("Couldn't add column family %s under %s keyspace", columnFamilyName, keyspace), e);
		}

	}

	@Override
	public void dropColumnFamily(String keyspace, String columnFamilyName) {
		try {
			if (keyspace != null && clusterContext != null) {
				clusterContext.getEntity().dropColumnFamily(keyspace, columnFamilyName);
			}
		} catch (ConnectionException e) {
			log.warn(String.format("Couldn't drop column family %s under %s keyspace", columnFamilyName, keyspace), e);
		}
	}

	@Override
	public void updateRow(K key, Map<String, Object> row, Map<String, Long> timestamps, Map<String, Integer> ttls) {
		// Inserting data
		MutationBatch m = keyspace.prepareMutationBatch();

		MutationBatch secondaryIndexMutation = keyspace.prepareMutationBatch();

		ColumnListMutation<String> columnListMutation = m.withRow(columnFamily, key);

		// check whether we have to set the timestamps.
		final boolean timestampsDefined = timestamps != null;

		for (String columnName : row.keySet()) {

			final Object value = row.get(columnName);

			// we will set the ttls if defined, if we pass that as null so that internally it will NOT set the ttls.
			final Integer ttl = ttls == null ? null : ttls.get(columnName);

			String valueToInsert = "null";

			if (value != null) {

				if (value instanceof Integer || value instanceof Long || value instanceof Double) {
					valueToInsert = value.toString();
				} else if (value instanceof Date) {
					valueToInsert = HecubaConstants.DATE_FORMATTER.print(((Date) value).getTime());
				} else if (value instanceof Boolean) {
					valueToInsert = ((Boolean) value) ? "true" : "false";
				} else if (value instanceof String) {
					valueToInsert = (String) value;
				} else {
					// TODO:Eran
					// not sure what to do here. There has to be a serializer to
					// send this value.
					valueToInsert = value.toString();
				}
			}

			// if timestamps are set, pass that on to the columnListMutation. The tricky thing here is once you set
			// a timestamp that will be used from that point beyond. So if a timestamp is NOT defined at least for one
			// column name then we need to set that timestamp to proper system timestamp.
			if (timestampsDefined) {
				columnListMutation.setTimestamp(timestamps.get(columnName) == null || timestamps.get(columnName) < 1 ?
														clock.getCurrentTime() : timestamps.get(columnName));
			}
			columnListMutation.putColumn(columnName, valueToInsert, ttl);

			updateSecondaryIndexes(key, columnName, valueToInsert, ttl == null ? -1 : ttl, secondaryIndexMutation);

		}

		try {

			// first update secondary indexes.
			if (!secondaryIndexMutation.isEmpty()) {
				final OperationResult<Void> secondaryIndexMutationExecResult = secondaryIndexMutation.execute();
				log.debug("Row Inserted into Cassandra secondary indexes. Exec Time = " +
								  secondaryIndexMutationExecResult.getLatency() + ", " +
								  "Host used = " + secondaryIndexMutationExecResult.getHost().getHostName());
			}

			OperationResult<Void> result = m.execute();
			log.debug("Row Inserted into Cassandra. Exec Time = " + result.getLatency() + ", Host used = " +
							  result.getHost().getHostName());
		} catch (ConnectionException e) {
			log.error(ExceptionUtils.getFullStackTrace(e));
		}

	}

	@Override
	public String readString(K key, String columnName) {
		Column<String> columnByName = readColumn(key, columnName);
		return columnByName == null ? null : columnByName.getStringValue();
	}

	private Column<String> readColumn(K key, String columnName) {
		try {
			OperationResult<ColumnList<String>> result = keyspace.prepareQuery(columnFamily).getKey(key).execute();
			return result.getResult().getColumnByName(columnName);
		} catch (ConnectionException e) {
			if (log.isDebugEnabled()) {
				log.debug("HecubaClientManager error while reading key " + key.toString() + ". " +
								  ExceptionUtils.getStackTrace(e));
			}
		}
		return null;
	}

	@Override
	public CassandraColumn readColumnInfo(K key, String columnName) {
		Column<String> columnByName = readColumn(key, columnName);
		return columnByName == null ? null : new CassandraColumn(columnByName.getName(), columnByName.getStringValue(),
																 columnByName.getTimestamp(), columnByName.getTtl());
	}

	public CassandraResultSet<K, String> readAllColumns(K key) throws ConnectionException {
		try {

			OperationResult<ColumnList<String>> result = keyspace.prepareQuery(columnFamily).getKey(key).execute();
			ColumnList<String> columns = result.getResult();
			if (isClientAdapterDebugMessagesEnabled) {
				log.info("Row retrieved from Cassandra. Exec Time (micro-sec) = " + result.getLatency() / 1000 +
								 ", Host used = " + result.getHost() + ", Key = " + key);
			}
			return new AstyanaxResultSet<K, String>(columns);

		} catch (ConnectionException e) {
			if (log.isDebugEnabled()) {
				log.debug("HecubaClientManager error while reading key " + key.toString());
			}
			throw e;
		}
	}

	@Override
	public CassandraResultSet<K, String> readColumnSlice(K key, String start, String end, boolean reversed, int count) {
		try {
			final OperationResult<ColumnList<String>> executeResult = keyspace.prepareQuery(columnFamily).getKey(key)
																			  .withColumnRange(start, end, reversed,
																							   count).execute();
			if (executeResult != null) {
				return new AstyanaxResultSet<K, String>(executeResult.getResult());
			}
		} catch (ConnectionException e) {
			log.warn("Couldn't execute column slice query ", e);
		}

		return null;
	}

	@Override
	public CassandraResultSet readAllColumnsBySecondaryIndex(Map<String, String> parameters, int limit) {

		List<PreparedIndexExpression<K, String>> clauses = new ArrayList<PreparedIndexExpression<K, String>>();
		for (Map.Entry<String, String> entry : parameters.entrySet()) {
			PreparedIndexExpression<K, String> clause = columnFamily.newIndexClause().whereColumn(entry.getKey())
																	.equals().value(entry.getValue());
			clauses.add(clause);
		}
		OperationResult<Rows<K, String>> result;

		try {
			result = keyspace.prepareQuery(columnFamily).searchWithIndex()
					//							.setStartKey(0L)
					.addPreparedExpressions(clauses).execute();
			Rows<K, String> rowItems = result.getResult();
			if (rowItems.size() > 0) {
				Row<K, String> row = rowItems.getRowByIndex(0);
				AstyanaxResultSet<K, String> resultSet = new AstyanaxResultSet<K, String>(row.getColumns());
				return resultSet;
			}

		} catch (ConnectionException e) {
			log.warn("HecubaClientManager error while reading secondary index key " + parameters.toString());
			if (log.isDebugEnabled()) {
				log.debug("Caught Exception", e);
			}
		}
		return null;
	}

	@Override
	public Long getCounterValue(K key, String counterColumnName) {
		try {
			RowQuery<K, String> row = keyspace.prepareQuery(columnFamily).getKey(key);
			if (row != null) {
				ColumnQuery<String> column = row.getColumn(counterColumnName);
				if (column != null) {
					OperationResult<Column<String>> executeResult = column.execute();
					if (executeResult != null) {
						Column<String> result = executeResult.getResult();
						return result != null ? result.getLongValue() : 0L;
					} else {
						return 0L;
					}
				} else {
					return 0L;
				}
			} else {
				return 0L;
			}

		} catch (ConnectionException e) {
			if (log.isDebugEnabled()) {
				log.debug("HecubaClientManager error while reading key " + key.toString() + ". " +
								  ExceptionUtils.getStackTrace(e));
				log.debug("Caught Exception", e);
			}
		}

		return 0L;
	}

	@Override
	public void updateCounter(K key, String counterColumnName, long value) {

		try {
			keyspace.prepareColumnMutation(columnFamily, key, counterColumnName).incrementCounterColumn(value)
					.execute();

		} catch (ConnectionException e) {
			log.error(ExceptionUtils.getStackTrace(e));
		}
	}

	@Override
	public void incrementCounter(K key, String counterColumnName) {
		this.updateCounter(key, counterColumnName, 1);
	}

	@Override
	public void decrementCounter(K key, String counterColumnName) {
		this.updateCounter(key, counterColumnName, -1);
	}

	@Override
	public void deleteColumn(K key, String columnName) {
		MutationBatch m = keyspace.prepareMutationBatch();

		// first check whether this is a column we have a seconday index on.
		if (isSecondaryIndexByColumnNameAndValueEnabledForColumn(columnName)) {
			final Column<String> oldColumn = readColumn(key, columnName);
			if (oldColumn != null) {
				m.withRow(secondaryIndexColumnFamily, getSecondaryIndexKey(columnName, oldColumn.getStringValue()))
				 .deleteColumn(key);
			}
		}

		if (isSecondaryIndexByColumnNameEnabledForColumn(columnName)) {
			m.withRow(secondaryIndexColumnFamily, getSecondaryIndexKey(columnName, "")).deleteColumn(key);
		}

		// delete the column from the main CF
		m.withRow(columnFamily, key).deleteColumn(columnName);

		try {
			m.execute();
		} catch (ConnectionException e) {
			log.error(ExceptionUtils.getStackTrace(e));
		}

	}

	@Override
	public void deleteRow(K key) {
		deleteRow(key, -1);
	}

	@Override
	public void deleteRow(K key, long timestamp) {
		try {
			MutationBatch m = keyspace.prepareMutationBatch();
			timestamp = timestamp > 0 ? timestamp : keyspace.getConfig().getClock().getCurrentTime();

			// first delete all the secondary indexes.
			if (isSecondaryIndexByColumnNameAndValueEnabled || isSecondaryIndexesByColumnNamesEnabled) {
				// first read the old values of columns.
				final CassandraResultSet<K, String> oldValues = readColumns(key, columnsToIndexOnColumnNameAndValue);

				if (isSecondaryIndexByColumnNameAndValueEnabled) {
					for (String columnName : columnsToIndexOnColumnNameAndValue) {
						m.withRow(secondaryIndexColumnFamily, getSecondaryIndexKey(columnName, oldValues.getString(
								columnName))).setTimestamp(timestamp).deleteColumn(key);
					}
				}

				if (isSecondaryIndexesByColumnNamesEnabled) {
					for (String columnName : oldValues.getColumnNames()) {
						if (columnName.matches(secondaryIdxByColumnPattern)) {
							m.withRow(secondaryIndexColumnFamily, getSecondaryIndexKey(columnName, "")).setTimestamp(
									timestamp).deleteColumn(key);
						}
					}
				}
			}

			// now delete the main row.
			m.withRow(columnFamily, key).setTimestamp(timestamp).delete();
			m.execute();
		} catch (ConnectionException e) {
			log.error(ExceptionUtils.getStackTrace(e));
		} catch (Exception e) {
			log.error(ExceptionUtils.getStackTrace(e));
		}

	}

	@Override
	public CassandraResultSet<K, String> retrieveBySecondaryIndex(String columnName, String columnValue) {

		if (isSecondaryIndexByColumnNameAndValueEnabledForColumn(columnName)) {
			return retrieveFromSecondaryIndex(columnName, columnValue);
		}

		return null;
	}

	@Override
	public CassandraResultSet<K, String> retrieveBySecondaryIndex(String columnName, List<String> columnValues) {
		if (isSecondaryIndexByColumnNameAndValueEnabledForColumn(columnName)) {
			return retrieveFromSecondaryIndex(columnName, columnValues);
		}

		return null;
	}

	private CassandraResultSet<K, String> retrieveFromSecondaryIndex(String columnName, String columnValue) {
		try {
			String secondaryIndexKey = getSecondaryIndexKey(columnName, columnValue);
			final OperationResult<ColumnList<K>> result = keyspace.prepareQuery(secondaryIndexColumnFamily).getKey(
					secondaryIndexKey).execute();
			if (isClientAdapterDebugMessagesEnabled) {
				log.debug("Secondary Index Row for " + secondaryIndexKey + " retrieved from Cassandra. Exec Time =" +
								  " " +
								  result.getLatency() + ", Host used = " + result.getHost().getHostName());
			}

			if (result != null && result.getResult().size() > 0) {
				final Iterator<Column<K>> iterator = result.getResult().iterator();
				Set<K> rowKeys = new HashSet<K>();
				while (iterator.hasNext()) {
					rowKeys.add(iterator.next().getName());
				}
				return readAllColumns(rowKeys);
			}

		} catch (Exception e) {
			e.printStackTrace();
			log.warn("Error executing retrieveBySecondaryIndex ", e);
		}

		return null;
	}

	private List<String> getSecondaryIndexKeys(String columnName, List<String> columnValues) {
		List<String> secondaryIndexKeys = null;
		if (columnValues != null && columnValues.size() > 0) {
			secondaryIndexKeys = new ArrayList<>();
			for (String columnValue : columnValues) {
				secondaryIndexKeys.add(getSecondaryIndexKey(columnName, columnValue));
			}
		}

		return secondaryIndexKeys;
	}

	private CassandraResultSet<K, String> retrieveFromSecondaryIndex(String columnName, List<String> columnValues) {
		try {
			List<String> secondaryIndexKeys = getSecondaryIndexKeys(columnName, columnValues);
			OperationResult<Rows<String, K>> result = keyspace.prepareQuery(secondaryIndexColumnFamily).getKeySlice(
					secondaryIndexKeys).execute();
			if (isClientAdapterDebugMessagesEnabled) {
				log.debug("Secondary Index Row for " + Joiner.on(",").join(secondaryIndexKeys) +
								  " retrieved from Cassandra. Exec" +
								  " " +
								  "Time =" +
								  " " +
								  result.getLatency() + ", Host used = " + result.getHost().getHostName());
			}

			if (result != null && result.getResult().size() > 0) {
				Iterator<Row<String, K>> iterator = result.getResult().iterator();
				Set<K> rowKeys = new HashSet<K>();
				while (iterator.hasNext()) {
					Iterator<Column<K>> columnIterator = iterator.next().getColumns().iterator();
					while (columnIterator.hasNext()) {
						rowKeys.add(columnIterator.next().getName());
					}
				}
				return readAllColumns(rowKeys);
			}

		} catch (Exception e) {
			e.printStackTrace();
			log.warn("Error executing retrieveBySecondaryIndex ", e);
		}

		return null;
	}

	@Override
	public CassandraResultSet<K, String> retrieveByColumnNameBasedSecondaryIndex(String columnName) {
		if (isSecondaryIndexByColumnNameEnabledForColumn(columnName)) {
			return retrieveFromSecondaryIndex(columnName, "");
		}

		return null;
	}

	@Override
	public void createKeyspaceAndColumnFamilies(String keyspace, List<ColumnFamilyInfo> columnFamilies) {
		AstyanaxContext<Cluster> clusterContext = initiateClusterContext(clusterName);
		try {
			Cluster cluster = clusterContext.getEntity();

			Map<String, String> stratOptions = new HashMap<String, String>();
			stratOptions.put("replication_factor", "1");

			KeyspaceDefinition ksDef = cluster.makeKeyspaceDefinition();

			ksDef.setName(keyspace).setStrategyOptions(stratOptions).setStrategyClass("SimpleStrategy");

			if (columnFamilies != null) {
				for (ColumnFamilyInfo columnFamilyInfo : columnFamilies) {
					ksDef.addColumnFamily(cluster.makeColumnFamilyDefinition().setName(columnFamilyInfo.getName())
												 .setComparatorType(columnFamilyInfo.getComparatorType())
												 .setKeyValidationClass(columnFamilyInfo.getKeyValidationClass())
												 .setDefaultValidationClass(
														 columnFamilyInfo.getDefaultValidationClass()));
				}
			}

			cluster.addKeyspace(ksDef);

			Thread.sleep(2000);

			initialize(clusterName, locationURLs, ports, keyspace);

		} catch (ConnectionException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} finally {
			clusterContext.shutdown();
		}

	}

	@Override
	public CassandraResultSet<K, String> readColumns(K key, List<String> columnName) throws Exception {
		final OperationResult<ColumnList<String>> operationResult = keyspace.prepareQuery(columnFamily).getKey(key)
																			.withColumnSlice(columnName).execute();
		if (operationResult != null) {
			return new AstyanaxResultSet<K, String>(operationResult.getResult());
		}

		return null;
	}


	@Override
	public CassandraResultSet<K, String> readColumns(Set<K> keys, List<String> columnNames) throws Exception {
		if (CollectionUtils.isNotEmpty(columnNames)) {
			final OperationResult<Rows<K, String>> result = keyspace.prepareQuery(columnFamily).getKeySlice(keys)
																	.withColumnSlice(columnNames).execute();
			if (isClientAdapterDebugMessagesEnabled) {
				log.info(columnNames.size() + " columns retrieved from Cassandra [Astyanax] for " + keys.size() +
								 " keys . Exec Time (micro-sec) = " + (result.getLatency() / 1000) + ", Host used = " +
								 result.getHost());
			}
			return new AstyanaxResultSet<K, String>(result);
		} else {
			return readAllColumns(keys);
		}
	}

	@Override
	public CassandraResultSet<K, String> readAllColumns(Set<K> keys) throws Exception {
		// This method was added as part of multi-get feature for cache calls.
		try {

			OperationResult<Rows<K, String>> result = keyspace.prepareQuery(columnFamily).getKeySlice(keys).execute();
			if (isClientAdapterDebugMessagesEnabled) {
				log.info("Rows retrieved from Cassandra [Astyanax] (for " + keys.size() + " keys). Exec Time " +
								 "(micro-sec) = " + result.getLatency() / 1000 + ", Host used = " + result.getHost());
			}

			return new AstyanaxResultSet<K, String>(result);

		} catch (ConnectionException e) {
			log.warn("HecubaClientManager [Astyanax] error while reading multiple keys. Number of keys = " +
							 keys.size() + ", keys = " + keys.toString());
			if (log.isDebugEnabled()) {
				log.debug("Caught Exception while reading for multiple keys", e);
			}
			throw e;
		}
	}

	@Override
	public CassandraResultSet<K, String> readColumnSlice(Set<K> keys, String start, String end, boolean reversed,
														 int count) {
		try {
			final OperationResult<Rows<K, String>> rowSliceQueryResult = keyspace.prepareQuery(columnFamily)
																				 .getKeySlice(keys).withColumnRange(
							start, end, reversed, count).execute();
			return new AstyanaxResultSet<K, String>(rowSliceQueryResult);
		} catch (ConnectionException e) {
			log.warn("error while executing a row slice query ", e);
		}

		return null;
	}

	@Override
	public void deleteColumns(K key, List<String> columnNameList) {
		for (String columnName : columnNameList) {
			deleteColumn(key, columnName);
		}

	}

}
