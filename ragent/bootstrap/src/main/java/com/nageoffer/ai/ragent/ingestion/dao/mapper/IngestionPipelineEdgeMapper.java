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

package com.nageoffer.ai.ragent.ingestion.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nageoffer.ai.ragent.ingestion.dao.entity.IngestionPipelineEdgeDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface IngestionPipelineEdgeMapper extends BaseMapper<IngestionPipelineEdgeDO> {

    /** Pipeline definitions are replace-on-save configuration, not historical records. */
    @Delete("DELETE FROM t_ingestion_pipeline_edge WHERE pipeline_id = #{pipelineId}")
    int deleteByPipelineId(@Param("pipelineId") String pipelineId);
}
