/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */
package org.elasticsearch.xpack.core.ilm;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.indices.rollover.MaxSizeCondition;
import org.elasticsearch.action.admin.indices.rollover.RolloverInfo;
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest;
import org.elasticsearch.action.admin.indices.rollover.RolloverResponse;
import org.elasticsearch.action.support.IndexComponentSelector;
import org.elasticsearch.cluster.ProjectState;
import org.elasticsearch.cluster.metadata.AliasMetadata;
import org.elasticsearch.cluster.metadata.DataStream;
import org.elasticsearch.cluster.metadata.IndexMetadata;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.ProjectMetadata;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.index.IndexVersion;
import org.elasticsearch.xpack.core.ilm.Step.StepKey;
import org.hamcrest.Matchers;
import org.mockito.Mockito;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.elasticsearch.cluster.metadata.DataStreamTestHelper.newInstance;
import static org.mockito.Mockito.verifyNoMoreInteractions;

public class RolloverStepTests extends AbstractStepTestCase<RolloverStep> {

    @Override
    public RolloverStep createRandomInstance() {
        StepKey stepKey = randomStepKey();
        StepKey nextStepKey = randomStepKey();

        return new RolloverStep(stepKey, nextStepKey, client);
    }

    @Override
    public RolloverStep mutateInstance(RolloverStep instance) {
        StepKey key = instance.getKey();
        StepKey nextKey = instance.getNextStepKey();

        switch (between(0, 1)) {
            case 0 -> key = new StepKey(key.phase(), key.action(), key.name() + randomAlphaOfLength(5));
            case 1 -> nextKey = new StepKey(nextKey.phase(), nextKey.action(), nextKey.name() + randomAlphaOfLength(5));
            default -> throw new AssertionError("Illegal randomisation branch");
        }

        return new RolloverStep(key, nextKey, instance.getClientWithoutProject());
    }

    @Override
    public RolloverStep copyInstance(RolloverStep instance) {
        return new RolloverStep(instance.getKey(), instance.getNextStepKey(), instance.getClientWithoutProject());
    }

    private IndexMetadata getIndexMetadata(String alias) {
        return IndexMetadata.builder(randomAlphaOfLength(10))
            .putAlias(AliasMetadata.builder(alias))
            .settings(settings(IndexVersion.current()).put(RolloverAction.LIFECYCLE_ROLLOVER_ALIAS, alias))
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();
    }

    private static void assertRolloverIndexRequest(RolloverRequest request, String rolloverTarget, boolean targetFailureStores) {
        String target = targetFailureStores
            ? IndexNameExpressionResolver.combineSelector(rolloverTarget, IndexComponentSelector.FAILURES)
            : rolloverTarget;

        assertNotNull(request);
        assertEquals(1, request.indices().length);
        assertEquals(target, request.indices()[0]);
        assertEquals(target, request.getRolloverTarget());
        assertFalse(request.isDryRun());
        assertEquals(0, request.getConditions().getConditions().size());
    }

    public void testPerformAction() throws Exception {
        String alias = randomAlphaOfLength(5);
        IndexMetadata indexMetadata = getIndexMetadata(alias);

        RolloverStep step = createRandomInstance();

        mockClientRolloverCall(alias, false);

        ProjectState state = projectStateFromProject(ProjectMetadata.builder(randomProjectIdOrDefault()).put(indexMetadata, true));
        performActionAndWait(step, indexMetadata, state, null);

        Mockito.verify(client).projectClient(state.projectId());
        Mockito.verify(client).admin();
        Mockito.verifyNoMoreInteractions(client);
        Mockito.verify(adminClient, Mockito.only()).indices();
        Mockito.verify(indicesClient, Mockito.only()).rolloverIndex(Mockito.any(), Mockito.any());
    }

