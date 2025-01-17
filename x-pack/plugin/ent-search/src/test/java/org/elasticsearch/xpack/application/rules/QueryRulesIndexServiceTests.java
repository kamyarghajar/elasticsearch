/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.application.rules;

import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.indices.SystemIndexDescriptor;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.plugins.SystemIndexPlugin;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.junit.Before;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.xpack.application.rules.QueryRule.QueryRuleType;
import static org.elasticsearch.xpack.application.rules.QueryRuleCriteria.CriteriaType;
import static org.elasticsearch.xpack.application.rules.QueryRulesIndexService.QUERY_RULES_CONCRETE_INDEX_NAME;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.equalTo;

public class QueryRulesIndexServiceTests extends ESSingleNodeTestCase {

    private QueryRulesIndexService queryRulesIndexService;

    @Before
    public void setup() {
        this.queryRulesIndexService = new QueryRulesIndexService(client());
    }

    @Override
    protected Collection<Class<? extends Plugin>> getPlugins() {
        List<Class<? extends Plugin>> plugins = new ArrayList<>(super.getPlugins());
        plugins.add(TestPlugin.class);
        return plugins;
    }

    public void testEmptyState() throws Exception {
        expectThrows(ResourceNotFoundException.class, () -> awaitGetQueryRuleset("i-dont-exist"));
        expectThrows(ResourceNotFoundException.class, () -> awaitDeleteQueryRuleset("i-dont-exist"));

        QueryRulesIndexService.QueryRulesetResult listResults = awaitListQueryRulesets(0, 10);
        assertThat(listResults.totalResults(), equalTo(0L));
    }

    public void testUpdateQueryRuleset() throws Exception {
        {
            final QueryRule myQueryRule1 = new QueryRule(
                "my_rule1",
                QueryRuleType.PINNED,
                List.of(new QueryRuleCriteria(CriteriaType.EXACT, "query_string", "foo")),
                Map.of("ids", List.of("id1", "id2"))
            );
            final QueryRuleset myQueryRuleset = new QueryRuleset("my_ruleset", Collections.singletonList(myQueryRule1));
            IndexResponse resp = awaitPutQueryRuleset(myQueryRuleset);
            assertThat(resp.status(), anyOf(equalTo(RestStatus.CREATED), equalTo(RestStatus.OK)));
            assertThat(resp.getIndex(), equalTo(QUERY_RULES_CONCRETE_INDEX_NAME));

            QueryRuleset getQueryRuleset = awaitGetQueryRuleset(myQueryRuleset.id());
            assertThat(getQueryRuleset, equalTo(myQueryRuleset));
        }

        final QueryRule myQueryRule1 = new QueryRule(
            "my_rule1",
            QueryRuleType.PINNED,
            List.of(new QueryRuleCriteria(CriteriaType.EXACT, "query_string", "foo")),
            Map.of("docs", List.of(Map.of("_index", "my_index1", "_id", "id1"), Map.of("_index", "my_index2", "_id", "id2")))
        );
        final QueryRule myQueryRule2 = new QueryRule(
            "my_rule2",
            QueryRuleType.PINNED,
            List.of(new QueryRuleCriteria(CriteriaType.EXACT, "query_string", "bar")),
            Map.of("docs", List.of(Map.of("_index", "my_index1", "_id", "id3"), Map.of("_index", "my_index2", "_id", "id4")))
        );
        final QueryRuleset myQueryRuleset = new QueryRuleset("my_ruleset", List.of(myQueryRule1, myQueryRule2));
        IndexResponse newResp = awaitPutQueryRuleset(myQueryRuleset);
        assertThat(newResp.status(), equalTo(RestStatus.OK));
        assertThat(newResp.getIndex(), equalTo(QUERY_RULES_CONCRETE_INDEX_NAME));
        QueryRuleset getQueryRuleset = awaitGetQueryRuleset(myQueryRuleset.id());
        assertThat(getQueryRuleset, equalTo(myQueryRuleset));
    }

    public void testListQueryRulesets() throws Exception {
        int numRulesets = 10;
        for (int i = 0; i < numRulesets; i++) {
            final List<QueryRule> rules = List.of(
                new QueryRule(
                    "my_rule_" + i,
                    QueryRuleType.PINNED,
                    List.of(new QueryRuleCriteria(CriteriaType.EXACT, "query_string", "foo" + i)),
                    Map.of("ids", List.of("id1", "id2"))
                ),
                new QueryRule(
                    "my_rule_" + i + "_" + (i + 1),
                    QueryRuleType.PINNED,
                    List.of(new QueryRuleCriteria(CriteriaType.EXACT, "query_string", "bar" + i)),
                    Map.of("ids", List.of("id3", "id4"))
                )
            );
            final QueryRuleset myQueryRuleset = new QueryRuleset("my_ruleset_" + i, rules);

            IndexResponse resp = awaitPutQueryRuleset(myQueryRuleset);
            assertThat(resp.status(), equalTo(RestStatus.CREATED));
            assertThat(resp.getIndex(), equalTo(QUERY_RULES_CONCRETE_INDEX_NAME));
        }

        {
            QueryRulesIndexService.QueryRulesetResult searchResponse = awaitListQueryRulesets(0, 10);
            final List<String> rulesetIds = searchResponse.rulesetIds();
            assertNotNull(rulesetIds);
            assertThat(rulesetIds.size(), equalTo(10));
            assertThat(searchResponse.totalResults(), equalTo(10L));

            for (int i = 0; i < numRulesets; i++) {
                String rulesetId = rulesetIds.get(i);
                assertThat(rulesetId, equalTo("my_ruleset_" + i));
            }
        }

        {
            QueryRulesIndexService.QueryRulesetResult searchResponse = awaitListQueryRulesets(5, 10);
            final List<String> rulesetIds = searchResponse.rulesetIds();
            assertNotNull(rulesetIds);
            assertThat(rulesetIds.size(), equalTo(5));
            assertThat(searchResponse.totalResults(), equalTo(10L));

            for (int i = 0; i < 5; i++) {
                int index = i + 5;
                String rulesetId = rulesetIds.get(i);
                assertThat(rulesetId, equalTo("my_ruleset_" + index));
            }
        }
    }

