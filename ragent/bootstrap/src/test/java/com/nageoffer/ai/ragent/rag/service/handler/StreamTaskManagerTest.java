/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.service.handler;

import com.nageoffer.ai.ragent.infra.chat.StreamCancellationHandle;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.api.RTopic;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

class StreamTaskManagerTest {

    @Test
    void shouldCancelLocalHandleWithoutWaitingForRedisSubscriber() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<Boolean> bucket = mock(RBucket.class);
        RTopic topic = mock(RTopic.class);
        when(redisson.<Boolean>getBucket(anyString())).thenReturn(bucket);
        when(redisson.getTopic(anyString())).thenReturn(topic);
        StreamTaskManager manager = new StreamTaskManager(redisson);
        manager.register("active-task", mock(com.nageoffer.ai.ragent.framework.web.SseEmitterSender.class),
                () -> null);
        AtomicBoolean cancelled = new AtomicBoolean();
        manager.bindHandle("active-task", () -> cancelled.set(true));

        manager.cancel("active-task");

        assertThat(cancelled).isTrue();
    }

    @Test
    void shouldCancelLateHandleInsteadOfResurrectingUnregisteredTask() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<Boolean> bucket = mock(RBucket.class);
        when(redisson.<Boolean>getBucket(anyString())).thenReturn(bucket);
        StreamTaskManager manager = new StreamTaskManager(redisson);
        AtomicBoolean cancelled = new AtomicBoolean();
        StreamCancellationHandle lateHandle = () -> cancelled.set(true);

        manager.unregister("completed-task");
        manager.bindHandle("completed-task", lateHandle);

        assertThat(cancelled).isTrue();
        assertThat(manager.isCancelled("completed-task")).isFalse();
    }

    @Test
    void shouldCancelAlreadyBoundHandleWhenTaskIsUnregistered() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<Boolean> bucket = mock(RBucket.class);
        when(redisson.<Boolean>getBucket(anyString())).thenReturn(bucket);
        StreamTaskManager manager = new StreamTaskManager(redisson);
        manager.register("finishing-task", mock(com.nageoffer.ai.ragent.framework.web.SseEmitterSender.class),
                () -> null);
        AtomicBoolean cancelled = new AtomicBoolean();
        manager.bindHandle("finishing-task", () -> cancelled.set(true));

        manager.unregister("finishing-task");

        assertThat(cancelled).isTrue();
    }

    @Test
    void shouldAllowOnlyOneTerminalPathToWin() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<Boolean> bucket = mock(RBucket.class);
        RTopic topic = mock(RTopic.class);
        when(redisson.<Boolean>getBucket(anyString())).thenReturn(bucket);
        when(redisson.getTopic(anyString())).thenReturn(topic);
        StreamTaskManager manager = new StreamTaskManager(redisson);
        manager.register("racing-task", mock(com.nageoffer.ai.ragent.framework.web.SseEmitterSender.class),
                () -> null);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var completion = executor.submit(() -> {
                start.await();
                completed.set(manager.tryComplete("racing-task"));
                return null;
            });
            var cancellation = executor.submit(() -> {
                start.await();
                manager.cancel("racing-task");
                return null;
            });
            start.countDown();
            completion.get(2, java.util.concurrent.TimeUnit.SECONDS);
            cancellation.get(2, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(completed.get() ^ manager.isCancelled("racing-task")).isTrue();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldSerializeActiveCallbackBeforeCancellation() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<Boolean> bucket = mock(RBucket.class);
        RTopic topic = mock(RTopic.class);
        when(redisson.<Boolean>getBucket(anyString())).thenReturn(bucket);
        when(redisson.getTopic(anyString())).thenReturn(topic);
        StreamTaskManager manager = new StreamTaskManager(redisson);
        manager.register("callback-task", mock(com.nageoffer.ai.ragent.framework.web.SseEmitterSender.class),
                () -> null);
        java.util.List<String> order = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.concurrent.CountDownLatch callbackEntered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch releaseCallback = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var callback = executor.submit(() -> manager.runIfActive("callback-task", () -> {
                callbackEntered.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                order.add("content");
            }));
            assertThat(callbackEntered.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var cancellation = executor.submit(() -> {
                manager.cancel("callback-task");
                order.add("cancelled");
            });
            releaseCallback.countDown();
            callback.get(2, java.util.concurrent.TimeUnit.SECONDS);
            cancellation.get(2, java.util.concurrent.TimeUnit.SECONDS);

            assertThat(order).containsExactly("content", "cancelled");
            assertThat(manager.runIfActive("callback-task", () -> order.add("late-content"))).isFalse();
            assertThat(order).doesNotContain("late-content");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldEmitPreExistingRedisCancellationExactlyOnceAfterRegistration() {
        RedissonClient redisson = mock(RedissonClient.class);
        @SuppressWarnings("unchecked")
        RBucket<Boolean> bucket = mock(RBucket.class);
        RTopic topic = mock(RTopic.class);
        when(bucket.get()).thenReturn(Boolean.TRUE);
        when(redisson.<Boolean>getBucket(anyString())).thenReturn(bucket);
        when(redisson.getTopic(anyString())).thenReturn(topic);
        StreamTaskManager manager = new StreamTaskManager(redisson);
        com.nageoffer.ai.ragent.framework.web.SseEmitterSender sender =
                mock(com.nageoffer.ai.ragent.framework.web.SseEmitterSender.class);

        manager.register("pre-cancelled", sender, () -> null);
        manager.cancelIfActive("pre-cancelled");

        verify(sender, times(2)).sendEvent(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
        verify(sender, times(1)).complete();
    }
}
