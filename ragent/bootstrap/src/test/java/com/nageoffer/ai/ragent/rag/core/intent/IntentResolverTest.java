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

package com.nageoffer.ai.ragent.rag.core.intent;

import com.nageoffer.ai.ragent.rag.core.rewrite.RewriteResult;
import com.nageoffer.ai.ragent.rag.dto.SubQuestionIntent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentResolverTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void shouldDegradeToEmptyIntentWhenClassifierExceedsTimeBudget() throws Exception {
        IntentClassifier classifier = mock(IntentClassifier.class);
        when(classifier.classifyTargets(anyString())).thenAnswer(invocation -> {
            Thread.sleep(5_000);
            return List.of();
        });
        IntentResolver resolver = new IntentResolver(classifier, executor, 100);

        long startNanos = System.nanoTime();
        List<SubQuestionIntent> result = resolver.resolve(
                new RewriteResult("机械臂操作钢卷", List.of("机械臂操作钢卷")));
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(result).singleElement().satisfies(intent -> {
            assertThat(intent.subQuestion()).isEqualTo("机械臂操作钢卷");
            assertThat(intent.nodeScores()).isEmpty();
        });
        assertThat(elapsedMillis).isLessThan(3_000);
    }

    @Test
    void shouldDegradeToEmptyIntentWhenClassifierExecutorRejectsSubmission() {
        IntentClassifier classifier = mock(IntentClassifier.class);
        IntentResolver resolver = new IntentResolver(
                classifier,
                command -> {
                    throw new RejectedExecutionException("intent classifier is saturated");
                },
                100);

        List<SubQuestionIntent> result = resolver.resolve(
                new RewriteResult("机械臂操作钢卷", List.of("机械臂操作钢卷")));

        assertThat(result).singleElement().satisfies(intent -> assertThat(intent.nodeScores()).isEmpty());
    }
}
