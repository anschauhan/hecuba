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

/**
 *
 */
package com.wizecommerce.hecuba;

import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import com.google.common.base.Joiner;

/**
 * @author - Eran Chinthaka Withana
 * @version - Sep 12, 2011
 */
public abstract class HecubaConstants {

	private static final Joiner dotJoiner = Joiner.on(".");

	public static final String GLOBAL_PROP_NAME_PREFIX = "com.wizecommerce.hecuba";

	public static final String HECUBA_CASSANDRA_CLIENT_IMPLEMENTATION_MANAGER = getPropertyName("cassandraclientmanager");

	public static enum CassandraClientImplementation {
		HECTOR, ASTYANAX, DATASTAX, DATASTAX_SHARED
	}

	public static DateTimeFormatter DATE_FORMATTER = DateTimeFormat.forPattern("E, dd MMM yyyy HH:mm:ss Z");

	/*****************************
	 * Configuration Properties.
	 *****************************/

	public static final String SECONDARY_INDEX_CF_NAME_SUFFIX = "_Secondary_Idx";

	public static final String AUTHENTICATION_USER = getPropertyName("username");
	public static final String AUTHENTICATION_PASSWORD = getPropertyName("password");
	public static final String ENABLE_DEBUG_MESSAGES = getPropertyName("hectorpools.enabledebugmessages");

	/******************************
	 * Astynax Specific Options
	 ******************************/
	public static final String ASTYANAX_NODE_DISCOVERY_TYPE = getPropertyName("client.astyanax.nodeDiscoveryType");
	public static final String ASTYANAX_CONNECTION_POOL_TYPE = getPropertyName("client.astyanax.connectionPoolType");
	public static final String ASTYANAX_MAX_CONNS_PER_HOST = getPropertyName("client.astyanax.maxConnsPerHost");
	public static final String ASTYANAX_LATENCY_AWARE_UPDATE_INTERVAL = getPropertyName("client.astyanax.latencyAwareUpdateInterval");
	public static final String ASTYANAX_LATENCY_AWARE_RESET_INTERVAL = getPropertyName("client.astyanax.latencyAwareResetInterval");
	public static final String ASTYANAX_LATENCY_AWARE_BADNESS_INTERVAL = getPropertyName("client.astyanax.latencyAwareBadnessInterval");
	public static final String ASTYANAX_LATENCY_AWARE_WINDOW_SIZE = getPropertyName("client.astyanax.latencyAwareWindowSize");

	/**************************
	 * Hector Specific Options
	 **************************/
	public static final String HECTOR_LOAD_BALANCING_POLICY = getPropertyName("hectorpools.loadbalancingpolicy");
	public static final String HECTOR_MAX_ACTIVE_POOLS = getPropertyName("hectorpools.maxactive");
	public static final String HECTOR_MAX_IDLE = getPropertyName("hectorpools.maxidle");
	public static final String HECTOR_RETRY_DOWN_HOST = getPropertyName("hectorpools.retrydownedhosts");
	public static final String HECTOR_RETRY_DOWN_HOST_DELAY = getPropertyName("hectorpools.retrydownedhostsinseconds");
	public static final String HECTOR_THRIFT_SOCKET_TIMEOUT = getPropertyName("hectorpools.thriftsockettimeout");
	public static final String HECTOR_USE_THRIFT_FRAME_TRANSPORT = getPropertyName("hectorpools.usethriftframedtransport");

	/***************************
	 * DataStax Specific Options
	 *****************************/
	public static final String DATASTAX_CONNECT_TIMEOUT = getPropertyName("datastax.socket.ConnectTimeout");
	public static final String DATASTAX_READ_TIMEOUT = getPropertyName("datastax.socket.ReadTimeout");
	public static final String DATASTAX_MAX_CONNECTIONS_PER_HOST = getPropertyName("datastax.pool.MaxConnectionsPerHost");
	public static final String DATASTAX_COMPRESSION_ENABLED = getPropertyName("datastax.CompressionEnabled");
	public static final String DATASTAX_TRACING_ENABLED = getPropertyName("datastax.TracingEnabled");
	public static final String DATASTAX_DATACENTER = getPropertyName("datastax.Datacenter");
	public static final String DATASTAX_STATEMENT_CACHE_MAX_SIZE = getPropertyName("datastax.statement.CacheMaxSize");
	public static final String DATASTAX_STATEMENT_FETCH_SIZE = getPropertyName("datastax.statement.FetchSize");

	public static enum HECTOR_LOAD_BALANCY_POLICIES {
		LeastActiveBalancingPolicy, DynamicLoadBalancingPolicy, RoundRobinBalancingPolicy,
	}

	private static String getPropertyName(String postfix) {
		return dotJoiner.join(GLOBAL_PROP_NAME_PREFIX, postfix);
	}

	public static String[] getConsistencyPolicyProperties(String columnFamily, String operation) {
		return new String[] { getPropertyName(dotJoiner.join("consistencypolicy", operation)), getPropertyName(dotJoiner.join(columnFamily, "consistencypolicy", operation)) };
	}

	public static String getSecondaryIndexColumnFamilyProperty(String columnFamily) {
		return getPropertyName(dotJoiner.join(columnFamily, "secondaryIndexCF"));
	}
}
