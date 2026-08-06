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

package com.nageoffer.ai.ragent.infra.rerank;

import com.nageoffer.ai.ragent.framework.convention.RetrievedChunk;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NoopRerankClientTest {

    @Test
    void shouldPromoteQueryAlignedCandidateWhenRemoteRerankFallsBack() {
        NoopRerankClient client = new NoopRerankClient();
        RetrievedChunk unrelated = chunk("hypergraph", "1号鼓风机轴承温度异常导致跳闸", 1.1f);
        RetrievedChunk aligned = chunk("image", "自动化机械臂正在操作大型金属卷材", 0.9f);

        List<RetrievedChunk> reranked = client.rerank(
                "请识别自动化机械臂操作大型金属卷材的工业图纸",
                List.of(unrelated, aligned), 1, null);

        assertThat(reranked).extracting(RetrievedChunk::getId).containsExactly("image");
        assertThat(reranked.get(0).getMetadata())
                .containsEntry("rerankMode", "local_lexical_fallback")
                .containsKey("fallbackLexicalScore");
    }

    @Test
    void shouldKeepInputOrderWhenFallbackScoresTie() {
        NoopRerankClient client = new NoopRerankClient();
        RetrievedChunk first = chunk("first", "无重叠文本", 1.0f);
        RetrievedChunk second = chunk("second", "另一段无重叠文本", 1.0f);

        List<RetrievedChunk> reranked = client.rerank("查询词", List.of(first, second), 2, null);

        assertThat(reranked).extracting(RetrievedChunk::getId).containsExactly("first", "second");
    }

    private static RetrievedChunk chunk(String id, String text, float score) {
        return RetrievedChunk.builder().id(id).text(text).score(score)
                .metadata(Map.of("source", "test")).build();
    }
}
