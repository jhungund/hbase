/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.io.hfile;

import static org.apache.hadoop.hbase.io.hfile.CacheConfig.CACHE_DATA_BLOCKS_COMPRESSED_KEY;
import static org.apache.hadoop.hbase.regionserver.CompactSplit.HBASE_REGION_SERVER_ENABLE_COMPACTION;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseClassTestRule;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptor;
import org.apache.hadoop.hbase.client.ColumnFamilyDescriptorBuilder;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.RegionInfoBuilder;
import org.apache.hadoop.hbase.fs.HFileSystem;
import org.apache.hadoop.hbase.io.ByteBuffAllocator;
import org.apache.hadoop.hbase.io.compress.Compression;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.regionserver.ConstantSizeRegionSplitPolicy;
import org.apache.hadoop.hbase.regionserver.HRegionFileSystem;
import org.apache.hadoop.hbase.regionserver.HStoreFile;
import org.apache.hadoop.hbase.regionserver.StoreFileWriter;
import org.apache.hadoop.hbase.testclassification.IOTests;
import org.apache.hadoop.hbase.testclassification.MediumTests;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

@Category({ IOTests.class, MediumTests.class })
public class TestPrefetch {

  @ClassRule
  public static final HBaseClassTestRule CLASS_RULE =
    HBaseClassTestRule.forClass(TestPrefetch.class);

  private static final HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();

  private static final int NUM_VALID_KEY_TYPES = KeyValue.Type.values().length - 2;
  private static final int DATA_BLOCK_SIZE = 2048;
  private static final int NUM_KV = 1000;

  private Configuration conf;
  private CacheConfig cacheConf;
  private FileSystem fs;
  private BlockCache blockCache;

  @Before
  public void setUp() throws IOException {
    conf = TEST_UTIL.getConfiguration();
    conf.setBoolean(CacheConfig.PREFETCH_BLOCKS_ON_OPEN_KEY, true);
    fs = HFileSystem.get(conf);
    blockCache = BlockCacheFactory.createBlockCache(conf);
    cacheConf = new CacheConfig(conf, blockCache);
  }

  @Test
  public void testPrefetchSetInHCDWorks() {
    ColumnFamilyDescriptor columnFamilyDescriptor = ColumnFamilyDescriptorBuilder
      .newBuilder(Bytes.toBytes("f")).setPrefetchBlocksOnOpen(true).build();
    Configuration c = HBaseConfiguration.create();
    assertFalse(c.getBoolean(CacheConfig.PREFETCH_BLOCKS_ON_OPEN_KEY, false));
    CacheConfig cc = new CacheConfig(c, columnFamilyDescriptor, blockCache, ByteBuffAllocator.HEAP);
    assertTrue(cc.shouldPrefetchOnOpen());
  }

  @Test
  public void testPrefetch() throws Exception {
    Path storeFile = writeStoreFile("TestPrefetch");
    readStoreFile(storeFile);
  }

  @Test
  public void testPrefetchRace() throws Exception {
    for (int i = 0; i < 10; i++) {
      Path storeFile = writeStoreFile("TestPrefetchRace-" + i);
      readStoreFileLikeScanner(storeFile);
    }
  }

  /**
   * Read a storefile in the same manner as a scanner -- using non-positional reads and without
   * waiting for prefetch to complete.
   */
  private void readStoreFileLikeScanner(Path storeFilePath) throws Exception {
    // Open the file
    HFile.Reader reader = HFile.createReader(fs, storeFilePath, cacheConf, true, conf);
    do {
      long offset = 0;
      while (offset < reader.getTrailer().getLoadOnOpenDataOffset()) {
        HFileBlock block =
          reader.readBlock(offset, -1, false, /* pread= */false, false, true, null, null);
        offset += block.getOnDiskSizeWithHeader();
      }
    } while (!reader.prefetchComplete());
  }

