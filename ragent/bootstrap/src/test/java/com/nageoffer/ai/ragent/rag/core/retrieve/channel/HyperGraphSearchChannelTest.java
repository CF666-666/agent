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

package com.nageoffer.ai.ragent.rag.core.retrieve.channel;

import com.nageoffer.ai.ragent.rag.core.hypergraph.HyperEdge;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HyperGraphSearchChannel 结构化路径构建测试
 */
class HyperGraphSearchChannelTest {

    @Test
    void shouldBuildStructuredPathFromAllFields() {
        HyperEdge edge = HyperEdge.builder()
                .equipment("1号鼓风机")
                .condition("冷却水不足")
                .parameter("电机过载")
                .fault("跳闸")
                .build();

        String path = HyperGraphSearchChannel.buildStructuredPath(edge);
        assertThat(path).isEqualTo("1号鼓风机 → 冷却水不足 → 电机过载 → 跳闸");
    }

    @Test
    void shouldBuildStructuredPathFromPartialFields() {
        HyperEdge edge = HyperEdge.builder()
                .equipment("2号轧机")
                .fault("轴承过热")
                .build();

        String path = HyperGraphSearchChannel.buildStructuredPath(edge);
        assertThat(path).isEqualTo("2号轧机 → 轴承过热");
    }

    @Test
    void shouldReturnEmptyStringForEmptyEdge() {
        HyperEdge edge = HyperEdge.builder().build();
        String path = HyperGraphSearchChannel.buildStructuredPath(edge);
        assertThat(path).isEqualTo("");
    }
}
