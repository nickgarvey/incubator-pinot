/**
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
package org.apache.pinot.controller.helix.core.realtime.segment;

import javax.annotation.Nullable;
import org.apache.pinot.common.metadata.segment.LLCRealtimeSegmentZKMetadata;
import org.apache.pinot.core.realtime.stream.PartitionLevelStreamConfig;


/**
 * Interface for the flush threshold updating strategies
 * These implementations are responsible for updating the flush threshold (rows/time) for the given segment ZK metadata
 */
public interface FlushThresholdUpdater {

  /**
   * Updates the flush threshold for the given segment ZK metadata
   */
  void updateFlushThreshold(PartitionLevelStreamConfig streamConfig, LLCRealtimeSegmentZKMetadata newSegmentZKMetadata,
      CommittingSegmentDescriptor committingSegmentDescriptor,
      @Nullable LLCRealtimeSegmentZKMetadata committingSegmentZKMetadata, int maxNumPartitionsPerInstance);
}