  private void readStoreFile(Path storeFilePath) throws Exception {
    readStoreFile(storeFilePath, (r, o) -> {
      HFileBlock block = null;
      try {
        block = r.readBlock(o, -1, false, true, false, true, null, null);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      return block;
    }, (key, block) -> {
      boolean isCached = blockCache.getBlock(key, true, false, true) != null;
      if (
        block.getBlockType() == BlockType.DATA || block.getBlockType() == BlockType.ROOT_INDEX
          || block.getBlockType() == BlockType.INTERMEDIATE_INDEX
      ) {
        assertTrue(isCached);
      }
    });
  }

  private void readStoreFileCacheOnly(Path storeFilePath) throws Exception {
    readStoreFile(storeFilePath, (r, o) -> {
      HFileBlock block = null;
      try {
        block = r.readBlock(o, -1, false, true, false, true, null, null, true);
      } catch (IOException e) {
        fail(e.getMessage());
      }
      return block;
    }, (key, block) -> {
      boolean isCached = blockCache.getBlock(key, true, false, true) != null;
      if (block.getBlockType() == BlockType.DATA) {
        assertFalse(block.isUnpacked());
      } else if (
        block.getBlockType() == BlockType.ROOT_INDEX
          || block.getBlockType() == BlockType.INTERMEDIATE_INDEX
      ) {
        assertTrue(block.isUnpacked());
      }
      assertTrue(isCached);
    });
  }

  private void readStoreFile(Path storeFilePath,
    BiFunction<HFile.Reader, Long, HFileBlock> readFunction,
    BiConsumer<BlockCacheKey, HFileBlock> validationFunction) throws Exception {
    // Open the file
    HFile.Reader reader = HFile.createReader(fs, storeFilePath, cacheConf, true, conf);

    while (!reader.prefetchComplete()) {
      // Sleep for a bit
      Thread.sleep(1000);
    }
    long offset = 0;
    while (offset < reader.getTrailer().getLoadOnOpenDataOffset()) {
      HFileBlock block = readFunction.apply(reader, offset);
      BlockCacheKey blockCacheKey = new BlockCacheKey(reader.getName(), offset);
      validationFunction.accept(blockCacheKey, block);
      offset += block.getOnDiskSizeWithHeader();
    }
  }

  @Test
  public void testPrefetchCompressed() throws Exception {
    conf.setBoolean(CACHE_DATA_BLOCKS_COMPRESSED_KEY, true);
    cacheConf = new CacheConfig(conf, blockCache);
    HFileContext context = new HFileContextBuilder().withCompression(Compression.Algorithm.GZ)
      .withBlockSize(DATA_BLOCK_SIZE).build();
    Path storeFile = writeStoreFile("TestPrefetchCompressed", context);
    readStoreFileCacheOnly(storeFile);
    conf.setBoolean(CACHE_DATA_BLOCKS_COMPRESSED_KEY, false);

  }

  @Test
  public void testPrefetchSkipsRefs() throws Exception {
    testPrefetchWhenRefs(true, c -> {
      boolean isCached = c != null;
      assertFalse(isCached);
    });
  }

  @Test
  public void testPrefetchDoesntSkipRefs() throws Exception {
    testPrefetchWhenRefs(false, c -> {
      boolean isCached = c != null;
      assertTrue(isCached);
    });
  }

  private void testPrefetchWhenRefs(boolean compactionEnabled, Consumer<Cacheable> test)
    throws Exception {
    cacheConf = new CacheConfig(conf, blockCache);
    HFileContext context = new HFileContextBuilder().withBlockSize(DATA_BLOCK_SIZE).build();
    Path tableDir = new Path(TEST_UTIL.getDataTestDir(), "testPrefetchSkipRefs");
    RegionInfo region =
      RegionInfoBuilder.newBuilder(TableName.valueOf("testPrefetchSkipRefs")).build();
    Path regionDir = new Path(tableDir, region.getEncodedName());
    Pair<Path, byte[]> fileWithSplitPoint =
      writeStoreFileForSplit(new Path(regionDir, "cf"), context);
    Path storeFile = fileWithSplitPoint.getFirst();
    HRegionFileSystem regionFS =
      HRegionFileSystem.createRegionOnFileSystem(conf, fs, tableDir, region);
    HStoreFile file = new HStoreFile(fs, storeFile, conf, cacheConf, BloomType.NONE, true);
    Path ref = regionFS.splitStoreFile(region, "cf", file, fileWithSplitPoint.getSecond(), false,
      new ConstantSizeRegionSplitPolicy());
    conf.setBoolean(HBASE_REGION_SERVER_ENABLE_COMPACTION, compactionEnabled);
    HStoreFile refHsf = new HStoreFile(this.fs, ref, conf, cacheConf, BloomType.NONE, true);
    refHsf.initReader();
    HFile.Reader reader = refHsf.getReader().getHFileReader();
    while (!reader.prefetchComplete()) {
      // Sleep for a bit
      Thread.sleep(1000);
    }
    long offset = 0;
    while (offset < reader.getTrailer().getLoadOnOpenDataOffset()) {
      HFileBlock block = reader.readBlock(offset, -1, false, true, false, true, null, null, true);
      BlockCacheKey blockCacheKey = new BlockCacheKey(reader.getName(), offset);
      if (block.getBlockType() == BlockType.DATA) {
        test.accept(blockCache.getBlock(blockCacheKey, true, false, true));
      }
      offset += block.getOnDiskSizeWithHeader();
    }
  }

  private Path writeStoreFile(String fname) throws IOException {
    HFileContext meta = new HFileContextBuilder().withBlockSize(DATA_BLOCK_SIZE).build();
    return writeStoreFile(fname, meta);
  }

  private Path writeStoreFile(String fname, HFileContext context) throws IOException {
    Path storeFileParentDir = new Path(TEST_UTIL.getDataTestDir(), fname);
    StoreFileWriter sfw = new StoreFileWriter.Builder(conf, cacheConf, fs)
      .withOutputDir(storeFileParentDir).withFileContext(context).build();
    Random rand = ThreadLocalRandom.current();
    final int rowLen = 32;
    for (int i = 0; i < NUM_KV; ++i) {
      byte[] k = RandomKeyValueUtil.randomOrderedKey(rand, i);
      byte[] v = RandomKeyValueUtil.randomValue(rand);
      int cfLen = rand.nextInt(k.length - rowLen + 1);
      KeyValue kv = new KeyValue(k, 0, rowLen, k, rowLen, cfLen, k, rowLen + cfLen,
        k.length - rowLen - cfLen, rand.nextLong(), generateKeyType(rand), v, 0, v.length);
      sfw.append(kv);
    }

    sfw.close();
    return sfw.getPath();
  }

  private Pair<Path, byte[]> writeStoreFileForSplit(Path storeDir, HFileContext context)
    throws IOException {
    StoreFileWriter sfw = new StoreFileWriter.Builder(conf, cacheConf, fs).withOutputDir(storeDir)
      .withFileContext(context).build();
    Random rand = ThreadLocalRandom.current();
    final int rowLen = 32;
    byte[] splitPoint = null;
    for (int i = 0; i < NUM_KV; ++i) {
      byte[] k = RandomKeyValueUtil.randomOrderedKey(rand, i);
      byte[] v = RandomKeyValueUtil.randomValue(rand);
      int cfLen = rand.nextInt(k.length - rowLen + 1);
      KeyValue kv = new KeyValue(k, 0, rowLen, k, rowLen, cfLen, k, rowLen + cfLen,
        k.length - rowLen - cfLen, rand.nextLong(), generateKeyType(rand), v, 0, v.length);
      sfw.append(kv);
      if (i == NUM_KV / 2) {
        splitPoint = k;
      }
    }
    sfw.close();
    return new Pair(sfw.getPath(), splitPoint);
  }

  public static KeyValue.Type generateKeyType(Random rand) {
    if (rand.nextBoolean()) {
      // Let's make half of KVs puts.
      return KeyValue.Type.Put;
    } else {
      KeyValue.Type keyType = KeyValue.Type.values()[1 + rand.nextInt(NUM_VALID_KEY_TYPES)];
      if (keyType == KeyValue.Type.Minimum || keyType == KeyValue.Type.Maximum) {
        throw new RuntimeException("Generated an invalid key type: " + keyType + ". "
          + "Probably the layout of KeyValue.Type has changed.");
      }
      return keyType;
    }
  }

}