    public void testPerformActionOnDataStream() throws Exception {
        String dataStreamName = "test-datastream";
        long ts = System.currentTimeMillis();
        IndexMetadata indexMetadata = IndexMetadata.builder(DataStream.getDefaultBackingIndexName(dataStreamName, 1, ts))
            .settings(settings(IndexVersion.current()))
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();
        IndexMetadata failureIndexMetadata = IndexMetadata.builder(DataStream.getDefaultFailureStoreName(dataStreamName, 1, ts))
            .settings(settings(IndexVersion.current()))
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();

        RolloverStep step = createRandomInstance();

        ProjectState state = projectStateFromProject(
            ProjectMetadata.builder(randomProjectIdOrDefault())
                .put(newInstance(dataStreamName, List.of(indexMetadata.getIndex()), List.of(failureIndexMetadata.getIndex())))
                .put(indexMetadata, true)
                .put(failureIndexMetadata, true)
        );
        boolean useFailureStore = randomBoolean();
        IndexMetadata indexToOperateOn = useFailureStore ? failureIndexMetadata : indexMetadata;
        mockClientRolloverCall(dataStreamName, useFailureStore);
        performActionAndWait(step, indexToOperateOn, state, null);

        Mockito.verify(client).projectClient(state.projectId());
        Mockito.verify(client).admin();
        Mockito.verifyNoMoreInteractions(client);
        Mockito.verify(adminClient, Mockito.only()).indices();
        Mockito.verify(indicesClient, Mockito.only()).rolloverIndex(Mockito.any(), Mockito.any());
    }

    public void testSkipRolloverIfDataStreamIsAlreadyRolledOver() throws Exception {
        String dataStreamName = "test-datastream";
        long ts = System.currentTimeMillis();
        IndexMetadata firstGenerationIndex = IndexMetadata.builder(DataStream.getDefaultBackingIndexName(dataStreamName, 1, ts))
            .settings(settings(IndexVersion.current()))
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();
        IndexMetadata failureFirstGenerationIndex = IndexMetadata.builder(DataStream.getDefaultFailureStoreName(dataStreamName, 1, ts))
            .settings(settings(IndexVersion.current()))
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();

        IndexMetadata writeIndex = IndexMetadata.builder(DataStream.getDefaultBackingIndexName(dataStreamName, 2, ts))
            .settings(settings(IndexVersion.current()))
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();
        IndexMetadata failureWriteIndex = IndexMetadata.builder(DataStream.getDefaultFailureStoreName(dataStreamName, 2, ts))
            .settings(settings(IndexVersion.current()))
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();
        RolloverStep step = createRandomInstance();

        ProjectState state = projectStateFromProject(
            ProjectMetadata.builder(randomProjectIdOrDefault())
                .put(firstGenerationIndex, true)
                .put(writeIndex, true)
                .put(failureFirstGenerationIndex, true)
                .put(failureWriteIndex, true)
                .put(
                    newInstance(
                        dataStreamName,
                        List.of(firstGenerationIndex.getIndex(), writeIndex.getIndex()),
                        List.of(failureFirstGenerationIndex.getIndex(), failureWriteIndex.getIndex())
                    )
                )
        );
        boolean useFailureStore = randomBoolean();
        IndexMetadata indexToOperateOn = useFailureStore ? failureFirstGenerationIndex : firstGenerationIndex;
        performActionAndWait(step, indexToOperateOn, state, null);

        verifyNoMoreInteractions(client);
        verifyNoMoreInteractions(adminClient);
        verifyNoMoreInteractions(indicesClient);
    }

    private void mockClientRolloverCall(String rolloverTarget, boolean targetFailureStore) {
        Mockito.doAnswer(invocation -> {
            RolloverRequest request = (RolloverRequest) invocation.getArguments()[0];
            @SuppressWarnings("unchecked")
            ActionListener<RolloverResponse> listener = (ActionListener<RolloverResponse>) invocation.getArguments()[1];
            assertRolloverIndexRequest(request, rolloverTarget, targetFailureStore);
            listener.onResponse(new RolloverResponse(null, null, Map.of(), request.isDryRun(), true, true, true, false));
            return null;
        }).when(indicesClient).rolloverIndex(Mockito.any(), Mockito.any());
    }

    public void testPerformActionWithIndexingComplete() throws Exception {
        String alias = randomAlphaOfLength(5);
        IndexMetadata indexMetadata = IndexMetadata.builder(randomAlphaOfLength(10))
            .putAlias(AliasMetadata.builder(alias))
            .settings(
                settings(IndexVersion.current()).put(RolloverAction.LIFECYCLE_ROLLOVER_ALIAS, alias)
                    .put(LifecycleSettings.LIFECYCLE_INDEXING_COMPLETE, true)
            )
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();

        RolloverStep step = createRandomInstance();

        ProjectState state = projectStateFromProject(ProjectMetadata.builder(randomProjectIdOrDefault()).put(indexMetadata, true));
        performActionAndWait(step, indexMetadata, state, null);
    }

