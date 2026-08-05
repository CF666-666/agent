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

package com.nageoffer.ai.ragent.rag.core.retrieve;

import com.nageoffer.ai.ragent.multimodal.retrieval.image.ImageSearchChannel;
import com.nageoffer.ai.ragent.rag.core.hypergraph.EntityExtractor;
import com.nageoffer.ai.ragent.rag.core.hypergraph.IndustrialHyperGraph;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.HyperGraphSearchChannel;
import com.nageoffer.ai.ragent.rag.core.retrieve.channel.SearchContext;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.FusionProperties;
import com.nageoffer.ai.ragent.rag.core.retrieve.postprocessor.MultiSourceFusionProcessor;
import com.nageoffer.ai.ragent.rag.dto.RetrievalOptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RetrievalOptionsTest {

    @Test
    void defaultsKeepAllRetrievalCapabilitiesEnabled() {
        RetrievalOptions options = RetrievalOptions.defaults();

        assertTrue(options.enableRewrite());
        assertTrue(options.enableImage());
        assertTrue(options.enableHyperGraph());
        assertTrue(options.enableFusion());
    }

    @Test
    void missingHttpValuesKeepHistoricalDefault() {
        RetrievalOptions options = RetrievalOptions.from(null, null, null, null);

        assertTrue(options.enableRewrite());
        assertTrue(options.enableImage());
        assertTrue(options.enableHyperGraph());
        assertTrue(options.enableFusion());
    }

    @Test
    void channelAndFusionSwitchesAreAppliedFromSearchContext() {
        SearchContext imageDisabled = context(new RetrievalOptions(true, false, true, true));
        SearchContext hyperGraphDisabled = context(new RetrievalOptions(true, true, false, true));
        SearchContext fusionDisabled = context(new RetrievalOptions(true, true, true, false));

        ImageSearchChannel imageChannel = new ImageSearchChannel(mock(com.nageoffer.ai.ragent.rag.core.retrieve.RetrieverService.class));
        HyperGraphSearchChannel hyperGraphChannel = new HyperGraphSearchChannel(
                mock(IndustrialHyperGraph.class), mock(EntityExtractor.class));
        MultiSourceFusionProcessor fusionProcessor = new MultiSourceFusionProcessor(new FusionProperties());

        assertFalse(imageChannel.isEnabled(imageDisabled));
        assertTrue(imageChannel.isEnabled(hyperGraphDisabled));
        assertFalse(hyperGraphChannel.isEnabled(hyperGraphDisabled));
        assertTrue(hyperGraphChannel.isEnabled(imageDisabled));
        assertFalse(fusionProcessor.isEnabled(fusionDisabled));
        assertTrue(fusionProcessor.isEnabled(imageDisabled));
    }

    private SearchContext context(RetrievalOptions options) {
        return SearchContext.builder()
                .originalQuestion("设备故障")
                .rewrittenQuestion("设备故障")
                .retrievalOptions(options)
                .build();
    }
}
