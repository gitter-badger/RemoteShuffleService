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

package com.aliyun.emr.rss.common.network;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.common.io.Files;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

import com.aliyun.emr.rss.common.network.buffer.ManagedBuffer;
import com.aliyun.emr.rss.common.network.client.RpcResponseCallback;
import com.aliyun.emr.rss.common.network.client.StreamCallback;
import com.aliyun.emr.rss.common.network.client.TransportClient;
import com.aliyun.emr.rss.common.network.client.TransportClientFactory;
import com.aliyun.emr.rss.common.network.server.RpcHandler;
import com.aliyun.emr.rss.common.network.server.StreamManager;
import com.aliyun.emr.rss.common.network.server.TransportServer;
import com.aliyun.emr.rss.common.network.util.MapConfigProvider;
import com.aliyun.emr.rss.common.network.util.TransportConf;

public class StreamSuiteJ {
  private static final String[] STREAMS = StreamTestHelper.STREAMS;
  private static StreamTestHelper testData;

  private static TransportServer server;
  private static TransportClientFactory clientFactory;

  private static ByteBuffer createBuffer(int bufSize) {
    ByteBuffer buf = ByteBuffer.allocate(bufSize);
    for (int i = 0; i < bufSize; i ++) {
      buf.put((byte) i);
    }
    buf.flip();
    return buf;
  }

  @BeforeClass
  public static void setUp() throws Exception {
    testData = new StreamTestHelper();

    final TransportConf conf = new TransportConf("shuffle", MapConfigProvider.EMPTY);
    final StreamManager streamManager = new StreamManager() {
      @Override
      public ManagedBuffer getChunk(long streamId, int chunkIndex) {
        throw new UnsupportedOperationException();
      }

      @Override
      public ManagedBuffer openStream(String streamId) {
        return testData.openStream(conf, streamId);
      }
    };
    RpcHandler handler = new RpcHandler() {
      @Override
      public void receive(
          TransportClient client,
          ByteBuffer message,
          RpcResponseCallback callback) {
        throw new UnsupportedOperationException();
      }

      @Override
      public StreamManager getStreamManager() {
        return streamManager;
      }
    };
    TransportContext context = new TransportContext(conf, handler);
    server = context.createServer();
    clientFactory = context.createClientFactory();
  }

  @AfterClass
  public static void tearDown() {
    server.close();
    clientFactory.close();
    testData.cleanup();
  }

  @Test
  public void testZeroLengthStream() throws Throwable {
    TransportClient client = clientFactory.createClient(TestUtils.getLocalHost(), server.getPort());
    try {
      StreamTask task = new StreamTask(client, "emptyBuffer", TimeUnit.SECONDS.toMillis(5));
      task.run();
      task.check();
    } finally {
      client.close();
    }
  }

  @Test
  public void testSingleStream() throws Throwable {
    TransportClient client = clientFactory.createClient(TestUtils.getLocalHost(), server.getPort());
    try {
      StreamTask task = new StreamTask(client, "largeBuffer", TimeUnit.SECONDS.toMillis(5));
      task.run();
      task.check();
    } finally {
      client.close();
    }
  }

  @Test
  public void testMultipleStreams() throws Throwable {
    TransportClient client = clientFactory.createClient(TestUtils.getLocalHost(), server.getPort());
    try {
      for (int i = 0; i < 20; i++) {
        StreamTask task = new StreamTask(client, STREAMS[i % STREAMS.length],
          TimeUnit.SECONDS.toMillis(5));
        task.run();
        task.check();
      }
    } finally {
      client.close();
    }
  }

