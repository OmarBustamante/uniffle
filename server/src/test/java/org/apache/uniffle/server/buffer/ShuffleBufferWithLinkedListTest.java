/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.uniffle.server.buffer;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.collect.Lists;
import org.junit.jupiter.api.Test;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import org.apache.uniffle.common.BufferSegment;
import org.apache.uniffle.common.ShuffleDataDistributionType;
import org.apache.uniffle.common.ShuffleDataResult;
import org.apache.uniffle.common.ShufflePartitionedBlock;
import org.apache.uniffle.common.ShufflePartitionedData;
import org.apache.uniffle.common.util.ByteBufUtils;
import org.apache.uniffle.common.util.Constants;
import org.apache.uniffle.server.ShuffleDataFlushEvent;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ShuffleBufferWithLinkedListTest extends BufferTestBase {

  private static AtomicInteger atomSequenceNo = new AtomicInteger(0);

  @Test
  public void appendTest() {
    ShuffleBuffer shuffleBuffer = createShuffleBuffer();
    shuffleBuffer.append(createData(10));
    // ShufflePartitionedBlock has constant 32 bytes overhead
    assertEquals(42, shuffleBuffer.getEncodedLength());

    shuffleBuffer.append(createData(26));
    assertEquals(100, shuffleBuffer.getEncodedLength());

    shuffleBuffer.append(createData(1));
    assertEquals(133, shuffleBuffer.getEncodedLength());
  }

  @Test
  public void appendMultiBlocksTest() {
    ShuffleBuffer shuffleBuffer = createShuffleBuffer();
    ShufflePartitionedData data1 = createData(10);
    ShufflePartitionedData data2 = createData(10);
    ShufflePartitionedBlock[] dataCombine = new ShufflePartitionedBlock[2];
    dataCombine[0] = data1.getBlockList()[0];
    dataCombine[1] = data2.getBlockList()[0];
    shuffleBuffer.append(new ShufflePartitionedData(1, dataCombine));
    assertEquals(84, shuffleBuffer.getEncodedLength());
  }

  @Test
  public void toFlushEventTest() {
    ShuffleBuffer shuffleBuffer = createShuffleBuffer();
    ShuffleDataFlushEvent event = shuffleBuffer.toFlushEvent("appId", 0, 0, 1, null);
    assertNull(event);
    shuffleBuffer.append(createData(10));
    assertEquals(42, shuffleBuffer.getEncodedLength());
    event = shuffleBuffer.toFlushEvent("appId", 0, 0, 1, null);
    assertEquals(42, event.getEncodedLength());
    assertEquals(10, event.getDataLength());
    shuffleBuffer.append(createData(10));
    event = shuffleBuffer.toFlushEvent("appId", 0, 0, 1, null);
    assertEquals(42, event.getEncodedLength());
    assertEquals(10, event.getDataLength());
    assertEquals(0, shuffleBuffer.getEncodedLength());
    assertEquals(0, shuffleBuffer.getBlocks().size());
  }

  @Test
  public void getShuffleDataWithExpectedTaskIdsFilterTest() {
    /** case1: all blocks in cached(or in flushed map) and size < readBufferSize */
    ShuffleBuffer shuffleBuffer = createShuffleBuffer();
    ShufflePartitionedData spd1 = createData(1, 1, 15);
    ShufflePartitionedData spd2 = createData(1, 0, 15);
    ShufflePartitionedData spd3 = createData(1, 2, 55);
    ShufflePartitionedData spd4 = createData(1, 1, 45);
    shuffleBuffer.append(spd1);
    shuffleBuffer.append(spd2);
    shuffleBuffer.append(spd3);
    shuffleBuffer.append(spd4);

    Roaring64NavigableMap expectedTasks = Roaring64NavigableMap.bitmapOf(1, 2);
    ShuffleDataResult result =
        shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 1000, expectedTasks);
    assertEquals(3, result.getBufferSegments().size());
    for (BufferSegment segment : result.getBufferSegments()) {
      assertTrue(expectedTasks.contains(segment.getTaskAttemptId()));
    }
    assertEquals(0, result.getBufferSegments().get(0).getOffset());
    assertEquals(15, result.getBufferSegments().get(0).getLength());
    assertEquals(15, result.getBufferSegments().get(1).getOffset());
    assertEquals(55, result.getBufferSegments().get(1).getLength());
    assertEquals(70, result.getBufferSegments().get(2).getOffset());
    assertEquals(45, result.getBufferSegments().get(2).getLength());

    expectedTasks = Roaring64NavigableMap.bitmapOf(0);
    result = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 1000, expectedTasks);
    assertEquals(1, result.getBufferSegments().size());
    assertEquals(15, result.getBufferSegments().get(0).getLength());

    /**
     * case2: all blocks in cached(or in flushed map) and size > readBufferSize, so it will read
     * multiple times.
     *
     * <p>required blocks size list: 15, 55, 45
     */
    expectedTasks = Roaring64NavigableMap.bitmapOf(1, 2);
    result = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 60, expectedTasks);
    assertEquals(2, result.getBufferSegments().size());
    assertEquals(0, result.getBufferSegments().get(0).getOffset());
    assertEquals(15, result.getBufferSegments().get(0).getLength());
    assertEquals(15, result.getBufferSegments().get(1).getOffset());
    assertEquals(55, result.getBufferSegments().get(1).getLength());

    // 2nd read
    long lastBlockId = result.getBufferSegments().get(1).getBlockId();
    result = shuffleBuffer.getShuffleData(lastBlockId, 60, expectedTasks);
    assertEquals(1, result.getBufferSegments().size());
    assertEquals(0, result.getBufferSegments().get(0).getOffset());
    assertEquals(45, result.getBufferSegments().get(0).getLength());

    /** case3: all blocks in flushed map and size < readBufferSize */
    expectedTasks = Roaring64NavigableMap.bitmapOf(1, 2);
    ShuffleDataFlushEvent event1 =
        shuffleBuffer.toFlushEvent("appId", 0, 0, 1, null, ShuffleDataDistributionType.LOCAL_ORDER);
    result = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 1000, expectedTasks);
    assertEquals(3, result.getBufferSegments().size());
    for (BufferSegment segment : result.getBufferSegments()) {
      assertTrue(expectedTasks.contains(segment.getTaskAttemptId()));
    }
    assertEquals(0, result.getBufferSegments().get(0).getOffset());
    assertEquals(15, result.getBufferSegments().get(0).getLength());
    assertEquals(15, result.getBufferSegments().get(1).getOffset());
    assertEquals(55, result.getBufferSegments().get(1).getLength());
    assertEquals(70, result.getBufferSegments().get(2).getOffset());
    assertEquals(45, result.getBufferSegments().get(2).getLength());

    /** case4: all blocks in flushed map and size > readBufferSize, it will read multiple times */
    expectedTasks = Roaring64NavigableMap.bitmapOf(1, 2);
    result = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 60, expectedTasks);
    assertEquals(2, result.getBufferSegments().size());
    assertEquals(0, result.getBufferSegments().get(0).getOffset());
    assertEquals(15, result.getBufferSegments().get(0).getLength());
    assertEquals(15, result.getBufferSegments().get(1).getOffset());
    assertEquals(55, result.getBufferSegments().get(1).getLength());

    // 2nd read
    lastBlockId = result.getBufferSegments().get(1).getBlockId();
    result = shuffleBuffer.getShuffleData(lastBlockId, 60, expectedTasks);
    assertEquals(1, result.getBufferSegments().size());
    assertEquals(0, result.getBufferSegments().get(0).getOffset());
    assertEquals(45, result.getBufferSegments().get(0).getLength());

    /**
     * case5: partial blocks in cache and another in flushedMap, and it will read multiple times.
     *
     * <p>required size: 15, 55, 45 (in flushed map) 55, 45, 5, 25(in cached)
     */
    ShufflePartitionedData spd5 = createData(1, 2, 55);
    ShufflePartitionedData spd6 = createData(1, 1, 45);
    ShufflePartitionedData spd7 = createData(1, 1, 5);
    ShufflePartitionedData spd8 = createData(1, 1, 25);
    shuffleBuffer.append(spd5);
    shuffleBuffer.append(spd6);
    shuffleBuffer.append(spd7);
    shuffleBuffer.append(spd8);

    expectedTasks = Roaring64NavigableMap.bitmapOf(1, 2);
    result = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 60, expectedTasks);
    assertEquals(2, result.getBufferSegments().size());

    // 2nd read
    lastBlockId = result.getBufferSegments().get(1).getBlockId();
    result = shuffleBuffer.getShuffleData(lastBlockId, 60, expectedTasks);
    assertEquals(2, result.getBufferSegments().size());
    // 3rd read
    lastBlockId = result.getBufferSegments().get(1).getBlockId();
    result = shuffleBuffer.getShuffleData(lastBlockId, 60, expectedTasks);
    assertEquals(3, result.getBufferSegments().size());
  }

  @Test
  public void getShuffleDataWithLocalOrderTest() {
    ShuffleBuffer shuffleBuffer = createShuffleBuffer();
    ShufflePartitionedData spd1 = createData(1, 1, 15);
    ShufflePartitionedData spd2 = createData(1, 0, 15);
    ShufflePartitionedData spd3 = createData(1, 2, 15);
    final byte[] expectedData = getExpectedData(spd1, spd2);
    final byte[] expectedData2 = getExpectedData(spd3);
    shuffleBuffer.append(spd1);
    shuffleBuffer.append(spd2);
    shuffleBuffer.append(spd3);

    // First read from the cached data
    ShuffleDataResult sdr = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 16);
    compareBufferSegment(
        new LinkedList<>(shuffleBuffer.getBlocks()), sdr.getBufferSegments(), 0, 2);
    assertArrayEquals(expectedData, sdr.getData());

    // Second read after flushed
    ShuffleDataFlushEvent event1 =
        shuffleBuffer.toFlushEvent("appId", 0, 0, 1, null, ShuffleDataDistributionType.LOCAL_ORDER);
    long lastBlockId = sdr.getBufferSegments().get(1).getBlockId();
    sdr = shuffleBuffer.getShuffleData(lastBlockId, 16);
    compareBufferSegment(
        new LinkedList<>(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId())),
        sdr.getBufferSegments(),
        2,
        1);
    assertArrayEquals(expectedData2, sdr.getData());
    Iterator<ShufflePartitionedBlock> it = event1.getShuffleBlocks().iterator();
    assertEquals(0, it.next().getTaskAttemptId());
    assertEquals(1, it.next().getTaskAttemptId());
    assertEquals(2, it.next().getTaskAttemptId());

    assertEquals(
        1,
        new LinkedList<>(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()))
            .get(0)
            .getTaskAttemptId());
    assertEquals(
        0,
        new LinkedList<>(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()))
            .get(1)
            .getTaskAttemptId());
    assertEquals(
        2,
        new LinkedList<>(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()))
            .get(2)
            .getTaskAttemptId());
  }

  @Test
  public void getShuffleDataTest() {
    ShuffleBuffer shuffleBuffer = createShuffleBuffer();
    // case1: cached data only, blockId = -1, readBufferSize > buffer size
    ShufflePartitionedData spd1 = createData(10);
    ShufflePartitionedData spd2 = createData(20);
    final byte[] expectedData = getExpectedData(spd1, spd2);
    shuffleBuffer.append(spd1);
    shuffleBuffer.append(spd2);
    ShuffleDataResult sdr = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 40);
    compareBufferSegment(
        new LinkedList<>(shuffleBuffer.getBlocks()), sdr.getBufferSegments(), 0, 2);
    assertArrayEquals(expectedData, sdr.getData());

    // case2: cached data only, blockId = -1, readBufferSize = buffer size
    shuffleBuffer = createShuffleBuffer();
    spd1 = createData(20);
    spd2 = createData(20);
    final byte[] expectedData2 = getExpectedData(spd1, spd2);
    shuffleBuffer.append(spd1);
    shuffleBuffer.append(spd2);
    sdr = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 40);
    compareBufferSegment(
        new LinkedList<>(shuffleBuffer.getBlocks()), sdr.getBufferSegments(), 0, 2);
    assertArrayEquals(expectedData2, sdr.getData());

    // case3-1: cached data only, blockId = -1, readBufferSize < buffer size
    shuffleBuffer = createShuffleBuffer();
    spd1 = createData(20);
    spd2 = createData(21);
    final byte[] expectedData3 = getExpectedData(spd1, spd2);
    shuffleBuffer.append(spd1);
    shuffleBuffer.append(spd2);
    sdr = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 40);
    compareBufferSegment(
        new LinkedList<>(shuffleBuffer.getBlocks()), sdr.getBufferSegments(), 0, 2);
    assertArrayEquals(expectedData3, sdr.getData());

    // case3-2: cached data only, blockId = -1, readBufferSize < buffer size
    shuffleBuffer = createShuffleBuffer();
    spd1 = createData(15);
    spd2 = createData(15);
    ShufflePartitionedData spd3 = createData(15);
    final byte[] expectedData4 = getExpectedData(spd1, spd2);
    final byte[] expectedData5 = getExpectedData(spd3);
    shuffleBuffer.append(spd1);
    shuffleBuffer.append(spd2);
    shuffleBuffer.append(spd3);
    sdr = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 25);
    compareBufferSegment(
        new LinkedList<>(shuffleBuffer.getBlocks()), sdr.getBufferSegments(), 0, 2);
    assertArrayEquals(expectedData4, sdr.getData());

    // case4: cached data only, blockId != -1 && exist, readBufferSize < buffer size
    long lastBlockId = spd2.getBlockList()[0].getBlockId();
    sdr = shuffleBuffer.getShuffleData(lastBlockId, 25);
    compareBufferSegment(
        new LinkedList<>(shuffleBuffer.getBlocks()), sdr.getBufferSegments(), 2, 1);
    assertArrayEquals(expectedData5, sdr.getData());

    // case5: flush data only, blockId = -1, readBufferSize < buffer size
    shuffleBuffer = createShuffleBuffer();
    spd1 = createData(15);
    spd2 = createData(15);
    final byte[] expectedData6 = getExpectedData(spd1, spd2);
    shuffleBuffer.append(spd1);
    shuffleBuffer.append(spd2);
    ShuffleDataFlushEvent event1 = shuffleBuffer.toFlushEvent("appId", 0, 0, 1, null);
    assertEquals(0, shuffleBuffer.getBlocks().size());
    sdr = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 20);
    compareBufferSegment(
        new LinkedList<>(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId())),
        sdr.getBufferSegments(),
        0,
        2);
    assertArrayEquals(expectedData6, sdr.getData());

    // case5: flush data only, blockId = lastBlockId
    sdr = shuffleBuffer.getShuffleData(spd2.getBlockList()[0].getBlockId(), 20);
    assertEquals(0, sdr.getBufferSegments().size());

    // case6: no data in buffer & flush buffer
    shuffleBuffer = createShuffleBuffer();
    sdr = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 10);
    assertEquals(0, sdr.getBufferSegments().size());
    assertEquals(0, sdr.getDataLength());

    // case7: get data with multiple flush buffer and cached buffer
    shuffleBuffer = createShuffleBuffer();
    spd1 = createData(15);
    spd2 = createData(15);
    spd3 = createData(15);
    final ShufflePartitionedData spd4 = createData(15);
    final ShufflePartitionedData spd5 = createData(15);
    final ShufflePartitionedData spd6 = createData(15);
    final ShufflePartitionedData spd7 = createData(15);
    final ShufflePartitionedData spd8 = createData(15);
    final ShufflePartitionedData spd9 = createData(15);
    final ShufflePartitionedData spd10 = createData(15);
    final ShufflePartitionedData spd11 = createData(15);
    final ShufflePartitionedData spd12 = createData(15);
    final ShufflePartitionedData spd13 = createData(15);
    final ShufflePartitionedData spd14 = createData(15);
    final ShufflePartitionedData spd15 = createData(15);
    final byte[] expectedData7 = getExpectedData(spd1);
    final byte[] expectedData8 = getExpectedData(spd2);
    final byte[] expectedData9 = getExpectedData(spd1, spd2);
    final byte[] expectedData10 = getExpectedData(spd2, spd3);
    final byte[] expectedData11 = getExpectedData(spd1, spd2, spd3, spd4);
    final byte[] expectedData12 = getExpectedData(spd4);
    final byte[] expectedData13 = getExpectedData(spd6);
    final byte[] expectedData14 = getExpectedData(spd4, spd5, spd6);
    final byte[] expectedData15 = getExpectedData(spd3, spd4, spd5, spd6, spd7);
    final byte[] expectedData16 = getExpectedData(spd6, spd7);
    final byte[] expectedData17 = getExpectedData(spd6, spd7, spd8, spd9);
    final byte[] expectedData18 = getExpectedData(spd9, spd10);
    final byte[] expectedData19 = getExpectedData(spd10);
    final byte[] expectedData20 = getExpectedData(spd12);
    final byte[] expectedData21 = getExpectedData(spd10, spd11, spd12);
    final byte[] expectedData22 = getExpectedData(spd12, spd13);
    final byte[] expectedData23 = getExpectedData(spd13);
    final byte[] expectedData24 = getExpectedData(spd14, spd15);
    final byte[] expectedData25 = getExpectedData(spd15);
    final byte[] expectedData26 = getExpectedData(spd12, spd13, spd14, spd15);
    final byte[] expectedData27 =
        getExpectedData(
            spd1, spd2, spd3, spd4, spd5, spd6, spd7, spd8, spd9, spd10, spd11, spd12, spd13, spd14,
            spd15);
    shuffleBuffer.append(spd1);
    shuffleBuffer.append(spd2);
    shuffleBuffer.append(spd3);
    event1 = shuffleBuffer.toFlushEvent("appId", 0, 0, 1, null);

    shuffleBuffer.append(spd4);
    shuffleBuffer.append(spd5);
    shuffleBuffer.append(spd6);

    final ShuffleDataFlushEvent event2 = shuffleBuffer.toFlushEvent("appId", 0, 0, 1, null);

    shuffleBuffer.append(spd7);
    shuffleBuffer.append(spd8);
    shuffleBuffer.append(spd9);
    final ShuffleDataFlushEvent event3 = shuffleBuffer.toFlushEvent("appId", 0, 0, 1, null);
    shuffleBuffer.append(spd10);
    shuffleBuffer.append(spd11);
    shuffleBuffer.append(spd12);
    final ShuffleDataFlushEvent event4 = shuffleBuffer.toFlushEvent("appId", 0, 0, 1, null);
    shuffleBuffer.append(spd13);
    shuffleBuffer.append(spd14);
    shuffleBuffer.append(spd15);
    assertEquals(3, shuffleBuffer.getBlocks().size());
    assertEquals(4, shuffleBuffer.getInFlushBlockMap().size());

    // all data in shuffle buffer are as following:
    // flush event1 -> spd1, spd2, spd3
    // flush event2 -> spd4, spd5, spd6
    // flush event3 -> spd7, spd8, spd9
    // flush event3 -> spd10, spd11, spd12
    // cached buffer -> spd13, spd14, spd15
    // case7 to get spd1
    List<ShufflePartitionedBlock> expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()));
    sdr = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 10);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 0, 1);
    assertArrayEquals(expectedData7, sdr.getData());

    // case7 to get spd2
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd1.getBlockList()[0].getBlockId(), 10);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 1, 1);
    assertArrayEquals(expectedData8, sdr.getData());

    // case7 to get spd1, spd2
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()));
    sdr = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 20);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 0, 2);
    assertArrayEquals(expectedData9, sdr.getData());

    // case7 to get spd2, spd3
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd1.getBlockList()[0].getBlockId(), 20);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 1, 2);
    assertArrayEquals(expectedData10, sdr.getData());

    // case7 to get spd1, spd2, spd3, spd4
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getInFlushBlockMap().get(event2.getEventId()));
    sdr = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 50);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 0, 4);
    assertArrayEquals(expectedData11, sdr.getData());

    // case7 to get spd2, spd3
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getInFlushBlockMap().get(event2.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd1.getBlockList()[0].getBlockId(), 20);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 1, 2);
    assertArrayEquals(expectedData10, sdr.getData());

    // case7 to get spd4
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event2.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd3.getBlockList()[0].getBlockId(), 10);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 0, 1);
    assertArrayEquals(expectedData12, sdr.getData());

    // case7 to get spd6
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event2.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd5.getBlockList()[0].getBlockId(), 10);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 2, 1);
    assertArrayEquals(expectedData13, sdr.getData());

    // case7 to get spd4, spd5, spd6
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event2.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd3.getBlockList()[0].getBlockId(), 40);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 0, 3);
    assertArrayEquals(expectedData14, sdr.getData());

    // case7 to get spd3, spd4, spd5, spd6, spd7
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getInFlushBlockMap().get(event2.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getInFlushBlockMap().get(event3.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd2.getBlockList()[0].getBlockId(), 70);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 2, 5);
    assertArrayEquals(expectedData15, sdr.getData());

    // case7 to get spd6, spd7
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event2.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getInFlushBlockMap().get(event3.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd5.getBlockList()[0].getBlockId(), 20);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 2, 2);
    assertArrayEquals(expectedData16, sdr.getData());

    // case7 to get spd6, spd7, spd8, spd9
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event2.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getInFlushBlockMap().get(event3.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd5.getBlockList()[0].getBlockId(), 50);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 2, 4);
    assertArrayEquals(expectedData17, sdr.getData());

    // case7 to get spd9, spd10
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event3.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getInFlushBlockMap().get(event4.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd8.getBlockList()[0].getBlockId(), 20);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 2, 2);
    assertArrayEquals(expectedData18, sdr.getData());

    // case7 to get spd10
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event4.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd9.getBlockList()[0].getBlockId(), 10);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 0, 1);
    assertArrayEquals(expectedData19, sdr.getData());

    // case7 to get spd12
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event4.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd11.getBlockList()[0].getBlockId(), 10);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 2, 1);
    assertArrayEquals(expectedData20, sdr.getData());

    // case7 to get spd10, spd11, spd12
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event4.getEventId()));
    sdr = shuffleBuffer.getShuffleData(spd9.getBlockList()[0].getBlockId(), 40);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 0, 3);
    assertArrayEquals(expectedData21, sdr.getData());

    // case7 to get spd12, spd13
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event4.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getBlocks());
    sdr = shuffleBuffer.getShuffleData(spd11.getBlockList()[0].getBlockId(), 20);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 2, 2);
    assertArrayEquals(expectedData22, sdr.getData());

    // case7 to get spd13
    expectedBlocks = Lists.newArrayList(shuffleBuffer.getBlocks());
    expectedBlocks.addAll(shuffleBuffer.getBlocks());
    sdr = shuffleBuffer.getShuffleData(spd12.getBlockList()[0].getBlockId(), 10);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 0, 1);
    assertArrayEquals(expectedData23, sdr.getData());

    // case7 to get spd14, spd15
    expectedBlocks = Lists.newArrayList(shuffleBuffer.getBlocks());
    expectedBlocks.addAll(shuffleBuffer.getBlocks());
    sdr = shuffleBuffer.getShuffleData(spd13.getBlockList()[0].getBlockId(), 20);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 1, 2);
    assertArrayEquals(expectedData24, sdr.getData());

    // case7 to get spd15
    expectedBlocks = Lists.newArrayList(shuffleBuffer.getBlocks());
    expectedBlocks.addAll(shuffleBuffer.getBlocks());
    sdr = shuffleBuffer.getShuffleData(spd14.getBlockList()[0].getBlockId(), 10);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 2, 1);
    assertArrayEquals(expectedData25, sdr.getData());

    // case7 to get spd12, spd13, spd14, spd15
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event4.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getBlocks());
    sdr = shuffleBuffer.getShuffleData(spd11.getBlockList()[0].getBlockId(), 50);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 2, 4);
    assertArrayEquals(expectedData26, sdr.getData());

    // case7 to get spd1 - spd15
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getInFlushBlockMap().get(event2.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getInFlushBlockMap().get(event3.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getInFlushBlockMap().get(event4.getEventId()));
    expectedBlocks.addAll(shuffleBuffer.getBlocks());
    sdr = shuffleBuffer.getShuffleData(Constants.INVALID_BLOCK_ID, 220);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 0, 15);
    assertArrayEquals(expectedData27, sdr.getData());

    // case7 after get spd15
    sdr = shuffleBuffer.getShuffleData(spd15.getBlockList()[0].getBlockId(), 20);
    assertEquals(0, sdr.getBufferSegments().size());

    // case7 can't find blockId, read from start
    expectedBlocks =
        Lists.newArrayList(shuffleBuffer.getInFlushBlockMap().get(event1.getEventId()));
    sdr = shuffleBuffer.getShuffleData(-200, 20);
    compareBufferSegment(expectedBlocks, sdr.getBufferSegments(), 0, 2);
    assertArrayEquals(expectedData9, sdr.getData());
  }

  @Test
  public void appendRepeatBlockTest() {
    ShuffleBuffer shuffleBuffer = createShuffleBuffer();
    ShufflePartitionedData block = createData(10);
    shuffleBuffer.append(block);
    // ShufflePartitionedBlock has constant 32 bytes overhead
    assertEquals(42, shuffleBuffer.getEncodedLength());

    ShufflePartitionedData block2 = createData(10);
    block2.getBlockList()[0].setBlockId(block.getBlockList()[0].getBlockId());
    shuffleBuffer.append(block2);
    // The repeat block should not append to shuffleBuffer
    assertEquals(42, shuffleBuffer.getEncodedLength());
  }

  private byte[] getExpectedData(ShufflePartitionedData... spds) {
    int size = 0;
    for (ShufflePartitionedData spd : spds) {
      size += spd.getBlockList()[0].getDataLength();
    }
    byte[] expectedData = new byte[size];
    int offset = 0;
    for (ShufflePartitionedData spd : spds) {
      ShufflePartitionedBlock block = spd.getBlockList()[0];
      block.getData().resetReaderIndex();
      ByteBufUtils.readBytes(block.getData(), expectedData, offset, block.getDataLength());
      offset += block.getDataLength();
    }
    return expectedData;
  }

  private void compareBufferSegment(
      List<ShufflePartitionedBlock> blocks,
      List<BufferSegment> bufferSegments,
      int startBlockIndex,
      int expectedBlockNum) {
    int segmentIndex = 0;
    int offset = 0;
    assertEquals(expectedBlockNum, bufferSegments.size());
    for (int i = startBlockIndex; i < startBlockIndex + expectedBlockNum; i++) {
      ShufflePartitionedBlock spb = blocks.get(i);
      BufferSegment segment = bufferSegments.get(segmentIndex);
      assertEquals(spb.getBlockId(), segment.getBlockId());
      assertEquals(spb.getDataLength(), segment.getLength());
      assertEquals(spb.getCrc(), segment.getCrc());
      assertEquals(offset, segment.getOffset());
      offset += spb.getDataLength();
      segmentIndex++;
    }
  }

  @Override
  protected AtomicInteger getAtomSequenceNo() {
    return atomSequenceNo;
  }

  @Override
  protected ShuffleBuffer createShuffleBuffer() {
    return new ShuffleBufferWithLinkedList();
  }
}