    public void testPerformActionSkipsRolloverForAlreadyRolledIndex() throws Exception {
        String rolloverAlias = randomAlphaOfLength(5);
        IndexMetadata indexMetadata = IndexMetadata.builder(randomAlphaOfLength(10))
            .putAlias(AliasMetadata.builder(rolloverAlias))
            .settings(settings(IndexVersion.current()).put(RolloverAction.LIFECYCLE_ROLLOVER_ALIAS, rolloverAlias))
            .putRolloverInfo(
                new RolloverInfo(rolloverAlias, List.of(new MaxSizeCondition(ByteSizeValue.ofBytes(2L))), System.currentTimeMillis())
            )
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();

        RolloverStep step = createRandomInstance();
        ProjectState state = projectStateFromProject(ProjectMetadata.builder(randomProjectIdOrDefault()).put(indexMetadata, true));
        performActionAndWait(step, indexMetadata, state, null);

        Mockito.verify(indicesClient, Mockito.never()).rolloverIndex(Mockito.any(), Mockito.any());
    }

    public void testPerformActionFailure() {
        String alias = randomAlphaOfLength(5);
        IndexMetadata indexMetadata = getIndexMetadata(alias);
        Exception exception = new RuntimeException();
        RolloverStep step = createRandomInstance();

        Mockito.doAnswer(invocation -> {
            RolloverRequest request = (RolloverRequest) invocation.getArguments()[0];
            @SuppressWarnings("unchecked")
            ActionListener<RolloverResponse> listener = (ActionListener<RolloverResponse>) invocation.getArguments()[1];
            assertRolloverIndexRequest(request, alias, false);
            listener.onFailure(exception);
            return null;
        }).when(indicesClient).rolloverIndex(Mockito.any(), Mockito.any());

        ProjectState state = projectStateFromProject(ProjectMetadata.builder(randomProjectIdOrDefault()).put(indexMetadata, true));
        assertSame(exception, expectThrows(Exception.class, () -> performActionAndWait(step, indexMetadata, state, null)));

        Mockito.verify(client).projectClient(state.projectId());
        Mockito.verify(client).admin();
        Mockito.verifyNoMoreInteractions(client);
        Mockito.verify(adminClient, Mockito.only()).indices();
        Mockito.verify(indicesClient, Mockito.only()).rolloverIndex(Mockito.any(), Mockito.any());
    }

    public void testPerformActionInvalidNullOrEmptyAlias() {
        String alias = randomBoolean() ? "" : null;
        IndexMetadata indexMetadata = IndexMetadata.builder(randomAlphaOfLength(10))
            .settings(settings(IndexVersion.current()).put(RolloverAction.LIFECYCLE_ROLLOVER_ALIAS, alias))
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();
        RolloverStep step = createRandomInstance();

        ProjectState state = projectStateFromProject(ProjectMetadata.builder(randomProjectIdOrDefault()).put(indexMetadata, true));
        Exception e = expectThrows(IllegalArgumentException.class, () -> performActionAndWait(step, indexMetadata, state, null));
        assertThat(
            e.getMessage(),
            Matchers.is(
                String.format(
                    Locale.ROOT,
                    "setting [%s] for index [%s] is empty or not defined, it must be set to the name of the alias pointing to the group of "
                        + "indices being rolled over",
                    RolloverAction.LIFECYCLE_ROLLOVER_ALIAS,
                    indexMetadata.getIndex().getName()
                )
            )
        );
    }

    public void testPerformActionAliasDoesNotPointToIndex() {
        String alias = randomAlphaOfLength(5);
        IndexMetadata indexMetadata = IndexMetadata.builder(randomAlphaOfLength(10))
            .settings(settings(IndexVersion.current()).put(RolloverAction.LIFECYCLE_ROLLOVER_ALIAS, alias))
            .numberOfShards(randomIntBetween(1, 5))
            .numberOfReplicas(randomIntBetween(0, 5))
            .build();
        RolloverStep step = createRandomInstance();

        ProjectState state = projectStateFromProject(ProjectMetadata.builder(randomProjectIdOrDefault()).put(indexMetadata, true));
        Exception e = expectThrows(IllegalArgumentException.class, () -> performActionAndWait(step, indexMetadata, state, null));
        assertThat(
            e.getMessage(),
            Matchers.is(
                String.format(
                    Locale.ROOT,
                    "%s [%s] does not point to index [%s]",
                    RolloverAction.LIFECYCLE_ROLLOVER_ALIAS,
                    alias,
                    indexMetadata.getIndex().getName()
                )
            )
        );
    }
}
