/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.yaml.core.failurestrategy.manualintervention;

import static io.harness.yaml.core.failurestrategy.NGFailureActionTypeConstants.MANUAL_INTERVENTION;

import io.harness.annotation.RecasterAlias;
import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.yaml.core.failurestrategy.FailureStrategyActionConfig;
import io.harness.yaml.core.failurestrategy.NGFailureActionType;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModelProperty;
import javax.validation.constraints.NotNull;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@OwnedBy(HarnessTeam.PIPELINE)
@RecasterAlias("io.harness.yaml.core.failurestrategy.manualintervention.ManualInterventionFailureActionConfig")
public class ManualInterventionFailureActionConfig implements FailureStrategyActionConfig {
  @ApiModelProperty(allowableValues = MANUAL_INTERVENTION)
  NGFailureActionType type = NGFailureActionType.MANUAL_INTERVENTION;

  @NotNull @JsonProperty("spec") ManualFailureSpecConfig specConfig;
}
