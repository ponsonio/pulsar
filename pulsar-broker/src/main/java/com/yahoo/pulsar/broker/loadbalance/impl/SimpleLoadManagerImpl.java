/**
 * Copyright 2016 Yahoo Inc.
 *
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
package com.yahoo.pulsar.broker.loadbalance.impl;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.bookkeeper.util.ZkUtils;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.ZooDefs.Ids;
import org.apache.zookeeper.data.Stat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.yahoo.pulsar.broker.PulsarServerException;
import com.yahoo.pulsar.broker.PulsarService;
import com.yahoo.pulsar.broker.ServiceConfiguration;
import com.yahoo.pulsar.broker.admin.AdminResource;
import com.yahoo.pulsar.broker.loadbalance.BrokerHostUsage;
import com.yahoo.pulsar.broker.loadbalance.LoadManager;
import com.yahoo.pulsar.broker.loadbalance.PlacementStrategy;
import com.yahoo.pulsar.broker.loadbalance.ResourceUnit;
import com.yahoo.pulsar.broker.stats.Metrics;
import com.yahoo.pulsar.client.admin.PulsarAdmin;
import com.yahoo.pulsar.common.naming.NamespaceName;
import com.yahoo.pulsar.common.naming.ServiceUnitId;
import com.yahoo.pulsar.common.policies.data.ResourceQuota;
import com.yahoo.pulsar.common.policies.data.loadbalancer.LoadReport;
import com.yahoo.pulsar.common.policies.data.loadbalancer.NamespaceBundleStats;
import com.yahoo.pulsar.common.policies.data.loadbalancer.ResourceUnitRanking;
import com.yahoo.pulsar.common.policies.data.loadbalancer.SystemResourceUsage;
import com.yahoo.pulsar.common.policies.data.loadbalancer.SystemResourceUsage.ResourceType;
import com.yahoo.pulsar.common.util.ObjectMapperFactory;
import com.yahoo.pulsar.zookeeper.ZooKeeperCacheListener;
import com.yahoo.pulsar.zookeeper.ZooKeeperChildrenCache;
import com.yahoo.pulsar.zookeeper.ZooKeeperDataCache;

public class SimpleLoadManagerImpl implements LoadManager, ZooKeeperCacheListener<LoadReport> {

    private static final Logger log = LoggerFactory.getLogger(SimpleLoadManagerImpl.class);
    private final SimpleResourceAllocationPolicies policies;
    private PulsarService pulsar;

    // average JVM heap usage for
    private long avgJvmHeapUsageMBytes = 0;
    // load report got from each broker
    private Map<ResourceUnit, LoadReport> currentLoadReports;
    // load ranking for each broker from multiple perspective
    private Map<ResourceUnit, ResourceUnitRanking> resourceUnitRankings;
    // sorted load ranking on one single dimension
    private AtomicReference<Map<Long, Set<ResourceUnit>>> sortedRankings = new AtomicReference<>();
    // rotation cursor between brokers
    private long brokerRotationCursor = 0;
    // load balancing metrics
    private AtomicReference<List<Metrics>> loadBalancingMetrics = new AtomicReference<>();

    // CPU usage per msg/sec
    private double realtimeCpuLoadFactor = 0.025;
    // memory usage per 500 (topics + producers + consumers)
    private double realtimeMemoryLoadFactor = 25.0;
    // realtime average resource quota
    private ResourceQuota realtimeAvgResourceQuota = null;
    // realtime resource quota calculated from the latest load reports
    private AtomicReference<Map<String, ResourceQuota>> realtimeResourceQuotas = new AtomicReference<>();
    // timestamp when the resource quotas were re-calculated
    private long lastResourceQuotaUpdateTimestamp = -1;

    public static final long RESOURCE_QUOTA_GO_UP_TIMEWINDOW = TimeUnit.MINUTES.toMillis(30);
    public static final long RESOURCE_QUOTA_GO_DOWN_TIMEWINDOW = TimeUnit.MINUTES.toMillis(1440);
    private static final double RESOURCE_QUOTA_MIN_CPU_FACTOR = 0.01;
    private static final double RESOURCE_QUOTA_MAX_CPU_FACTOR = 0.1;
    private static final double RESOURCE_QUOTA_MIN_MEM_FACTOR = 10;
    private static final double RESOURCE_QUOTA_MAX_MEM_FACTOR = 50;
    private static final long RESOURCE_QUOTA_MIN_MSGRATE_IN = 5;
    private static final long RESOURCE_QUOTA_MIN_MSGRATE_OUT = 5;
    private static final long RESOURCE_QUOTA_MIN_BANDWIDTH_IN = 10000;
    private static final long RESOURCE_QUOTA_MIN_BANDWIDTH_OUT = 10000;
    private static final long RESOURCE_QUOTA_MIN_MEMORY = 2;
    private static final long RESOURCE_QUOTA_MAX_MSGRATE_IN = 5000;
    private static final long RESOURCE_QUOTA_MAX_MSGRATE_OUT = 5000;
    private static final long RESOURCE_QUOTA_MAX_BANDWIDTH_IN = 1000000;
    private static final long RESOURCE_QUOTA_MAX_BANDWIDTH_OUT = 1000000;
    private static final long RESOURCE_QUOTA_MAX_MEMORY = 200;

    private final PlacementStrategy placementStrategy;

    private final ZooKeeperDataCache<LoadReport> loadReportCacheZk;
    private final ZooKeeperDataCache<Map<String, String>> dynamicConfigurationCache;
    private final BrokerHostUsage brokerHostUsage;
    private final LoadingCache<String, PulsarAdmin> adminCache;
    private final LoadingCache<String, Long> unloadedHotNamespaceCache;

    public static final String LOADBALANCE_BROKERS_ROOT = "/loadbalance/brokers";
    public static final String LOADBALANCER_DYNAMIC_SETTING_STRATEGY_ZPATH = "/loadbalance/settings/strategy";
    private static final String LOADBALANCER_DYNAMIC_SETTING_LOAD_FACTOR_CPU_ZPATH = "/loadbalance/settings/load_factor_cpu";
    private static final String LOADBALANCER_DYNAMIC_SETTING_LOAD_FACTOR_MEM_ZPATH = "/loadbalance/settings/load_factor_mem";
    private static final String LOADBALANCER_DYNAMIC_SETTING_OVERLOAD_THRESHOLD_ZPATH = "/loadbalance/settings/overload_threshold";
    private static final String LOADBALANCER_DYNAMIC_SETTING_COMFORT_LOAD_THRESHOLD_ZPATH = "/loadbalance/settings/comfort_load_threshold";
    private static final String LOADBALANCER_DYNAMIC_SETTING_UNDERLOAD_THRESHOLD_ZPATH = "/loadbalance/settings/underload_threshold";
    private static final String LOADBALANCER_DYNAMIC_SETTING_AUTO_BUNDLE_SPLIT_ENABLED = "/loadbalance/settings/auto_bundle_split_enabled";
    private static final String SETTING_NAME_LOAD_FACTOR_CPU = "loadFactorCPU";
    private static final String SETTING_NAME_LOAD_FACTOR_MEM = "loadFactorMemory";
    private static final String SETTING_NAME_STRATEGY = "loadBalancerStrategy";
    private static final String SETTING_NAME_OVERLOAD_THRESHOLD = "overloadThreshold";
    private static final String SETTING_NAME_UNDERLOAD_THRESHOLD = "underloadThreshold";
    private static final String SETTING_NAME_COMFORTLOAD_THRESHOLD = "comfortLoadThreshold";
    private static final String SETTING_NAME_AUTO_BUNDLE_SPLIT_ENABLED = "autoBundleSplitEnabled";

    public static final String LOADBALANCER_STRATEGY_LLS = "leastLoadedServer";
    public static final String LOADBALANCER_STRATEGY_RAND = "weightedRandomSelection";

    private String brokerZnodePath;
    private final ScheduledExecutorService scheduler;
    private final ZooKeeperChildrenCache availableActiveBrokers;

    private static final long MBytes = 1024 * 1024;
    // update LoadReport at most every 5 seconds
    public static final long LOAD_REPORT_UPDATE_MIMIMUM_INTERVAL = TimeUnit.SECONDS.toMillis(5);
    // last LoadReport stored in ZK
    private LoadReport lastLoadReport;
    // last timestamp resource usage was checked
    private long lastResourceUsageTimestamp = -1;
    // flag to force update load report
    private boolean forceLoadReportUpdate = false;

    public SimpleLoadManagerImpl(PulsarService pulsar) {
        this.policies = new SimpleResourceAllocationPolicies(pulsar);
        this.sortedRankings.set(new TreeMap<>());
        this.currentLoadReports = new HashMap<>();
        this.resourceUnitRankings = new HashMap<>();
        this.loadBalancingMetrics.set(Lists.newArrayList());
        this.realtimeResourceQuotas.set(new HashMap<>());
        this.realtimeAvgResourceQuota = new ResourceQuota();
        placementStrategy = new WRRPlacementStrategy();
        lastLoadReport = new LoadReport(pulsar.getWebServiceAddress(), pulsar.getWebServiceAddressTls(),
                pulsar.getBrokerServiceUrl(), pulsar.getBrokerServiceUrlTls());
        brokerHostUsage = new BrokerHostUsage(pulsar);
        loadReportCacheZk = new ZooKeeperDataCache<LoadReport>(pulsar.getLocalZkCache()) {
            @Override
            public LoadReport deserialize(String key, byte[] content) throws Exception {
                return ObjectMapperFactory.getThreadLocal().readValue(content, LoadReport.class);
            }
        };
        loadReportCacheZk.registerListener(this);
        this.dynamicConfigurationCache = new ZooKeeperDataCache<Map<String, String>>(pulsar.getLocalZkCache()) {
            @Override
            public Map<String, String> deserialize(String key, byte[] content) throws Exception {
                return ObjectMapperFactory.getThreadLocal().readValue(content, HashMap.class);
            }
        };
        adminCache = CacheBuilder.newBuilder().removalListener(new RemovalListener<String, PulsarAdmin>() {
            public void onRemoval(RemovalNotification<String, PulsarAdmin> removal) {
                removal.getValue().close();
            }
        }).expireAfterAccess(1, TimeUnit.DAYS).build(new CacheLoader<String, PulsarAdmin>() {
            @Override
            public PulsarAdmin load(String key) throws Exception {
                // key - broker name already is valid URL, has prefix "http://"
                return new PulsarAdmin(new URL(key), pulsar.getConfiguration().getBrokerClientAuthenticationPlugin(),
                        pulsar.getConfiguration().getBrokerClientAuthenticationParameters());
            }
        });
        int entryExpiryTime = (int) pulsar.getConfiguration().getLoadBalancerSheddingGracePeriodMinutes();
        unloadedHotNamespaceCache = CacheBuilder.newBuilder().expireAfterWrite(entryExpiryTime, TimeUnit.MINUTES)
                .build(new CacheLoader<String, Long>() {
                    @Override
                    public Long load(String key) throws Exception {
                        return System.currentTimeMillis();
                    }
                });
        availableActiveBrokers = new ZooKeeperChildrenCache(pulsar.getLocalZkCache(), LOADBALANCE_BROKERS_ROOT);
        availableActiveBrokers.registerListener(new ZooKeeperCacheListener<Set<String>>() {
            @Override
            public void onUpdate(String path, Set<String> data, Stat stat) {
                if (log.isDebugEnabled()) {
                    log.debug("Update Received for path {}", path);
                }
                scheduler.submit(SimpleLoadManagerImpl.this::updateRanking);
            }
        });
        scheduler = Executors.newScheduledThreadPool(1);
        this.pulsar = pulsar;
    }

    @Override
    public void start() throws PulsarServerException {
        try {
            // Register the brokers in zk list
            ServiceConfiguration conf = pulsar.getConfiguration();
            if (pulsar.getZkClient().exists(LOADBALANCE_BROKERS_ROOT, false) == null) {
                try {
                    ZkUtils.createFullPathOptimistic(pulsar.getZkClient(), LOADBALANCE_BROKERS_ROOT, new byte[0],
                            Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                } catch (KeeperException.NodeExistsException e) {
                    // ignore the exception, node might be present already
                }
            }

            String lookupServiceAddress = pulsar.getAdvertisedAddress() + ":" + conf.getWebServicePort();
            brokerZnodePath = LOADBALANCE_BROKERS_ROOT + "/" + lookupServiceAddress;
            LoadReport loadReport = null;
            try {
                loadReport = generateLoadReport();
                this.lastResourceUsageTimestamp = loadReport.getTimestamp();
            } catch (Exception e) {
                log.warn("Unable to get load report to write it on zookeeper [{}]", e);
            }
            String loadReportJson = "";
            if (loadReport != null) {
                loadReportJson = ObjectMapperFactory.getThreadLocal().writeValueAsString(loadReport);
            }
            try {
                ZkUtils.createFullPathOptimistic(pulsar.getZkClient(), brokerZnodePath,
                        loadReportJson.getBytes(Charsets.UTF_8), Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
            } catch (Exception e) {
                // Catching excption here to print the right error message
                log.error("Unable to create znode - [{}] for load balance on zookeeper ", brokerZnodePath, e);
                throw e;
            }
            // first time, populate the broker ranking
            updateRanking();
            log.info("Created broker ephemeral node on {}", brokerZnodePath);

            // load default resource quota
            this.realtimeAvgResourceQuota = pulsar.getLocalZkCacheService().getResourceQuotaCache().getDefaultQuota();
            this.lastResourceQuotaUpdateTimestamp = System.currentTimeMillis();
            this.realtimeCpuLoadFactor = getDynamicConfigurationDouble(
                    LOADBALANCER_DYNAMIC_SETTING_LOAD_FACTOR_CPU_ZPATH, SETTING_NAME_LOAD_FACTOR_CPU,
                    this.realtimeCpuLoadFactor);
            this.realtimeMemoryLoadFactor = getDynamicConfigurationDouble(
                    LOADBALANCER_DYNAMIC_SETTING_LOAD_FACTOR_MEM_ZPATH, SETTING_NAME_LOAD_FACTOR_MEM,
                    this.realtimeMemoryLoadFactor);
        } catch (Exception e) {
            log.error("Unable to create znode - [{}] for load balance on zookeeper ", brokerZnodePath, e);
            throw new PulsarServerException(e);
        }
    }

    @Override
    public void disableBroker() throws Exception {
        if (isNotEmpty(brokerZnodePath)) {
            pulsar.getZkClient().delete(brokerZnodePath, -1);
        }
    }

    public ZooKeeperChildrenCache getActiveBrokersCache() {
        return this.availableActiveBrokers;
    }

    public ZooKeeperDataCache<LoadReport> getLoadReportCache() {
        return this.loadReportCacheZk;
    }

    private void setDynamicConfigurationToZK(String zkPath, Map<String, String> settings) throws IOException {
        byte[] settingBytes = ObjectMapperFactory.getThreadLocal().writeValueAsBytes(settings);
        try {
            if (pulsar.getLocalZkCache().exists(zkPath)) {
                pulsar.getZkClient().setData(zkPath, settingBytes, -1);
            } else {
                ZkUtils.createFullPathOptimistic(pulsar.getZkClient(), zkPath, settingBytes, Ids.OPEN_ACL_UNSAFE,
                        CreateMode.PERSISTENT);
            }
        } catch (Exception e) {
            log.warn("Got exception when writing to ZooKeeper path [{}]:", zkPath, e);
        }

    }

    private String getDynamicConfigurationFromZK(String zkPath, String settingName, String defaultValue) {
        try {
            return dynamicConfigurationCache.get(zkPath).map(c -> c.get(settingName)).orElse(defaultValue);
        } catch (Exception e) {
            log.warn("Got exception when reading ZooKeeper path [{}]:", zkPath, e);
            return defaultValue;
        }
    }

    private double getDynamicConfigurationDouble(String zkPath, String settingName, double defaultValue) {
        double result = defaultValue;
        try {
            String setting = this.getDynamicConfigurationFromZK(zkPath, settingName, null);
            if (setting != null) {
                result = Double.parseDouble(setting);
            }
        } catch (Exception e) {
            log.warn("Got exception when parsing configuration from ZooKeeper path [{}]:", zkPath, e);
        }
        return result;
    }

    private boolean getDynamicConfigurationBoolean(String zkPath, String settingName, boolean defaultValue) {
        boolean result = defaultValue;
        try {
            String setting = this.getDynamicConfigurationFromZK(zkPath, settingName, null);
            if (setting != null) {
                result = Boolean.parseBoolean(setting);
            }
        } catch (Exception e) {
            log.warn("Got exception when parsing configuration from ZooKeeper path [{}]:", zkPath, e);
        }
        return result;
    }

    private String getLoadBalancerPlacementStrategy() {
        String strategy = this.getDynamicConfigurationFromZK(LOADBALANCER_DYNAMIC_SETTING_STRATEGY_ZPATH,
                SETTING_NAME_STRATEGY, pulsar.getConfiguration().getLoadBalancerPlacementStrategy());
        if (!LOADBALANCER_STRATEGY_LLS.equals(strategy) && !LOADBALANCER_STRATEGY_RAND.equals(strategy)) {
            strategy = LOADBALANCER_STRATEGY_RAND;
        }
        return strategy;
    }

    private double getCpuLoadFactorFromZK(double defaultValue) {
        return getDynamicConfigurationDouble(LOADBALANCER_DYNAMIC_SETTING_LOAD_FACTOR_CPU_ZPATH,
                SETTING_NAME_LOAD_FACTOR_CPU, defaultValue);
    }

    private double getMemoryLoadFactorFromZK(double defaultValue) {
        return getDynamicConfigurationDouble(LOADBALANCER_DYNAMIC_SETTING_LOAD_FACTOR_MEM_ZPATH,
                SETTING_NAME_LOAD_FACTOR_MEM, defaultValue);
    }

    @Override
    public boolean isCentralized() {
        String strategy = this.getLoadBalancerPlacementStrategy();
        return (strategy.equals(LOADBALANCER_STRATEGY_LLS));
    }

    private long getLoadBalancerBrokerUnderloadedThresholdPercentage() {
        return (long) this.getDynamicConfigurationDouble(LOADBALANCER_DYNAMIC_SETTING_UNDERLOAD_THRESHOLD_ZPATH,
                SETTING_NAME_UNDERLOAD_THRESHOLD,
                pulsar.getConfiguration().getLoadBalancerBrokerUnderloadedThresholdPercentage());
    }

    private long getLoadBalancerBrokerOverloadedThresholdPercentage() {
        return (long) this.getDynamicConfigurationDouble(LOADBALANCER_DYNAMIC_SETTING_OVERLOAD_THRESHOLD_ZPATH,
                SETTING_NAME_OVERLOAD_THRESHOLD,
                pulsar.getConfiguration().getLoadBalancerBrokerOverloadedThresholdPercentage());
    }

    private long getLoadBalancerBrokerComfortLoadThresholdPercentage() {
        return (long) this.getDynamicConfigurationDouble(LOADBALANCER_DYNAMIC_SETTING_COMFORT_LOAD_THRESHOLD_ZPATH,
                SETTING_NAME_COMFORTLOAD_THRESHOLD,
                pulsar.getConfiguration().getLoadBalancerBrokerComfortLoadLevelPercentage());
    }

    private boolean getLoadBalancerAutoBundleSplitEnabled() {
        return this.getDynamicConfigurationBoolean(LOADBALANCER_DYNAMIC_SETTING_AUTO_BUNDLE_SPLIT_ENABLED,
                SETTING_NAME_AUTO_BUNDLE_SPLIT_ENABLED,
                pulsar.getConfiguration().getLoadBalancerAutoBundleSplitEnabled());
    }

    /*
     * temp method, remove it in future, in-place to make this glue code to make load balancing work
     */
    private PulsarResourceDescription fromLoadReport(LoadReport report) {
        SystemResourceUsage sru = report.getSystemResourceUsage();
        PulsarResourceDescription resourceDescription = new PulsarResourceDescription();
        if (sru.bandwidthIn != null)
            resourceDescription.put("bandwidthIn", sru.bandwidthIn);
        if (sru.bandwidthOut != null)
            resourceDescription.put("bandwidthOut", sru.bandwidthOut);
        if (sru.memory != null)
            resourceDescription.put("memory", sru.memory);
        if (sru.cpu != null)
            resourceDescription.put("cpu", sru.cpu);
        return resourceDescription;
    }

    private ResourceQuota getResourceQuota(String bundle) {
        Map<String, ResourceQuota> quotas = this.realtimeResourceQuotas.get();
        if (!quotas.containsKey(bundle)) {
            ResourceQuota quota = pulsar.getLocalZkCacheService().getResourceQuotaCache().getQuota(bundle);
            quotas.put(bundle, quota);
            return quota;
        } else {
            return quotas.get(bundle);
        }
    }

    /**
     * Get the sum of allocated resource for the list of namespace bundles
     */
    private ResourceQuota getTotalAllocatedQuota(Set<String> bundles) {
        ResourceQuota totalQuota = new ResourceQuota();
        for (String bundle : bundles) {
            ResourceQuota quota = this.getResourceQuota(bundle);
            totalQuota.add(quota);
        }
        return totalQuota;
    }

    private double timeSmoothValue(double oldValue, double newSample, double minValue, double maxValue, long timePast) {
        newSample = Math.max(minValue, newSample);
        if (maxValue > 0) {
            newSample = Math.min(maxValue, newSample);
        }

        double weight = 0.0;
        if (newSample >= oldValue) {
            weight = Math.min(1, Math.max(0, (double) timePast / RESOURCE_QUOTA_GO_UP_TIMEWINDOW));
        } else if (newSample < oldValue) {
            weight = Math.min(1, Math.max(0, (double) timePast / RESOURCE_QUOTA_GO_DOWN_TIMEWINDOW));
        }

        double result = (1 - weight) * oldValue + weight * newSample;
        return result;
    }

    private ResourceQuota timeSmoothQuota(ResourceQuota oldQuota, double msgRateIn, double msgRateOut,
            double bandwidthIn, double bandwidthOut, double memory, long timePast) {
        if (oldQuota.getDynamic()) {
            ResourceQuota newQuota = new ResourceQuota();
            newQuota.setMsgRateIn(timeSmoothValue(oldQuota.getMsgRateIn(), msgRateIn, RESOURCE_QUOTA_MIN_MSGRATE_IN,
                    RESOURCE_QUOTA_MAX_MSGRATE_IN, timePast));
            newQuota.setMsgRateOut(timeSmoothValue(oldQuota.getMsgRateOut(), msgRateOut, RESOURCE_QUOTA_MIN_MSGRATE_OUT,
                    RESOURCE_QUOTA_MAX_MSGRATE_OUT, timePast));
            newQuota.setBandwidthIn(timeSmoothValue(oldQuota.getBandwidthIn(), bandwidthIn,
                    RESOURCE_QUOTA_MIN_BANDWIDTH_IN, RESOURCE_QUOTA_MAX_BANDWIDTH_IN, timePast));
            newQuota.setBandwidthOut(timeSmoothValue(oldQuota.getBandwidthOut(), bandwidthOut,
                    RESOURCE_QUOTA_MIN_BANDWIDTH_OUT, RESOURCE_QUOTA_MAX_BANDWIDTH_OUT, timePast));
            newQuota.setMemory(timeSmoothValue(oldQuota.getMemory(), memory, RESOURCE_QUOTA_MIN_MEMORY,
                    RESOURCE_QUOTA_MAX_MEMORY, timePast));
            return newQuota;
        } else {
            return oldQuota;
        }
    }

    private synchronized void updateRealtimeResourceQuota() {
        long memObjectGroupSize = 500;
        if (!currentLoadReports.isEmpty()) {
            long totalBundles = 0;
            long totalMemGroups = 0;
            double totalMsgRateIn = 0.0;
            double totalMsgRateOut = 0.0;
            double totalMsgRate = 0.0;
            double totalCpuUsage = 0.0;
            double totalMemoryUsage = 0.0;
            double totalBandwidthIn = 0.0;
            double totalBandwidthOut = 0.0;
            long loadReportTimestamp = -1;

            // update resource factors
            for (Map.Entry<ResourceUnit, LoadReport> entry : currentLoadReports.entrySet()) {
                LoadReport loadReport = entry.getValue();
                if (loadReport.getTimestamp() > loadReportTimestamp) {
                    loadReportTimestamp = loadReport.getTimestamp();
                }

                Map<String, NamespaceBundleStats> bundleStats = loadReport.getBundleStats();
                if (bundleStats == null) {
                    continue;
                }

                for (Map.Entry<String, NamespaceBundleStats> statsEntry : bundleStats.entrySet()) {
                    totalBundles++;
                    NamespaceBundleStats stats = statsEntry.getValue();
                    totalMemGroups += (1
                            + (stats.topics + stats.producerCount + stats.consumerCount) / memObjectGroupSize);
                    totalBandwidthIn += stats.msgThroughputIn;
                    totalBandwidthOut += stats.msgThroughputOut;
                }

                SystemResourceUsage resUsage = loadReport.getSystemResourceUsage();
                totalMsgRateIn += loadReport.getMsgRateIn();
                totalMsgRateOut += loadReport.getMsgRateOut();
                totalCpuUsage = totalCpuUsage + resUsage.getCpu().usage;
                totalMemoryUsage = totalMemoryUsage + resUsage.getMemory().usage;
            }

            totalMsgRate = totalMsgRateIn + totalMsgRateOut;
            long timePast = loadReportTimestamp - this.lastResourceQuotaUpdateTimestamp;
            this.lastResourceQuotaUpdateTimestamp = loadReportTimestamp;
            if (totalMsgRate > 1000 && totalMemGroups > 30) {
                this.realtimeCpuLoadFactor = timeSmoothValue(this.realtimeCpuLoadFactor, totalCpuUsage / totalMsgRate,
                        RESOURCE_QUOTA_MIN_CPU_FACTOR, RESOURCE_QUOTA_MAX_CPU_FACTOR, timePast);
                this.realtimeMemoryLoadFactor = timeSmoothValue(this.realtimeMemoryLoadFactor,
                        totalMemoryUsage / totalMemGroups, RESOURCE_QUOTA_MIN_MEM_FACTOR, RESOURCE_QUOTA_MAX_MEM_FACTOR,
                        timePast);
            }

            // calculate average bundle
            if (totalBundles > 30 && this.realtimeAvgResourceQuota.getDynamic()) {
                ResourceQuota oldQuota = this.realtimeAvgResourceQuota;
                ResourceQuota newQuota = timeSmoothQuota(oldQuota, totalMsgRateIn / totalBundles,
                        totalMsgRateOut / totalBundles, totalBandwidthIn / totalBundles,
                        totalBandwidthOut / totalBundles, totalMemoryUsage / totalBundles, timePast);
                this.realtimeAvgResourceQuota = newQuota;
            }

            // update realtime quota for each bundle
            Map<String, ResourceQuota> newQuotas = new HashMap<>();
            for (Map.Entry<ResourceUnit, LoadReport> entry : currentLoadReports.entrySet()) {
                ResourceUnit resourceUnit = entry.getKey();
                LoadReport loadReport = entry.getValue();
                Map<String, NamespaceBundleStats> bundleStats = loadReport.getBundleStats();
                if (bundleStats == null) {
                    continue;
                }

                for (Map.Entry<String, NamespaceBundleStats> statsEntry : bundleStats.entrySet()) {
                    String bundle = statsEntry.getKey();
                    NamespaceBundleStats stats = statsEntry.getValue();
                    long memGroupCount = (1
                            + (stats.topics + stats.producerCount + stats.consumerCount) / memObjectGroupSize);
                    double newMemoryQuota = memGroupCount * this.realtimeMemoryLoadFactor;

                    ResourceQuota oldQuota = getResourceQuota(bundle);
                    ResourceQuota newQuota = timeSmoothQuota(oldQuota, stats.msgRateIn, stats.msgRateOut,
                            stats.msgThroughputIn, stats.msgThroughputOut, newMemoryQuota, timePast);
                    newQuotas.put(bundle, newQuota);
                }
            }
            this.realtimeResourceQuotas.set(newQuotas);
        }
    }

    private void compareAndWriteQuota(String bundle, ResourceQuota oldQuota, ResourceQuota newQuota) throws Exception {
        boolean needUpdate = true;
        if (!oldQuota.getDynamic() || (Math
                .abs(newQuota.getMsgRateIn() - oldQuota.getMsgRateIn()) < RESOURCE_QUOTA_MIN_MSGRATE_IN
                && Math.abs(newQuota.getMsgRateOut() - oldQuota.getMsgRateOut()) < RESOURCE_QUOTA_MIN_MSGRATE_OUT
                && Math.abs(newQuota.getBandwidthIn() - oldQuota.getBandwidthOut()) < RESOURCE_QUOTA_MIN_BANDWIDTH_IN
                && Math.abs(newQuota.getBandwidthOut() - oldQuota.getBandwidthOut()) < RESOURCE_QUOTA_MIN_BANDWIDTH_OUT
                && Math.abs(newQuota.getMemory() - oldQuota.getMemory()) < RESOURCE_QUOTA_MIN_MEMORY)) {
            needUpdate = false;
        }

        if (needUpdate) {
            if (log.isDebugEnabled()) {
                log.debug(String.format(
                        "Update quota %s - msgRateIn: %.1f, msgRateOut: %.1f, bandwidthIn: %.1f, bandwidthOut: %.1f, memory: %.1f",
                        (bundle == null) ? "default" : bundle, newQuota.getMsgRateIn(), newQuota.getMsgRateOut(),
                        newQuota.getBandwidthIn(), newQuota.getBandwidthOut(), newQuota.getMemory()));
            }

            if (bundle == null) {
                pulsar.getLocalZkCacheService().getResourceQuotaCache().setDefaultQuota(newQuota);
            } else {
                pulsar.getLocalZkCacheService().getResourceQuotaCache().setQuota(bundle, newQuota);
            }
        }
    }

    @Override
    public void writeResourceQuotasToZooKeeper() throws Exception {
        log.info("Writing namespace bundle resource quotas to ZooKeeper as leader broker");

        // write the load factors
        setDynamicConfigurationToZK(LOADBALANCER_DYNAMIC_SETTING_LOAD_FACTOR_CPU_ZPATH, new HashMap<String, String>() {
            {
                put(SETTING_NAME_LOAD_FACTOR_CPU, Double.toString(realtimeCpuLoadFactor));
            }
        });
        setDynamicConfigurationToZK(LOADBALANCER_DYNAMIC_SETTING_LOAD_FACTOR_MEM_ZPATH, new HashMap<String, String>() {
            {
                put(SETTING_NAME_LOAD_FACTOR_MEM, Double.toString(realtimeMemoryLoadFactor));
            }
        });

        // write default quota
        ResourceQuota defaultQuota = pulsar.getLocalZkCacheService().getResourceQuotaCache().getDefaultQuota();
        this.compareAndWriteQuota(null, defaultQuota, this.realtimeAvgResourceQuota);

        // write each bundle's quota
        Map<String, ResourceQuota> quotas = this.realtimeResourceQuotas.get();
        for (Map.Entry<String, ResourceQuota> entry : quotas.entrySet()) {
            String bundle = entry.getKey();
            ResourceQuota oldQuota = pulsar.getLocalZkCacheService().getResourceQuotaCache().getQuota(bundle);
            this.compareAndWriteQuota(bundle, oldQuota, entry.getValue());
        }
    }

    /**
     * Rank brokers by available capacity, or load percentage, based on placement strategy:
     *
     * - Available capacity for weighted random selection (weightedRandomSelection): ranks ResourceUnits units based on
     * estimation of their capacity which is basically how many bundles each ResourceUnit is able can handle with its
     * available resources (CPU, memory, network, etc);
     *
     * - Load percentage for least loaded server (leastLoadedServer): ranks ResourceUnits units based on estimation of
     * their load percentage which is basically how many percent of resource is allocated which is
     * max(resource_actually_used, resource_quota)
     *
     * If we fail to collect the Load Reports OR fail to process them for the first time, it means the leader does not
     * have enough information to make a decision so we set it to ready when we collect and process the load reports
     * successfully the first time.
     */
    private synchronized void doLoadRanking() {
        ResourceUnitRanking.setCpuUsageByMsgRate(this.realtimeCpuLoadFactor);
        String hostname = pulsar.getAdvertisedAddress();
        String strategy = this.getLoadBalancerPlacementStrategy();
        log.info("doLoadRanking - load balancing strategy: {}", strategy);
        if (!currentLoadReports.isEmpty()) {
            synchronized (resourceUnitRankings) {
                Map<Long, Set<ResourceUnit>> newSortedRankings = Maps.newTreeMap();
                Map<ResourceUnit, ResourceUnitRanking> newResourceUnitRankings = new HashMap<>();

                for (Map.Entry<ResourceUnit, LoadReport> entry : currentLoadReports.entrySet()) {
                    ResourceUnit resourceUnit = entry.getKey();
                    LoadReport loadReport = entry.getValue();

                    // calculate rankings
                    Set<String> loadedBundles = loadReport.getBundles();
                    Set<String> preAllocatedBundles = null;
                    if (resourceUnitRankings.containsKey(resourceUnit)) {
                        preAllocatedBundles = resourceUnitRankings.get(resourceUnit).getPreAllocatedBundles();
                        preAllocatedBundles.removeAll(loadedBundles);
                    } else {
                        preAllocatedBundles = new HashSet<>();
                    }
                    ResourceQuota allocatedQuota = getTotalAllocatedQuota(loadedBundles);
                    ResourceQuota preAllocatedQuota = getTotalAllocatedQuota(preAllocatedBundles);
                    ResourceUnitRanking ranking = new ResourceUnitRanking(loadReport.getSystemResourceUsage(),
                            loadedBundles, allocatedQuota, preAllocatedBundles, preAllocatedQuota);
                    newResourceUnitRankings.put(resourceUnit, ranking);

                    // generated sorted ranking
                    double loadPercentage = ranking.getEstimatedLoadPercentage();
                    long maxCapacity = ranking.estimateMaxCapacity(
                            pulsar.getLocalZkCacheService().getResourceQuotaCache().getDefaultQuota());
                    long finalRank = 0;
                    if (strategy.equals(LOADBALANCER_STRATEGY_LLS)) {
                        finalRank = (long) loadPercentage;
                    } else {
                        double idleRatio = (100 - loadPercentage) / 100;
                        finalRank = (long) (maxCapacity * idleRatio * idleRatio);
                    }

                    if (!newSortedRankings.containsKey(finalRank)) {
                        newSortedRankings.put(finalRank, new HashSet<ResourceUnit>());
                    }
                    newSortedRankings.get(finalRank).add(entry.getKey());
                    if (log.isDebugEnabled()) {
                        log.debug("Added Resource Unit [{}] with Rank [{}]", entry.getKey().getResourceId(), finalRank);
                    }

                    // update metrics
                    if (resourceUnit.getResourceId().contains(hostname)) {
                        updateLoadBalancingMetrics(hostname, finalRank, ranking);
                    }
                }
                this.sortedRankings.set(newSortedRankings);
                this.resourceUnitRankings = newResourceUnitRankings;
            }
        } else {
            log.info("Leader broker[{}] No ResourceUnits to rank this run, Using Old Ranking",
                    pulsar.getWebServiceAddress());
        }
    }

    public List<Metrics> getLoadBalancingMetrics() {
        List<Metrics> metrics = this.loadBalancingMetrics.get();
        return metrics;
    }

    private void updateLoadBalancingMetrics(String hostname, long finalRank, ResourceUnitRanking ranking) {
        List<Metrics> metrics = Lists.newArrayList();
        Map<String, String> dimensions = new HashMap<>();

        dimensions.put("broker", hostname);
        Metrics m = Metrics.create(dimensions);
        m.put("brk_lb_load_rank", finalRank);
        m.put("brk_lb_quota_pct_cpu", ranking.getAllocatedLoadPercentageCPU());
        m.put("brk_lb_quota_pct_memory", ranking.getAllocatedLoadPercentageMemory());
        m.put("brk_lb_quota_pct_bandwidth_in", ranking.getAllocatedLoadPercentageBandwidthIn());
        m.put("brk_lb_quota_pct_bandwidth_out", ranking.getAllocatedLoadPercentageBandwidthOut());
        metrics.add(m);
        this.loadBalancingMetrics.set(metrics);
    }

    /**
     * Assign owner for specified ServiceUnit from the given candidates, following the the principles: 1) Optimum
     * distribution: fill up one broker till its load reaches optimum level (defined by underload threshold) before pull
     * another idle broker in; 2) Even distribution: once all brokers' load are above optimum level, maintain all
     * brokers to have even load; 3) Set the underload threshold to small value (like 1) for pure even distribution, and
     * high value (like 80) for pure optimum distribution;
     *
     * Strategy to select broker: 1) The first choice is the least loaded broker which is underload but not idle; 2) The
     * second choice is idle broker (if there is any); 3) Othewise simply select the least loaded broker if it is NOT
     * overloaded; 4) If all brokers are overloaded, select the broker with maximum available capacity (considering
     * brokers could have different hardware configuration, this usually means to select the broker with more hardware
     * resource);
     *
     * Broker's load level: 1) Load ranking (triggered by LoadReport update) estimate the load level according to the
     * resourse usage and namespace bundles already loaded by each broker; 2) When leader broker decide the owner for a
     * new namespace bundle, it may take time for the real owner to actually load the bundle and refresh LoadReport,
     * leader broker will store the bundle in a list called preAllocatedBundles, and the quota of all
     * preAllocatedBundles in preAllocatedQuotas, and re-estimate the broker's load level by putting the
     * preAllocatedQuota into calculation; 3) Everything (preAllocatedBundles and preAllocatedQuotas) will get reset in
     * load ranking.
     */
    private ResourceUnit findBrokerForPlacement(Multimap<Long, ResourceUnit> candidates, ServiceUnitId serviceUnit) {
        long underloadThreshold = this.getLoadBalancerBrokerUnderloadedThresholdPercentage();
        long overloadThreshold = this.getLoadBalancerBrokerOverloadedThresholdPercentage();
        ResourceQuota defaultQuota = pulsar.getLocalZkCacheService().getResourceQuotaCache().getDefaultQuota();

        double minLoadPercentage = 101.0;
        long maxAvailability = -1;
        ResourceUnit idleRU = null;
        ResourceUnit maxAvailableRU = null;
        ResourceUnit randomRU = null;

        ResourceUnit selectedRU = null;
        ResourceUnitRanking selectedRanking = null;
        String serviceUnitId = serviceUnit.toString();
        synchronized (resourceUnitRankings) {
            long randomBrokerIndex = (candidates.size() > 0) ? (this.brokerRotationCursor % candidates.size()) : 0;
            // find the least loaded & not-idle broker
            for (Map.Entry<Long, ResourceUnit> candidateOwner : candidates.entries()) {
                ResourceUnit candidate = candidateOwner.getValue();
                randomBrokerIndex--;

                // skip broker which is not ranked. this should never happen except in unit test
                if (!resourceUnitRankings.containsKey(candidate)) {
                    continue;
                }

                // check if this ServiceUnit is already pre-allocated
                String resourceUnitId = candidate.getResourceId();
                ResourceUnitRanking ranking = resourceUnitRankings.get(candidate);
                if (ranking.isServiceUnitPreAllocated(serviceUnitId)) {
                    return candidate;
                }

                // check if this ServiceUnit is already loaded
                if (ranking.isServiceUnitLoaded(serviceUnitId)) {
                    ranking.removeLoadedServiceUnit(serviceUnitId, this.getResourceQuota(serviceUnitId));
                }

                // record a random broker
                if (randomBrokerIndex < 0 && randomRU == null) {
                    randomRU = candidate;
                }

                // check the available capacity
                double loadPercentage = ranking.getEstimatedLoadPercentage();
                double availablePercentage = Math.max(0, (100 - loadPercentage) / 100);
                long availability = (long) (ranking.estimateMaxCapacity(defaultQuota) * availablePercentage);
                if (availability > maxAvailability) {
                    maxAvailability = availability;
                    maxAvailableRU = candidate;
                }

                // check the load percentage
                if (ranking.isIdle()) {
                    if (idleRU == null) {
                        idleRU = candidate;
                    }
                } else {
                    if (selectedRU == null) {
                        selectedRU = candidate;
                        selectedRanking = ranking;
                        minLoadPercentage = loadPercentage;
                    } else {
                        if (ranking.compareTo(selectedRanking) < 0) {
                            minLoadPercentage = loadPercentage;
                            selectedRU = candidate;
                            selectedRanking = ranking;
                        }
                    }
                }
            }

            if ((minLoadPercentage > underloadThreshold && idleRU != null) || selectedRU == null) {
                // assigned to idle broker is the least loaded broker already have optimum load (which means NOT
                // underloaded), or all brokers are idle
                selectedRU = idleRU;
            } else if (minLoadPercentage >= 100.0 && randomRU != null) {
                // all brokers are full, assign to a random one
                selectedRU = randomRU;
            } else if (minLoadPercentage > overloadThreshold) {
                // assign to the broker with maximum available capacity if all brokers are overloaded
                selectedRU = maxAvailableRU;
            }

            // re-calculate load level for selected broker
            if (selectedRU != null) {
                this.brokerRotationCursor = (this.brokerRotationCursor + 1) % 1000000;
                ResourceUnitRanking ranking = resourceUnitRankings.get(selectedRU);
                String loadPercentageDesc = ranking.getEstimatedLoadPercentageString();
                log.info("Assign {} to {} with ({}).", serviceUnitId, selectedRU.getResourceId(), loadPercentageDesc);
                if (!ranking.isServiceUnitPreAllocated(serviceUnitId)) {
                    ResourceQuota quota = this.getResourceQuota(serviceUnitId);
                    ranking.addPreAllocatedServiceUnit(serviceUnitId, quota);
                }
            }
        }
        return selectedRU;
    }

    private Multimap<Long, ResourceUnit> getFinalCandidatesWithPolicy(NamespaceName namespace,
            Multimap<Long, ResourceUnit> primaries, Multimap<Long, ResourceUnit> shared) {
        Multimap<Long, ResourceUnit> finalCandidates = TreeMultimap.create();
        // if not enough primary then it should be union of primaries and secondaries
        finalCandidates.putAll(primaries);
        if (policies.shouldFailoverToSecondaries(namespace, primaries.size())) {
            log.debug(
                    "Not enough of primaries [{}] available for namespace - [{}], "
                            + "adding shared [{}] as possible candidate owners",
                    primaries.size(), namespace.toString(), shared.size());
            finalCandidates.putAll(shared);
        }
        return finalCandidates;
    }

    private Multimap<Long, ResourceUnit> getFinalCandidatesNoPolicy(Multimap<Long, ResourceUnit> shared) {
        Multimap<Long, ResourceUnit> finalCandidates = TreeMultimap.create();

        finalCandidates.putAll(shared);
        return finalCandidates;
    }

    private Multimap<Long, ResourceUnit> getFinalCandidates(ServiceUnitId serviceUnit,
            Map<Long, Set<ResourceUnit>> availableBrokers) {
        // need multimap or at least set of RUs
        Multimap<Long, ResourceUnit> matchedPrimaries = TreeMultimap.create();
        Multimap<Long, ResourceUnit> matchedShared = TreeMultimap.create();

        NamespaceName namespace = serviceUnit.getNamespaceObject();
        boolean isIsolationPoliciesPresent = policies.IsIsolationPoliciesPresent(namespace);
        if (isIsolationPoliciesPresent) {
            log.debug("Isolation Policies Present for namespace - [{}]", namespace.toString());
        }
        for (Map.Entry<Long, Set<ResourceUnit>> entry : availableBrokers.entrySet()) {
            for (ResourceUnit ru : entry.getValue()) {
                log.debug("Considering Resource Unit [{}] with Rank [{}] for serviceUnit [{}]", ru.getResourceId(),
                        entry.getKey(), serviceUnit);
                URL brokerUrl = null;
                try {
                    brokerUrl = new URL(String.format(ru.getResourceId()));
                } catch (MalformedURLException e) {
                    log.error("Unable to parse brokerUrl from ResourceUnitId - [{}]", e);
                    continue;
                }
                // todo: in future check if the resource unit has resources to take the namespace
                if (isIsolationPoliciesPresent) {
                    // note: serviceUnitID is namespace name and ResourceID is brokerName
                    if (policies.isPrimaryBroker(namespace, brokerUrl.getHost())) {
                        matchedPrimaries.put(entry.getKey(), ru);
                        if (log.isDebugEnabled()) {
                            log.debug(
                                    "Added Primary Broker - [{}] as possible Candidates for"
                                            + " namespace - [{}] with policies",
                                    brokerUrl.getHost(), namespace.toString());
                        }
                    } else if (policies.isSharedBroker(brokerUrl.getHost())) {
                        matchedShared.put(entry.getKey(), ru);
                        if (log.isDebugEnabled()) {
                            log.debug(
                                    "Added Shared Broker - [{}] as possible "
                                            + "Candidates for namespace - [{}] with policies",
                                    brokerUrl.getHost(), namespace.toString());
                        }
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("Skipping Broker - [{}] not primary broker and not shared"
                                    + " for namespace - [{}] ", brokerUrl.getHost(), namespace.toString());
                        }

                    }
                } else {
                    if (policies.isSharedBroker(brokerUrl.getHost())) {
                        matchedShared.put(entry.getKey(), ru);
                        log.debug("Added Shared Broker - [{}] as possible Candidates for namespace - [{}]",
                                brokerUrl.getHost(), namespace.toString());
                    }
                }
            }
        }
        if (isIsolationPoliciesPresent) {
            return getFinalCandidatesWithPolicy(namespace, matchedPrimaries, matchedShared);
        } else {
            log.debug(
                    "Policies not present for namespace - [{}] so only "
                            + "considering shared [{}] brokers for possible owner",
                    namespace.toString(), matchedShared.size());
            return getFinalCandidatesNoPolicy(matchedShared);
        }
    }

    public ResourceUnit getLeastLoaded(ServiceUnitId serviceUnit) throws Exception {
        return getLeastLoadedBroker(serviceUnit, getAvailableBrokers(serviceUnit));
    }

    public Multimap<Long, ResourceUnit> getResourceAvailabilityFor(ServiceUnitId serviceUnitId) throws Exception {
        return getFinalCandidates(serviceUnitId, getAvailableBrokers(serviceUnitId));
    }

    private Map<Long, Set<ResourceUnit>> getAvailableBrokers(ServiceUnitId serviceUnitId) throws Exception {
        Map<Long, Set<ResourceUnit>> availableBrokers = sortedRankings.get();

        // Normal case: we are the leader and we do have load reports information available

        if (availableBrokers.isEmpty()) {
            // Create a map with all available brokers with no load information
            Set<String> activeBrokers = availableActiveBrokers.get(LOADBALANCE_BROKERS_ROOT);
            List<String> brokersToShuffle = new ArrayList<>(activeBrokers);
            Collections.shuffle(brokersToShuffle);
            activeBrokers = new HashSet<>(brokersToShuffle);

            availableBrokers = Maps.newTreeMap();
            for (String broker : activeBrokers) {
                ResourceUnit resourceUnit = new SimpleResourceUnit(String.format("http://%s", broker),
                        new PulsarResourceDescription());
                availableBrokers.computeIfAbsent(0L, key -> Sets.newTreeSet()).add(resourceUnit);
            }
            log.info("Choosing at random from broker list: [{}]", availableBrokers.values());
        }
        return availableBrokers;
    }

    private ResourceUnit getLeastLoadedBroker(ServiceUnitId serviceUnit,
            Map<Long, Set<ResourceUnit>> availableBrokers) {
        ResourceUnit selectedBroker = null;
        Multimap<Long, ResourceUnit> finalCandidates = getFinalCandidates(serviceUnit, availableBrokers);
        // Remove candidates that point to inactive brokers
        Set<String> activeBrokers = Collections.emptySet();
        try {
            activeBrokers = availableActiveBrokers.get();
            // Need to use an explicit Iterator object to prevent concurrent modification exceptions
            Iterator<Map.Entry<Long, ResourceUnit>> candidateIterator = finalCandidates.entries().iterator();
            while (candidateIterator.hasNext()) {
                Map.Entry<Long, ResourceUnit> candidate = candidateIterator.next();
                String candidateBrokerName = candidate.getValue().getResourceId().replace("http://", "");
                if (!activeBrokers.contains(candidateBrokerName)) {
                    candidateIterator.remove(); // Current candidate points to an inactive broker, so remove it
                }
            }
        } catch (Exception e) {
            log.warn("Error during attempt to remove inactive brokers while searching for least active broker", e);
        }

        if (finalCandidates.size() > 0) {
            if (this.getLoadBalancerPlacementStrategy().equals(LOADBALANCER_STRATEGY_LLS)) {
                selectedBroker = findBrokerForPlacement(finalCandidates, serviceUnit);
            } else {
                selectedBroker = placementStrategy.findBrokerForPlacement(finalCandidates);
            }
            log.debug("Selected : [{}] for ServiceUnit : [{}]", selectedBroker.getResourceId(),
                    serviceUnit.getNamespaceObject().toString());
            return selectedBroker;
        } else {
            // No available broker found
            log.warn("No broker available to acquire service unit: [{}]", serviceUnit);
            return null;
        }
    }

    /*
     * only update the report if a minute has elapsed since last update, since brokers update their report every minute.
     *
     * we should calculate the rank only for updated path but for now we read all the reports and re-calculate
     * everything
     */
    @Override
    public void onUpdate(String path, LoadReport data, Stat stat) {
        log.debug("Received updated load report from broker node - [{}], scheduling re-ranking of brokers.", path);
        scheduler.submit(this::updateRanking);
    }

    private void updateRanking() {
        try {
            synchronized (currentLoadReports) {
                currentLoadReports.clear();
                Set<String> activeBrokers = availableActiveBrokers.get();
                for (String broker : activeBrokers) {
                    try {
                        String key = String.format("%s/%s", LOADBALANCE_BROKERS_ROOT, broker);
                        LoadReport lr = loadReportCacheZk.get(key)
                                .orElseThrow(() -> new KeeperException.NoNodeException());
                        ResourceUnit ru = new SimpleResourceUnit(String.format("http://%s", lr.getName()),
                                fromLoadReport(lr));
                        this.currentLoadReports.put(ru, lr);
                    } catch (Exception e) {
                        log.warn("Error reading load report from Cache for broker - [{}], [{}]", broker, e);
                    }
                }
                updateRealtimeResourceQuota();
                doLoadRanking();
            }
        } catch (Exception e) {
            log.warn("Error reading active brokers list from zookeeper while re-ranking load reports [{}]", e);
        }
    }

    public static boolean isAboveLoadLevel(SystemResourceUsage usage, float thresholdPercentage) {
        return (usage.bandwidthOut.percentUsage() > thresholdPercentage
                || usage.bandwidthIn.percentUsage() > thresholdPercentage
                || usage.cpu.percentUsage() > thresholdPercentage || usage.memory.percentUsage() > thresholdPercentage);
    }

    public static boolean isBelowLoadLevel(SystemResourceUsage usage, float thresholdPercentage) {
        return (usage.bandwidthOut.percentUsage() < thresholdPercentage
                && usage.bandwidthIn.percentUsage() < thresholdPercentage
                && usage.cpu.percentUsage() < thresholdPercentage && usage.memory.percentUsage() < thresholdPercentage);
    }

    private static long getRealtimeJvmHeapUsageMBytes() {
        long totalHeapMemoryInBytes = Runtime.getRuntime().totalMemory();
        long freeHeapMemoryInBytes = Runtime.getRuntime().freeMemory();
        long memoryUsageInBytes = totalHeapMemoryInBytes - freeHeapMemoryInBytes;
        long memoryUsageInMBytes = 0L;
        if (memoryUsageInBytes > 0L) {
            memoryUsageInMBytes = memoryUsageInBytes / MBytes;
        }
        return memoryUsageInMBytes;
    }

    private long getAverageJvmHeapUsageMBytes() {
        if (this.avgJvmHeapUsageMBytes > 0) {
            return this.avgJvmHeapUsageMBytes;
        } else {
            return getRealtimeJvmHeapUsageMBytes();
        }
    }

    private SystemResourceUsage getSystemResourceUsage() throws IOException {
        SystemResourceUsage systemResourceUsage = new SystemResourceUsage();
        if (isNotEmpty(pulsar.getConfiguration().getLoadBalancerHostUsageScriptPath())) {
            systemResourceUsage = ObjectMapperFactory.getThreadLocal().readValue(brokerHostUsage.getBrokerHostUsage(),
                    SystemResourceUsage.class);

            // Override System memory usage and limit with JVM heap usage and limit
            long maxHeapMemoryInBytes = Runtime.getRuntime().maxMemory();
            long memoryUsageInMBytes = getAverageJvmHeapUsageMBytes();
            systemResourceUsage.memory.usage = (double) memoryUsageInMBytes;
            systemResourceUsage.memory.limit = (double) (maxHeapMemoryInBytes) / MBytes;

            // Collect JVM direct memory
            systemResourceUsage.directMemory.usage = (double) (sun.misc.SharedSecrets.getJavaNioAccess()
                    .getDirectBufferPool().getMemoryUsed() / MBytes);
            systemResourceUsage.directMemory.limit = (double) (sun.misc.VM.maxDirectMemory() / MBytes);
        }

        return systemResourceUsage;
    }

    @Override
    public LoadReport generateLoadReport() throws Exception {
        long timeSinceLastGenMillis = System.currentTimeMillis() - lastLoadReport.getTimestamp();
        if (timeSinceLastGenMillis <= LOAD_REPORT_UPDATE_MIMIMUM_INTERVAL) {
            return lastLoadReport;
        }

        try {
            LoadReport loadReport = new LoadReport(pulsar.getWebServiceAddress(), pulsar.getWebServiceAddressTls(),
                    pulsar.getBrokerServiceUrl(), pulsar.getBrokerServiceUrlTls());
            loadReport.setName(String.format("%s:%s", pulsar.getAdvertisedAddress(), pulsar.getConfiguration().getWebServicePort()));
            SystemResourceUsage systemResourceUsage = this.getSystemResourceUsage();
            loadReport.setOverLoaded(
                    isAboveLoadLevel(systemResourceUsage, this.getLoadBalancerBrokerOverloadedThresholdPercentage()));
            loadReport.setUnderLoaded(
                    isBelowLoadLevel(systemResourceUsage, this.getLoadBalancerBrokerUnderloadedThresholdPercentage()));

            loadReport.setSystemResourceUsage(systemResourceUsage);
            loadReport.setBundleStats(pulsar.getBrokerService().getBundleStats());
            loadReport.setTimestamp(System.currentTimeMillis());
            return loadReport;
        } catch (Exception e) {
            log.error("[{}] Failed to generate LoadReport for broker, reason [{}]", e.getMessage(), e);
            throw e;
        }
    }

    @Override
    public void setLoadReportForceUpdateFlag() {
        this.forceLoadReportUpdate = true;
    }

    @Override
    public void writeLoadReportOnZookeeper() throws Exception {
        // update average JVM heap usage to average value of the last 120 seconds
        long realtimeJvmHeapUsage = getRealtimeJvmHeapUsageMBytes();
        if (this.avgJvmHeapUsageMBytes <= 0) {
            this.avgJvmHeapUsageMBytes = realtimeJvmHeapUsage;
        } else {
            long weight = Math.max(1, TimeUnit.SECONDS.toMillis(120) / LOAD_REPORT_UPDATE_MIMIMUM_INTERVAL);
            this.avgJvmHeapUsageMBytes = ((weight - 1) * this.avgJvmHeapUsageMBytes + realtimeJvmHeapUsage) / weight;
        }

        // Update LoadReport in below situations:
        // 1) This is the first time to update LoadReport
        // 2) The last LoadReport is 5 minutes ago
        // 3) There is more than 10% change on number of bundles assigned comparing with broker's maximum capacity
        // 4) There is more than 10% change on resource usage comparing with broker's resource limit
        boolean needUpdate = false;
        if (lastLoadReport == null || this.forceLoadReportUpdate == true) {
            needUpdate = true;
            this.forceLoadReportUpdate = false;
        } else {
            long timestampNow = System.currentTimeMillis();
            long timeElapsedSinceLastReport = timestampNow - lastLoadReport.getTimestamp();
            int maxUpdateIntervalInMinutes = pulsar.getConfiguration().getLoadBalancerReportUpdateMaxIntervalMinutes();
            if (timeElapsedSinceLastReport > TimeUnit.MINUTES.toMillis(maxUpdateIntervalInMinutes)) {
                needUpdate = true;
            } else if (timeElapsedSinceLastReport > LOAD_REPORT_UPDATE_MIMIMUM_INTERVAL) {
                // check number of bundles assigned, comparing with last LoadReport
                long oldBundleCount = lastLoadReport.getNumBundles();
                long newBundleCount = pulsar.getBrokerService().getNumberOfNamespaceBundles();
                long bundleCountChange = Math.abs(oldBundleCount - newBundleCount);
                long maxCapacity = ResourceUnitRanking.calculateBrokerMaxCapacity(
                        lastLoadReport.getSystemResourceUsage(),
                        pulsar.getLocalZkCacheService().getResourceQuotaCache().getDefaultQuota());
                double bundlePercentageChange = (maxCapacity > 0) ? (bundleCountChange * 100 / maxCapacity) : 0;
                if (newBundleCount < oldBundleCount || bundlePercentageChange > pulsar.getConfiguration()
                        .getLoadBalancerReportUpdateThresholdPercentage()) {
                    needUpdate = true;
                }

                // check resource usage comparing with last LoadReport
                if (!needUpdate && timestampNow - this.lastResourceUsageTimestamp > TimeUnit.MINUTES
                        .toMillis(pulsar.getConfiguration().getLoadBalancerHostUsageCheckIntervalMinutes())) {
                    SystemResourceUsage oldUsage = lastLoadReport.getSystemResourceUsage();
                    SystemResourceUsage newUsage = this.getSystemResourceUsage();
                    this.lastResourceUsageTimestamp = timestampNow;

                    // calculate percentage of change
                    double cpuChange = (newUsage.cpu.limit > 0)
                            ? ((newUsage.cpu.usage - oldUsage.cpu.usage) * 100 / newUsage.cpu.limit) : 0;
                    double memChange = (newUsage.memory.limit > 0)
                            ? ((newUsage.memory.usage - oldUsage.memory.usage) * 100 / newUsage.memory.limit) : 0;
                    double directMemChange = (newUsage.directMemory.limit > 0)
                            ? ((newUsage.directMemory.usage - oldUsage.directMemory.usage) * 100
                                    / newUsage.directMemory.limit)
                            : 0;
                    double bandwidthOutChange = (newUsage.bandwidthOut.limit > 0)
                            ? ((newUsage.bandwidthOut.usage - oldUsage.bandwidthOut.usage) * 100
                                    / newUsage.bandwidthOut.limit)
                            : 0;
                    double bandwidthInChange = (newUsage.bandwidthIn.limit > 0)
                            ? ((newUsage.bandwidthIn.usage - oldUsage.bandwidthIn.usage) * 100
                                    / newUsage.bandwidthIn.limit)
                            : 0;
                    long resourceChange = (long) Math.min(100.0,
                            Math.max(Math.abs(cpuChange),
                                    Math.max(Math.abs(directMemChange), Math.max(Math.abs(memChange),
                                            Math.max(Math.abs(bandwidthOutChange), Math.abs(bandwidthInChange))))));

                    if (resourceChange > pulsar.getConfiguration().getLoadBalancerReportUpdateThresholdPercentage()) {
                        needUpdate = true;
                        log.info("LoadReport update triggered by change on resource usage, detal ({}).",
                                String.format(
                                        "cpu: %.1f%%, mem: %.1f%%, directMemory: %.1f%%, bandwidthIn: %.1f%%, bandwidthOut: %.1f%%)",
                                        cpuChange, memChange, directMemChange, bandwidthInChange, bandwidthOutChange));
                    }
                }
            }
        }

        if (needUpdate) {
            LoadReport lr = generateLoadReport();
            pulsar.getZkClient().setData(brokerZnodePath, ObjectMapperFactory.getThreadLocal().writeValueAsBytes(lr),
                    -1);
            this.lastLoadReport = lr;
            this.lastResourceUsageTimestamp = lr.getTimestamp();
        }
    }

    private String getNamespaceNameFromBundleName(String bundleName) {
        // the bundle format is property/cluster/namespace/0x00000000_0xFFFFFFFF
        int pos = bundleName.lastIndexOf("/");
        checkArgument(pos != -1);
        return bundleName.substring(0, pos);
    }

    private String getBundleRangeFromBundleName(String bundleName) {
        // the bundle format is property/cluster/namespace/0x00000000_0xFFFFFFFF
        int pos = bundleName.lastIndexOf("/");
        checkArgument(pos != -1);
        return bundleName.substring(pos + 1, bundleName.length());
    }

    // todo: changeme: this can be optimized, we don't have to iterate through everytime
    private boolean isBrokerAvailableForRebalancing(String bundleName, long maxLoadLevel) {

        NamespaceName namespaceName = new NamespaceName(getNamespaceNameFromBundleName(bundleName));
        Map<Long, Set<ResourceUnit>> availableBrokers = sortedRankings.get();
        // this does not have "http://" in front, hacky but no time to pretty up
        Multimap<Long, ResourceUnit> brokers = getFinalCandidates(namespaceName, availableBrokers);

        for (Object broker : brokers.values()) {
            ResourceUnit underloadedRU = (ResourceUnit) broker;
            LoadReport currentLoadReport = currentLoadReports.get(underloadedRU);
            if (isBelowLoadLevel(currentLoadReport.getSystemResourceUsage(), maxLoadLevel)) {
                return true;
            }
        }
        return false;
    }

    /**
     * If load balancing is enabled, load shedding is enabled by default unless forced off by setting a flag in global
     * zk /admin/flags/load-shedding-unload-disabled
     *
     * @return false by default, unload is allowed in load shedding true if zk flag is set, unload is disabled
     */
    public boolean isUnloadDisabledInLoadShedding() {
        if (!pulsar.getConfiguration().isLoadBalancerEnabled()) {
            return true;
        }

        boolean unloadDisabledInLoadShedding = false;
        try {
            unloadDisabledInLoadShedding = pulsar.getGlobalZkCache()
                    .exists(AdminResource.LOAD_SHEDDING_UNLOAD_DISABLED_FLAG_PATH);
        } catch (Exception e) {
            log.warn("Unable to fetch contents of [{}] from global zookeeper",
                    AdminResource.LOAD_SHEDDING_UNLOAD_DISABLED_FLAG_PATH, e);
        }
        return unloadDisabledInLoadShedding;
    }

    private void unloadNamespacesFromOverLoadedBrokers(Map<ResourceUnit, String> namespaceBundlesToUnload) {
        for (Map.Entry<ResourceUnit, String> bundle : namespaceBundlesToUnload.entrySet()) {
            String brokerName = bundle.getKey().getResourceId();
            String bundleName = bundle.getValue();
            try {
                if (unloadedHotNamespaceCache.getIfPresent(bundleName) == null) {
                    if (!isUnloadDisabledInLoadShedding()) {
                        log.info("Unloading namespace {} from overloaded broker {}", bundleName, brokerName);
                        adminCache.get(brokerName).namespaces().unloadNamespaceBundle(
                                getNamespaceNameFromBundleName(bundleName), getBundleRangeFromBundleName(bundleName));
                        log.info("Successfully unloaded namespace {} from broker {}", bundleName, brokerName);
                    } else {
                        log.info("DRY RUN: Unload in Load Shedding is disabled. Namespace {} would have been "
                                + "unloaded from overloaded broker {} otherwise.", bundleName, brokerName);
                    }
                    unloadedHotNamespaceCache.put(bundleName, System.currentTimeMillis());
                } else {
                    // we can't unload this namespace so move to next one
                    log.info("Can't unload Namespace {} because it was unloaded last at {} and unload interval has "
                            + "not exceeded.", bundleName, LocalDateTime.now());
                }
            } catch (Exception e) {
                log.warn("ERROR failed to unload the bundle {} from overloaded broker {}", bundleName, brokerName, e);
            }
        }
    }

    @Override
    public void doLoadShedding() {
        long overloadThreshold = this.getLoadBalancerBrokerOverloadedThresholdPercentage();
        long comfortLoadLevel = this.getLoadBalancerBrokerComfortLoadThresholdPercentage();
        log.info("Running load shedding task as leader broker, overload threshold {}, comfort loadlevel {}",
                overloadThreshold, comfortLoadLevel);
        // overloadedRU --> bundleName
        Map<ResourceUnit, String> namespaceBundlesToBeUnloaded = new HashMap<>();
        synchronized (currentLoadReports) {
            for (Map.Entry<ResourceUnit, LoadReport> entry : currentLoadReports.entrySet()) {
                ResourceUnit overloadedRU = entry.getKey();
                LoadReport lr = entry.getValue();
                if (isAboveLoadLevel(lr.getSystemResourceUsage(), overloadThreshold)) {
                    ResourceType bottleneckResourceType = lr.getBottleneckResourceType();
                    Map<String, NamespaceBundleStats> bundleStats = lr.getSortedBundleStats(bottleneckResourceType);
                    // 1. owns only one namespace
                    if (bundleStats.size() == 1) {
                        // can't unload one namespace, just issue a warning message
                        String bundleName = lr.getBundleStats().keySet().iterator().next();
                        log.warn(
                                "HIGH USAGE WARNING : Sole namespace bundle {} is overloading broker {}. "
                                        + "No Load Shedding will be done on this broker",
                                bundleName, overloadedRU.getResourceId());
                        continue;
                    }
                    for (Map.Entry<String, NamespaceBundleStats> bundleStat : bundleStats.entrySet()) {
                        String bundleName = bundleStat.getKey();
                        NamespaceBundleStats stats = bundleStat.getValue();
                        // We need at least one underloaded RU from list of candidates that can host this bundle
                        if (isBrokerAvailableForRebalancing(bundleStat.getKey(), comfortLoadLevel)) {
                            log.info(
                                    "Namespace bundle {} will be unloaded from overloaded broker {}, bundle stats (topics: {}, producers {}, "
                                            + "consumers {}, bandwidthIn {}, bandwidthOut {})",
                                    bundleName, overloadedRU.getResourceId(), stats.topics, stats.producerCount,
                                    stats.consumerCount, stats.msgThroughputIn, stats.msgThroughputOut);
                            namespaceBundlesToBeUnloaded.put(overloadedRU, bundleName);
                        } else {
                            log.info("Unable to shed load from broker {}, no brokers with enough capacity available "
                                    + "for re-balancing {}", overloadedRU.getResourceId(), bundleName);
                        }
                        break;
                    }
                }
            }
        }
        unloadNamespacesFromOverLoadedBrokers(namespaceBundlesToBeUnloaded);
    }

    /**
     * Detect and split hot namespace bundles
     */
    @Override
    public void doNamespaceBundleSplit() throws Exception {
        int maxBundleCount = pulsar.getConfiguration().getLoadBalancerNamespaceMaximumBundles();
        long maxBundleTopics = pulsar.getConfiguration().getLoadBalancerNamespaceBundleMaxTopics();
        long maxBundleSessions = pulsar.getConfiguration().getLoadBalancerNamespaceBundleMaxSessions();
        long maxBundleMsgRate = pulsar.getConfiguration().getLoadBalancerNamespaceBundleMaxMsgRate();
        long maxBundleBandwidth = pulsar.getConfiguration().getLoadBalancerNamespaceBundleMaxBandwidthMbytes() * MBytes;

        log.info(
                "Running namespace bundle split with thresholds: topics {}, sessions {}, msgRate {}, bandwidth {}, maxBundles {}",
                maxBundleTopics, maxBundleSessions, maxBundleMsgRate, maxBundleBandwidth, maxBundleCount);
        if (this.lastLoadReport == null || this.lastLoadReport.getBundleStats() == null) {
            return;
        }

        Map<String, NamespaceBundleStats> bundleStats = this.lastLoadReport.getBundleStats();
        Set<String> bundlesToBeSplit = new HashSet<>();
        for (Map.Entry<String, NamespaceBundleStats> statsEntry : bundleStats.entrySet()) {
            String bundleName = statsEntry.getKey();
            NamespaceBundleStats stats = statsEntry.getValue();

            long totalSessions = stats.consumerCount + stats.producerCount;
            double totalMsgRate = stats.msgRateIn + stats.msgRateOut;
            double totalBandwidth = stats.msgThroughputIn + stats.msgThroughputOut;

            boolean needSplit = false;
            if (stats.topics > maxBundleTopics || totalSessions > maxBundleSessions || totalMsgRate > maxBundleMsgRate
                    || totalBandwidth > maxBundleBandwidth) {
                if (stats.topics <= 1) {
                    log.info("Unable to split hot namespace bundle {} since there is only one topic.", bundleName);
                } else {
                    NamespaceName namespaceName = new NamespaceName(getNamespaceNameFromBundleName(bundleName));
                    int numBundles = pulsar.getNamespaceService().getBundleCount(namespaceName);
                    if (numBundles >= maxBundleCount) {
                        log.info("Unable to split hot namespace bundle {} since the namespace has too many bundles.",
                                bundleName);
                    } else {
                        needSplit = true;
                    }
                }
            }

            if (needSplit) {
                if (this.getLoadBalancerAutoBundleSplitEnabled()) {
                    log.info(
                            "Will split hot namespace bundle {}, topics {}, producers+consumers {}, msgRate in+out {}, bandwidth in+out {}",
                            bundleName, stats.topics, totalSessions, totalMsgRate, totalBandwidth);
                    bundlesToBeSplit.add(bundleName);
                } else {
                    log.info(
                            "DRY RUN - split hot namespace bundle {}, topics {}, producers+consumers {}, msgRate in+out {}, bandwidth in+out {}",
                            bundleName, stats.topics, totalSessions, totalMsgRate, totalBandwidth);
                }
            }
        }

        if (bundlesToBeSplit.size() > 0) {
            for (String bundleName : bundlesToBeSplit) {
                try {
                    pulsar.getAdminClient().namespaces().splitNamespaceBundle(
                            getNamespaceNameFromBundleName(bundleName), getBundleRangeFromBundleName(bundleName));
                    log.info("Successfully split namespace bundle {}", bundleName);
                } catch (Exception e) {
                    log.error("Failed to split namespace bundle {}", bundleName, e);
                }
            }
            this.setLoadReportForceUpdateFlag();
        }
    }

    @Override
    public void stop() throws PulsarServerException {
        // do nothing
    }
}
