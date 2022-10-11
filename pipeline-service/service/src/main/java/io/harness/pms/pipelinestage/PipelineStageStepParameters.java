/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.pms.pipelinestage;

import io.harness.annotation.RecasterAlias;
import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.pms.sdk.core.steps.io.StepParameters;
import io.harness.pms.yaml.ParameterField;

import java.util.List;
import java.util.Map;
import javax.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.TypeAlias;

@OwnedBy(HarnessTeam.PIPELINE)
@Data
@Builder
@TypeAlias("pipelineStageStepParameters")
@RecasterAlias("io.harness.pms.pipelinestage.PipelineStageStepParameters")
public class PipelineStageStepParameters implements StepParameters {
  @NotNull String pipeline;
  @NotNull String project;
  @NotNull String org;

  private ParameterField<Map<String, Object>> pipelineInputs;

  private List<String> inputSetReferences;
}
