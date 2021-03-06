/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.xpack.core.search;

import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.action.admin.indices.stats.CommonStats;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.xpack.core.LocalStateCompositeXPackPlugin;
import org.elasticsearch.xpack.core.search.action.ClosePointInTimeAction;
import org.elasticsearch.xpack.core.search.action.ClosePointInTimeRequest;
import org.elasticsearch.action.search.SearchPhaseExecutionException;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.search.SearchType;
import org.elasticsearch.action.search.ShardSearchFailure;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.query.MatchAllQueryBuilder;
import org.elasticsearch.index.query.RangeQueryBuilder;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.search.SearchContextMissingException;
import org.elasticsearch.search.SearchService;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.xpack.core.search.action.OpenPointInTimeAction;
import org.elasticsearch.xpack.core.search.action.OpenPointInTimeRequest;
import org.elasticsearch.xpack.core.search.action.OpenPointInTimeResponse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertHitCount;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertNoFailures;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class PointInTimeIT extends ESIntegTestCase {

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return Settings.builder()
            .put(super.nodeSettings(nodeOrdinal))
            .put(SearchService.KEEPALIVE_INTERVAL_SETTING.getKey(), TimeValue.timeValueMillis(randomIntBetween(100, 500)))
            .build();
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        final List<Class<? extends Plugin>> plugins = new ArrayList<>();
        plugins.add(LocalStateCompositeXPackPlugin.class);
        return plugins;
    }

    public void testBasic() {
        createIndex("test");
        int numDocs = randomIntBetween(10, 50);
        for (int i = 0; i < numDocs; i++) {
            String id = Integer.toString(i);
            client().prepareIndex("test").setId(id).setSource("value", i).get();
        }
        refresh("test");
        String readerId = openPointInTime(new String[] { "test" }, TimeValue.timeValueMinutes(2));
        SearchResponse resp1 = client().prepareSearch().setPreference(null).setSearchContext(readerId, TimeValue.timeValueMinutes(2)).get();
        assertThat(resp1.pointInTimeId(), equalTo(readerId));
        assertHitCount(resp1, numDocs);
        int deletedDocs = 0;
        for (int i = 0; i < numDocs; i++) {
            if (randomBoolean()) {
                String id = Integer.toString(i);
                client().prepareDelete("test", id).get();
                deletedDocs++;
            }
        }
        refresh("test");
        if (randomBoolean()) {
            SearchResponse resp2 = client().prepareSearch("test").setPreference(null).setQuery(new MatchAllQueryBuilder()).get();
            assertNoFailures(resp2);
            assertHitCount(resp2, numDocs - deletedDocs);
        }
        try {
            SearchResponse resp3 = client().prepareSearch()
                .setPreference(null)
                .setQuery(new MatchAllQueryBuilder())
                .setSearchContext(resp1.pointInTimeId(), TimeValue.timeValueMinutes(2))
                .get();
            assertNoFailures(resp3);
            assertHitCount(resp3, numDocs);
            assertThat(resp3.pointInTimeId(), equalTo(readerId));
        } finally {
            closePointInTime(readerId);
        }
    }

    public void testMultipleIndices() {
        int numIndices = randomIntBetween(1, 5);
        for (int i = 1; i <= numIndices; i++) {
            createIndex("index-" + i);
        }
        int numDocs = randomIntBetween(10, 50);
        for (int i = 0; i < numDocs; i++) {
            String id = Integer.toString(i);
            String index = "index-" + randomIntBetween(1, numIndices);
            client().prepareIndex(index).setId(id).setSource("value", i).get();
        }
        refresh();
        String readerId = openPointInTime(new String[] { "*" }, TimeValue.timeValueMinutes(2));
        SearchResponse resp1 = client().prepareSearch().setPreference(null).setSearchContext(readerId, TimeValue.timeValueMinutes(2)).get();
        assertNoFailures(resp1);
        assertHitCount(resp1, numDocs);
        int moreDocs = randomIntBetween(10, 50);
        for (int i = 0; i < moreDocs; i++) {
            String id = "more-" + i;
            String index = "index-" + randomIntBetween(1, numIndices);
            client().prepareIndex(index).setId(id).setSource("value", i).get();
        }
        refresh();
        try {
            SearchResponse resp2 = client().prepareSearch().get();
            assertNoFailures(resp2);
            assertHitCount(resp2, numDocs + moreDocs);

            SearchResponse resp3 = client().prepareSearch()
                .setPreference(null)
                .setSearchContext(resp1.pointInTimeId(), TimeValue.timeValueMinutes(1))
                .get();
            assertNoFailures(resp3);
            assertHitCount(resp3, numDocs);
        } finally {
            closePointInTime(resp1.pointInTimeId());
        }
    }

    public void testPointInTimeNotFound() throws Exception {
        createIndex("index");
        int index1 = randomIntBetween(10, 50);
        for (int i = 0; i < index1; i++) {
            String id = Integer.toString(i);
            client().prepareIndex("index").setId(id).setSource("value", i).get();
        }
        refresh();
        String readerId = openPointInTime(new String[] { "index" }, TimeValue.timeValueSeconds(5));
        SearchResponse resp1 = client().prepareSearch()
            .setPreference(null)
            .setSearchContext(readerId, TimeValue.timeValueMillis(randomIntBetween(0, 10)))
            .get();
        assertNoFailures(resp1);
        assertHitCount(resp1, index1);
        if (rarely()) {
            assertBusy(() -> {
                final CommonStats stats = client().admin().indices().prepareStats().setSearch(true).get().getTotal();
                assertThat(stats.search.getOpenContexts(), equalTo(0L));
            }, 60, TimeUnit.SECONDS);
        } else {
            closePointInTime(resp1.pointInTimeId());
        }
        SearchPhaseExecutionException e = expectThrows(
            SearchPhaseExecutionException.class,
            () -> client().prepareSearch()
                .setPreference(null)
                .setSearchContext(resp1.pointInTimeId(), TimeValue.timeValueMinutes(1))
                .get()
        );
        for (ShardSearchFailure failure : e.shardFailures()) {
            assertThat(ExceptionsHelper.unwrapCause(failure.getCause()), instanceOf(SearchContextMissingException.class));
        }
    }

    public void testIndexNotFound() {
        createIndex("index-1");
        createIndex("index-2");

        int index1 = randomIntBetween(10, 50);
        for (int i = 0; i < index1; i++) {
            String id = Integer.toString(i);
            client().prepareIndex("index-1").setId(id).setSource("value", i).get();
        }

        int index2 = randomIntBetween(10, 50);
        for (int i = 0; i < index2; i++) {
            String id = Integer.toString(i);
            client().prepareIndex("index-2").setId(id).setSource("value", i).get();
        }
        refresh();
        String readerId = openPointInTime(new String[] { "index-*" }, TimeValue.timeValueMinutes(2));
        SearchResponse resp1 = client().prepareSearch().setPreference(null).setSearchContext(readerId, TimeValue.timeValueMinutes(2)).get();
        assertNoFailures(resp1);
        assertHitCount(resp1, index1 + index2);
        client().admin().indices().prepareDelete("index-1").get();
        if (randomBoolean()) {
            SearchResponse resp2 = client().prepareSearch("index-*").get();
            assertNoFailures(resp2);
            assertHitCount(resp2, index2);

        }
        expectThrows(
            IndexNotFoundException.class,
            () -> client().prepareSearch()
                .setPreference(null)
                .setSearchContext(resp1.pointInTimeId(), TimeValue.timeValueMinutes(1))
                .get()
        );
        closePointInTime(resp1.pointInTimeId());
    }

    public void testCanMatch() throws Exception {
        final Settings.Builder settings = Settings.builder()
            .put(IndexMetadata.SETTING_NUMBER_OF_SHARDS, randomIntBetween(5, 10))
            .put(IndexMetadata.SETTING_NUMBER_OF_REPLICAS, 0)
            .put(IndexSettings.INDEX_SEARCH_IDLE_AFTER.getKey(), TimeValue.timeValueMillis(randomIntBetween(50, 100)));
        assertAcked(
            prepareCreate("test").setSettings(settings)
                .setMapping("{\"properties\":{\"created_date\":{\"type\": \"date\", \"format\": \"yyyy-MM-dd\"}}}")
        );
        ensureGreen("test");
        String readerId = openPointInTime(new String[] { "test*" }, TimeValue.timeValueMinutes(2));
        try {
            for (String node : internalCluster().nodesInclude("test")) {
                for (IndexService indexService : internalCluster().getInstance(IndicesService.class, node)) {
                    for (IndexShard indexShard : indexService) {
                        assertBusy(() -> assertTrue(indexShard.isSearchIdle()));
                    }
                }
            }
            client().prepareIndex("test").setId("1").setSource("created_date", "2020-01-01").get();
            SearchResponse resp = client().prepareSearch()
                .setQuery(new RangeQueryBuilder("created_date").gte("2020-01-02").lte("2020-01-03"))
                .setSearchType(SearchType.QUERY_THEN_FETCH)
                .setPreference(null)
                .setPreFilterShardSize(randomIntBetween(2, 3))
                .setMaxConcurrentShardRequests(randomIntBetween(1, 2))
                .setSearchContext(readerId, TimeValue.timeValueMinutes(2))
                .get();
            assertThat(resp.getHits().getHits(), arrayWithSize(0));
            for (String node : internalCluster().nodesInclude("test")) {
                for (IndexService indexService : internalCluster().getInstance(IndicesService.class, node)) {
                    for (IndexShard indexShard : indexService) {
                        // all shards are still search-idle as we did not acquire new searchers
                        assertTrue(indexShard.isSearchIdle());
                    }
                }
            }
        } finally {
            closePointInTime(readerId);
        }
    }

    private String openPointInTime(String[] indices, TimeValue keepAlive) {
        OpenPointInTimeRequest request = new OpenPointInTimeRequest(
            indices,
            OpenPointInTimeRequest.DEFAULT_INDICES_OPTIONS,
            keepAlive,
            null,
            null
        );
        final OpenPointInTimeResponse response = client().execute(OpenPointInTimeAction.INSTANCE, request).actionGet();
        return response.getSearchContextId();
    }

    private void closePointInTime(String readerId) {
        client().execute(ClosePointInTimeAction.INSTANCE, new ClosePointInTimeRequest(readerId)).actionGet();
    }
}