    public void testDeleteQueryRuleset() throws Exception {
        for (int i = 0; i < 5; i++) {
            final QueryRule myQueryRule1 = new QueryRule(
                "my_rule1",
                QueryRuleType.PINNED,
                List.of(new QueryRuleCriteria(CriteriaType.EXACT, "query_string", "foo")),
                Map.of("ids", List.of("id1", "id2"))
            );
            final QueryRule myQueryRule2 = new QueryRule(
                "my_rule2",
                QueryRuleType.PINNED,
                List.of(new QueryRuleCriteria(CriteriaType.EXACT, "query_string", "bar")),
                Map.of("ids", List.of("id3", "id4"))
            );
            final QueryRuleset myQueryRuleset = new QueryRuleset("my_ruleset", List.of(myQueryRule1, myQueryRule2));
            IndexResponse resp = awaitPutQueryRuleset(myQueryRuleset);
            assertThat(resp.status(), anyOf(equalTo(RestStatus.CREATED), equalTo(RestStatus.OK)));
            assertThat(resp.getIndex(), equalTo(QUERY_RULES_CONCRETE_INDEX_NAME));

            QueryRuleset getQueryRuleset = awaitGetQueryRuleset(myQueryRuleset.id());
            assertThat(getQueryRuleset, equalTo(myQueryRuleset));
        }

        DeleteResponse resp = awaitDeleteQueryRuleset("my_ruleset");
        assertThat(resp.status(), equalTo(RestStatus.OK));
        expectThrows(ResourceNotFoundException.class, () -> awaitGetQueryRuleset("my_ruleset"));
    }

    private IndexResponse awaitPutQueryRuleset(QueryRuleset queryRuleset) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<IndexResponse> resp = new AtomicReference<>(null);
        final AtomicReference<Exception> exc = new AtomicReference<>(null);
        queryRulesIndexService.putQueryRuleset(queryRuleset, new ActionListener<>() {
            @Override
            public void onResponse(IndexResponse indexResponse) {
                resp.set(indexResponse);
                latch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                exc.set(e);
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (exc.get() != null) {
            throw exc.get();
        }
        assertNotNull(resp.get());
        return resp.get();
    }

    private QueryRuleset awaitGetQueryRuleset(String name) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<QueryRuleset> resp = new AtomicReference<>(null);
        final AtomicReference<Exception> exc = new AtomicReference<>(null);
        queryRulesIndexService.getQueryRuleset(name, new ActionListener<>() {
            @Override
            public void onResponse(QueryRuleset ruleset) {
                resp.set(ruleset);
                latch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                exc.set(e);
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (exc.get() != null) {
            throw exc.get();
        }
        assertNotNull(resp.get());
        return resp.get();
    }

    private DeleteResponse awaitDeleteQueryRuleset(String name) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<DeleteResponse> resp = new AtomicReference<>(null);
        final AtomicReference<Exception> exc = new AtomicReference<>(null);
        queryRulesIndexService.deleteQueryRuleset(name, new ActionListener<>() {
            @Override
            public void onResponse(DeleteResponse deleteResponse) {
                resp.set(deleteResponse);
                latch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                exc.set(e);
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (exc.get() != null) {
            throw exc.get();
        }
        assertNotNull(resp.get());
        return resp.get();
    }

    private QueryRulesIndexService.QueryRulesetResult awaitListQueryRulesets(int from, int size) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<QueryRulesIndexService.QueryRulesetResult> resp = new AtomicReference<>(null);
        final AtomicReference<Exception> exc = new AtomicReference<>(null);
        queryRulesIndexService.listQueryRulesets(from, size, new ActionListener<>() {
            @Override
            public void onResponse(QueryRulesIndexService.QueryRulesetResult result) {
                resp.set(result);
                latch.countDown();
            }

            @Override
            public void onFailure(Exception e) {
                exc.set(e);
                latch.countDown();
            }
        });
        assertTrue(latch.await(5, TimeUnit.SECONDS));
        if (exc.get() != null) {
            throw exc.get();
        }
        assertNotNull(resp.get());
        return resp.get();
    }

    /**
     * Test plugin to register the {@link QueryRulesIndexService} system index descriptor.
     */
    public static class TestPlugin extends Plugin implements SystemIndexPlugin {
        @Override
        public Collection<SystemIndexDescriptor> getSystemIndexDescriptors(Settings settings) {
            return List.of(QueryRulesIndexService.getSystemIndexDescriptor());
        }

        @Override
        public String getFeatureName() {
            return this.getClass().getSimpleName();
        }

        @Override
        public String getFeatureDescription() {
            return this.getClass().getCanonicalName();
        }
    }
}