  @Test
  public void testConcurrentStreams() throws Throwable {
    ExecutorService executor = Executors.newFixedThreadPool(20);
    TransportClient client = clientFactory.createClient(TestUtils.getLocalHost(), server.getPort());

    try {
      List<StreamTask> tasks = new ArrayList<>();
      for (int i = 0; i < 20; i++) {
        StreamTask task = new StreamTask(client, STREAMS[i % STREAMS.length],
          TimeUnit.SECONDS.toMillis(20));
        tasks.add(task);
        executor.submit(task);
      }

      executor.shutdown();
      assertTrue("Timed out waiting for tasks.", executor.awaitTermination(30, TimeUnit.SECONDS));
      for (StreamTask task : tasks) {
        task.check();
      }
    } finally {
      executor.shutdownNow();
      client.close();
    }
  }

  private static class StreamTask implements Runnable {

    private final TransportClient client;
    private final String streamId;
    private final long timeoutMs;
    private Throwable error;

    StreamTask(TransportClient client, String streamId, long timeoutMs) {
      this.client = client;
      this.streamId = streamId;
      this.timeoutMs = timeoutMs;
    }

    @Override
    public void run() {
      ByteBuffer srcBuffer = null;
      OutputStream out = null;
      File outFile = null;
      try {
        ByteArrayOutputStream baos = null;

        switch (streamId) {
          case "largeBuffer":
            baos = new ByteArrayOutputStream();
            out = baos;
            srcBuffer = testData.largeBuffer;
            break;
          case "smallBuffer":
            baos = new ByteArrayOutputStream();
            out = baos;
            srcBuffer = testData.smallBuffer;
            break;
          case "file":
            outFile = File.createTempFile("data", ".tmp", testData.tempDir);
            out = new FileOutputStream(outFile);
            break;
          case "emptyBuffer":
            baos = new ByteArrayOutputStream();
            out = baos;
            srcBuffer = testData.emptyBuffer;
            break;
          default:
            throw new IllegalArgumentException(streamId);
        }

        TestCallback callback = new TestCallback(out);
        client.stream(streamId, callback);
        callback.waitForCompletion(timeoutMs);

        if (srcBuffer == null) {
          assertTrue("File stream did not match.", Files.equal(testData.testFile, outFile));
        } else {
          ByteBuffer base;
          synchronized (srcBuffer) {
            base = srcBuffer.duplicate();
          }
          byte[] result = baos.toByteArray();
          byte[] expected = new byte[base.remaining()];
          base.get(expected);
          assertEquals(expected.length, result.length);
          assertTrue("buffers don't match", Arrays.equals(expected, result));
        }
      } catch (Throwable t) {
        error = t;
      } finally {
        if (out != null) {
          try {
            out.close();
          } catch (Exception e) {
            // ignore.
          }
        }
        if (outFile != null) {
          outFile.delete();
        }
      }
    }

    public void check() throws Throwable {
      if (error != null) {
        throw error;
      }
    }
  }

  static class TestCallback implements StreamCallback {

    private final OutputStream out;
    public volatile boolean completed;
    public volatile Throwable error;

    TestCallback(OutputStream out) {
      this.out = out;
      this.completed = false;
    }

    @Override
    public void onData(String streamId, ByteBuffer buf) throws IOException {
      byte[] tmp = new byte[buf.remaining()];
      buf.get(tmp);
      out.write(tmp);
    }

    @Override
    public void onComplete(String streamId) throws IOException {
      out.close();
      synchronized (this) {
        completed = true;
        notifyAll();
      }
    }

    @Override
    public void onFailure(String streamId, Throwable cause) {
      error = cause;
      synchronized (this) {
        completed = true;
        notifyAll();
      }
    }

    void waitForCompletion(long timeoutMs) {
      long now = System.currentTimeMillis();
      long deadline = now + timeoutMs;
      synchronized (this) {
        while (!completed && now < deadline) {
          try {
            wait(deadline - now);
          } catch (InterruptedException ie) {
            throw new RuntimeException(ie);
          }
          now = System.currentTimeMillis();
        }
      }
      assertTrue("Timed out waiting for stream.", completed);
      assertNull(error);
    }
  }
}
