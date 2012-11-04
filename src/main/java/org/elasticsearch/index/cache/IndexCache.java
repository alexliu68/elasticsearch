/*
 * Licensed to ElasticSearch and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. ElasticSearch licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.cache;

import org.apache.lucene.index.IndexReader;
import org.elasticsearch.ElasticSearchException;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.component.CloseableComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.AbstractIndexComponent;
import org.elasticsearch.index.Index;
import org.elasticsearch.index.cache.bloom.BloomCache;
import org.elasticsearch.index.cache.field.data.FieldDataCache;
import org.elasticsearch.index.cache.filter.FilterCache;
import org.elasticsearch.index.cache.id.IdCache;
import org.elasticsearch.index.cache.query.parser.QueryParserCache;
import org.elasticsearch.index.settings.IndexSettings;

/**
 *
 */
public class IndexCache extends AbstractIndexComponent implements CloseableComponent, ClusterStateListener {

    private final FilterCache filterCache;

    private final FieldDataCache fieldDataCache;

    private final QueryParserCache queryParserCache;

    private final IdCache idCache;

    private final BloomCache bloomCache;

    private final TimeValue refreshInterval;

    private ClusterService clusterService;

    private long latestCacheStatsTimestamp = -1;
    private CacheStats latestCacheStats;

    @Inject
    public IndexCache(Index index, @IndexSettings Settings indexSettings, FilterCache filterCache, FieldDataCache fieldDataCache,
                      QueryParserCache queryParserCache, IdCache idCache, BloomCache bloomCache) {
        super(index, indexSettings);
        this.filterCache = filterCache;
        this.fieldDataCache = fieldDataCache;
        this.queryParserCache = queryParserCache;
        this.idCache = idCache;
        this.bloomCache = bloomCache;

        this.refreshInterval = componentSettings.getAsTime("stats.refresh_interval", TimeValue.timeValueSeconds(1));

        logger.debug("Using stats.refresh_interval [{}]", refreshInterval);
    }

    @Inject(optional = true)
    public void setClusterService(@Nullable ClusterService clusterService) {
        this.clusterService = clusterService;
        if (clusterService != null) {
            clusterService.add(this);
        }
    }

    public synchronized void invalidateCache() {
        FilterCache.EntriesStats filterEntriesStats = filterCache.entriesStats();
        latestCacheStats = new CacheStats(fieldDataCache.evictions(), filterCache.evictions(), fieldDataCache.sizeInBytes(), filterEntriesStats.sizeInBytes, filterEntriesStats.count, bloomCache.sizeInBytes(), idCache.sizeInBytes(), idCache.sizeInBytesByTypeMap());
        latestCacheStatsTimestamp = System.currentTimeMillis();
    }

    public synchronized CacheStats stats() {
        long timestamp = System.currentTimeMillis();
        if ((timestamp - latestCacheStatsTimestamp) > refreshInterval.millis()) {
            FilterCache.EntriesStats filterEntriesStats = filterCache.entriesStats();
            latestCacheStats = new CacheStats(fieldDataCache.evictions(), filterCache.evictions(), fieldDataCache.sizeInBytes(), filterEntriesStats.sizeInBytes, filterEntriesStats.count, bloomCache.sizeInBytes(), idCache.sizeInBytes(), idCache.sizeInBytesByTypeMap());
            latestCacheStatsTimestamp = timestamp;
        }
        return latestCacheStats;
    }

    public FilterCache filter() {
        return filterCache;
    }

    public FieldDataCache fieldData() {
        return fieldDataCache;
    }

    public IdCache idCache() {
        return this.idCache;
    }

    public BloomCache bloomCache() {
        return this.bloomCache;
    }

    public QueryParserCache queryParserCache() {
        return this.queryParserCache;
    }

    @Override
    public void close() throws ElasticSearchException {
        filterCache.close();
        fieldDataCache.close();
        idCache.close();
        queryParserCache.close();
        bloomCache.close();
        if (clusterService != null) {
            clusterService.remove(this);
        }
    }

    public void clear(IndexReader reader) {
        filterCache.clear(reader);
        fieldDataCache.clear(reader);
        idCache.clear(reader);
        bloomCache.clear(reader);
    }

    public void clear(String reason) {
        filterCache.clear(reason);
        fieldDataCache.clear(reason);
        idCache.clear();
        queryParserCache.clear();
        bloomCache.clear();
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        // clear the query parser cache if the metadata (mappings) changed...
        if (event.metaDataChanged()) {
            queryParserCache.clear();
        }
    }
}
