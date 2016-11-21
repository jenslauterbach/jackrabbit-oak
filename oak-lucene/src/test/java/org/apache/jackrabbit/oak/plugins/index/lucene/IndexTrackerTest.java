/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.plugins.index.lucene;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.ImmutableSet;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.commons.PathUtils;
import org.apache.jackrabbit.oak.plugins.index.IndexUpdateProvider;
import org.apache.jackrabbit.oak.plugins.memory.ArrayBasedBlob;
import org.apache.jackrabbit.oak.plugins.memory.PropertyStates;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EditorHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.plugins.index.lucene.BadIndexTracker.BadIndexInfo;
import org.junit.Test;

import static org.apache.jackrabbit.oak.plugins.index.IndexConstants.INDEX_DEFINITIONS_NAME;
import static org.apache.jackrabbit.oak.plugins.index.lucene.util.LuceneIndexHelper.newLucenePropertyIndexDefinition;
import static org.apache.jackrabbit.oak.plugins.nodetype.write.InitialContent.INITIAL_CONTENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@SuppressWarnings("UnusedAssignment")
public class IndexTrackerTest {
    private static final EditorHook HOOK = new EditorHook(
            new IndexUpdateProvider(
                    new LuceneIndexEditorProvider()));

    private NodeState root = INITIAL_CONTENT;

    private NodeBuilder builder = root.builder();

    private IndexTracker tracker = new IndexTracker();

    @Test
    public void update() throws Exception{
        NodeBuilder index = builder.child(INDEX_DEFINITIONS_NAME);
        newLucenePropertyIndexDefinition(index, "lucene", ImmutableSet.of("foo"), null);

        NodeState before = builder.getNodeState();
        builder.setProperty("foo", "bar");
        NodeState after = builder.getNodeState();

        NodeState indexed = HOOK.processCommit(before, after, CommitInfo.EMPTY);

        assertEquals(0, tracker.getIndexNodePaths().size());

        tracker.update(indexed);
        IndexNode indexNode = tracker.acquireIndexNode("/oak:index/lucene");
        indexNode.release();
        assertEquals(1, tracker.getIndexNodePaths().size());

        tracker.refresh();
        assertEquals(1, tracker.getIndexNodePaths().size());

        tracker.update(indexed);
        //Post refresh size should be 0 as all are closed
        assertEquals(0, tracker.getIndexNodePaths().size());
    }

    @Test
    public void badIndexAccess() throws Exception{
        createIndex("foo");

        //1. Create and populate index
        NodeState before = builder.getNodeState();
        builder.setProperty("foo", "bar");
        NodeState after = builder.getNodeState();

        NodeState indexed = HOOK.processCommit(before, after, CommitInfo.EMPTY);
        tracker.update(indexed);

        IndexNode indexNode = tracker.acquireIndexNode("/oak:index/foo");
        indexNode.release();

        assertTrue(tracker.getBadIndexTracker().getIndexPaths().isEmpty());

        //2. Corrupt the index
        builder = indexed.builder();
        indexed = corruptIndex("/oak:index/foo");

        tracker.update(indexed);
        indexNode = tracker.acquireIndexNode("/oak:index/foo");
        //Even if the persisted index is corrupted the index should be accessible
        //as update would have failed so old copy would be used
        assertNotNull(indexNode);
        assertFalse(tracker.getBadIndexTracker().getBadPersistedIndexPaths().isEmpty());



        //3. Recreate the tracker as we cannot push corrupt index in existing tracker
        //As diffAndUpdate would fail and existing IndexNode would not be changed
        tracker = new IndexTracker();
        tracker.update(indexed);

        VirtualTicker ticker = new VirtualTicker();
        tracker.getBadIndexTracker().setTicker(ticker);

        indexNode = tracker.acquireIndexNode("/oak:index/foo");

        //Index must be corrupted hence it must be null
        assertNull(indexNode);
        assertTrue(tracker.getBadIndexTracker().getIndexPaths().contains("/oak:index/foo"));

        BadIndexInfo badIdxInfo = tracker.getBadIndexTracker().getInfo("/oak:index/foo");
        assertNotNull(badIdxInfo);
        assertEquals(0, badIdxInfo.getAccessCount());

        //Try to access again
        indexNode = tracker.acquireIndexNode("/oak:index/foo");
        assertEquals(1, badIdxInfo.getAccessCount());
        assertEquals(0, badIdxInfo.getFailedAccessCount());

        indexNode = tracker.acquireIndexNode("/oak:index/foo");
        assertEquals(2, badIdxInfo.getAccessCount());
        assertEquals(0, badIdxInfo.getFailedAccessCount());

        //5. Move clock forward
        ticker.addTime(tracker.getBadIndexTracker().getRecheckIntervalMillis() + 1, TimeUnit.MILLISECONDS);

        //Now index access must be attempted again
        indexNode = tracker.acquireIndexNode("/oak:index/foo");
        assertEquals(3, badIdxInfo.getAccessCount());
        assertEquals(1, badIdxInfo.getFailedAccessCount());

        //6. Now lets reindex to fix the corruption
        builder = indexed.builder();
        before = indexed;
        after = reindex("/oak:index/foo");

        indexed = HOOK.processCommit(before, after, CommitInfo.EMPTY);
        tracker.update(indexed);

        //7. Now indexNode should be accessible
        indexNode = tracker.acquireIndexNode("/oak:index/foo");
        assertNotNull(indexNode);

        //And this index would not be considered bad
        badIdxInfo = tracker.getBadIndexTracker().getInfo("/oak:index/foo");
        assertNull(badIdxInfo);
    }

    private NodeState corruptIndex(String indexPath) {
        NodeBuilder dir = TestUtil.child(builder, PathUtils.concat(indexPath, ":data"));
        for (String name : dir.getChildNodeNames()){
            if (!"segments.gen".equals(name)){
                dir.getChildNode(name).setProperty(PropertyStates.createProperty("jcr:data", Collections
                        .singletonList(new ArrayBasedBlob("foo".getBytes())), Type.BINARIES));
            }
        }
        return builder.getNodeState();
    }

    private NodeState reindex(String indexPath){
        NodeBuilder dir = TestUtil.child(builder, indexPath);
        dir.setProperty("reindex", true);
        return builder.getNodeState();
    }

    private void createIndex(String propName){
        NodeBuilder index = builder.child(INDEX_DEFINITIONS_NAME);
        newLucenePropertyIndexDefinition(index, propName, ImmutableSet.of(propName), null);
    }

}