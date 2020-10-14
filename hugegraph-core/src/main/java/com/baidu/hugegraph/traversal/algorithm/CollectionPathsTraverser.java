/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.traversal.algorithm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.slf4j.Logger;

import com.baidu.hugegraph.HugeGraph;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.structure.HugeVertex;
import com.baidu.hugegraph.traversal.algorithm.strategy.TraverseStrategy;
import com.baidu.hugegraph.util.E;
import com.baidu.hugegraph.util.Log;
import com.google.common.collect.ImmutableList;

public class CollectionPathsTraverser extends TpTraverser {

    private static final Logger LOG = Log.logger(CollectionPathsTraverser.class);

    public CollectionPathsTraverser(HugeGraph graph) {
        super(graph, "collection-paths");
    }

    public Collection<Path> paths(Iterator<Vertex> sources,
                                  Iterator<Vertex> targets,
                                  EdgeStep step, int depth, boolean nearest,
                                  long capacity, long limit) {
        checkCapacity(capacity);
        checkLimit(limit);

        List<Id> sourceList = new ArrayList<>();
        while (sources.hasNext()) {
            sourceList.add(((HugeVertex) sources.next()).id());
        }
        int sourceSize = sourceList.size();
        E.checkState(sourceSize >= 1 && sourceSize <= MAX_VERTICES,
                     "The number of source vertices must in [1, %s], " +
                     "but got: %s", MAX_VERTICES, sourceList.size());
        List<Id> targetList = new ArrayList<>();
        while (targets.hasNext()) {
            targetList.add(((HugeVertex) targets.next()).id());
        }
        int targetSize = targetList.size();
        E.checkState(targetSize >= 1 && targetSize <= MAX_VERTICES,
                     "The number of target vertices must in [1, %s], " +
                     "but got: %s", MAX_VERTICES, sourceList.size());
        checkPositive(depth, "max depth");

        Traverser traverser;
        TraverseStrategy strategy = this.traverseStrategy(
                                    depth >= this.concurrentDepth());
        if (nearest) {
            traverser = new NearestTraverser(sourceList, targetList, step,
                                             depth, capacity, limit, strategy);
        } else {
            traverser = new Traverser(sourceList, targetList, step,
                                      depth, capacity, limit, strategy);
        }

        do {
            // Forward
            traverser.forward();
            if (traverser.finish()) {
                return traverser.paths();
            }

            // Backward
            traverser.backward();
            if (traverser.finish()) {
                return traverser.paths();
            }
        } while (true);
    }

    private class Traverser extends PathTraverser {

        protected final EdgeStep step;

        public Traverser(Collection<Id> sources, Collection<Id> targets,
                         EdgeStep step, int depth, long capacity,
                         long limit, TraverseStrategy strategy) {
            super(sources, targets, capacity, limit, strategy);
            this.step = step;
            this.totalSteps = depth;
        }

        @Override
        public EdgeStep nextStep(boolean forward) {
            return this.step;
        }

        @Override
        protected void processOneForForward(Id sourceV, Id targetV) {
            for (Node source : this.sources.get(sourceV)) {
                // If have loop, skip target
                if (source.contains(targetV)) {
                    continue;
                }

                // If cross point exists, path found, concat them
                if (this.targetsAll.containsKey(targetV)) {
                    for (Node target : this.targetsAll.get(targetV)) {
                        List<Id> path = source.joinPath(target);
                        if (!path.isEmpty()) {
                            this.paths.add(new Path(targetV, path));
                            if (this.reachLimit()) {
                                return;
                            }
                        }
                    }
                }

                // Add node to next start-nodes
                this.addNodeToNewVertices(targetV, new Node(targetV, source));
            }
        }

        @Override
        protected void processOneForBackward(Id sourceV, Id targetV) {
            for (Node source : this.targets.get(sourceV)) {
                // If have loop, skip target
                if (source.contains(targetV)) {
                    continue;
                }

                // If cross point exists, path found, concat them
                if (this.sourcesAll.containsKey(targetV)) {
                    for (Node target : this.sourcesAll.get(targetV)) {
                        List<Id> path = source.joinPath(target);
                        if (!path.isEmpty()) {
                            Path newPath = new Path(targetV, path);
                            newPath.reverse();
                            this.paths.add(newPath);
                            if (this.reachLimit()) {
                                return;
                            }
                        }
                    }
                }

                // Add node to next start-nodes
                this.addNodeToNewVertices(targetV, new Node(targetV, source));
            }
        }

        @Override
        protected void reInitCurrentStepIfNeeded(EdgeStep step,
                                                 boolean forward) {
            if (forward) {
                // Re-init sources
                this.sources = this.newVertices;
                // Record all passed vertices
                this.addNewVerticesToAll(this.sourcesAll);
            } else {
                // Re-init targets
                this.targets = this.newVertices;
                // Record all passed vertices
                this.addNewVerticesToAll(this.targetsAll);
            }
        }
    }

    private class NearestTraverser extends Traverser {

        public NearestTraverser(Collection<Id> sources,
                                Collection<Id> targets, EdgeStep step,
                                int depth, long capacity, long limit,
                                TraverseStrategy strategy) {
            super(sources, targets, step, depth, capacity, limit, strategy);
        }

        @Override
        protected void processOneForForward(Id sourceV, Id targetV) {
            Node source = this.sources.get(sourceV).get(0);
            // If have loop, skip target
            if (source.contains(targetV)) {
                return;
            }

            // If cross point exists, path found, concat them
            if (this.targetsAll.containsKey(targetV)) {
                Node node = this.targetsAll.get(targetV).get(0);
                List<Id> path = source.joinPath(node);
                if (!path.isEmpty()) {
                    this.paths.add(new Path(targetV, path));
                    if (this.reachLimit()) {
                        return;
                    }
                }
            }

            // Add node to next start-nodes
            this.addNodeToNewVertices(targetV, new Node(targetV, source));
        }

        @Override
        protected void processOneForBackward(Id sourceV, Id targetV) {
            Node sourcee = this.targets.get(sourceV).get(0);
            // If have loop, skip target
            if (sourcee.contains(targetV)) {
                return;
            }

            // If cross point exists, path found, concat them
            if (this.sourcesAll.containsKey(targetV)) {
                Node node = this.sourcesAll.get(targetV).get(0);
                List<Id> path = sourcee.joinPath(node);
                if (!path.isEmpty()) {
                    Path newPath = new Path(targetV, path);
                    newPath.reverse();
                    this.paths.add(newPath);
                    if (this.reachLimit()) {
                        return;
                    }
                }
            }

            // Add node to next start-nodes
            this.addNodeToNewVertices(targetV, new Node(targetV, sourcee));
        }

        @Override
        protected void reInitCurrentStepIfNeeded(EdgeStep step,
                                                 boolean forward) {
            if (forward) {
                // Re-init targets
                this.sources = this.newVertices;
                // Record all passed vertices
                this.addNewVerticesToAll(this.sourcesAll);
            } else {
                // Re-init targets
                this.targets = this.newVertices;
                // Record all passed vertices
                this.addNewVerticesToAll(this.targetsAll);
            }
        }

        @Override
        public void addNodeToNewVertices(Id id, Node node) {
            this.newVertices.putIfAbsent(id, ImmutableList.of(node));
        }

        @Override
        public void addNewVerticesToAll(Map<Id, List<Node>> targets) {
            for (Map.Entry<Id, List<Node>> entry : this.newVertices.entrySet()) {
                targets.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        protected int accessedNodes() {
            return this.sourcesAll.size() + this.targetsAll.size();
        }
    }
}