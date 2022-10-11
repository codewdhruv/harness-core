/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.cdng.artifact.steps;

import static io.harness.data.structure.EmptyPredicate.isEmpty;
import static io.harness.data.structure.EmptyPredicate.isNotEmpty;

import io.harness.beans.DelegateTaskRequest;
import io.harness.cdng.CDStepHelper;
import io.harness.cdng.artifact.bean.ArtifactConfig;
import io.harness.cdng.artifact.bean.yaml.ArtifactListConfig;
import io.harness.cdng.artifact.bean.yaml.ArtifactSource;
import io.harness.cdng.artifact.bean.yaml.CustomArtifactConfig;
import io.harness.cdng.artifact.bean.yaml.PrimaryArtifact;
import io.harness.cdng.artifact.bean.yaml.SidecarArtifactWrapper;
import io.harness.cdng.artifact.bean.yaml.customartifact.CustomScriptInlineSource;
import io.harness.cdng.artifact.mappers.ArtifactResponseToOutcomeMapper;
import io.harness.cdng.artifact.outcome.ArtifactOutcome;
import io.harness.cdng.artifact.outcome.ArtifactsOutcome;
import io.harness.cdng.artifact.outcome.ArtifactsOutcome.ArtifactsOutcomeBuilder;
import io.harness.cdng.artifact.outcome.SidecarsOutcome;
import io.harness.cdng.artifact.utils.ArtifactStepHelper;
import io.harness.cdng.artifact.utils.ArtifactUtils;
import io.harness.cdng.expressions.CDExpressionResolver;
import io.harness.cdng.service.steps.ServiceStepsHelper;
import io.harness.cdng.steps.EmptyStepParameters;
import io.harness.cdng.stepsdependency.constants.OutcomeExpressionConstants;
import io.harness.cdng.visitor.YamlTypes;
import io.harness.data.structure.EmptyPredicate;
import io.harness.delegate.TaskSelector;
import io.harness.delegate.beans.ErrorNotifyResponseData;
import io.harness.delegate.task.artifacts.ArtifactSourceDelegateRequest;
import io.harness.delegate.task.artifacts.ArtifactSourceType;
import io.harness.delegate.task.artifacts.ArtifactTaskType;
import io.harness.delegate.task.artifacts.request.ArtifactTaskParameters;
import io.harness.delegate.task.artifacts.response.ArtifactTaskResponse;
import io.harness.exception.ArtifactServerException;
import io.harness.exception.InvalidRequestException;
import io.harness.executions.steps.ExecutionNodeType;
import io.harness.logging.CommandExecutionStatus;
import io.harness.logging.LogLevel;
import io.harness.logstreaming.NGLogCallback;
import io.harness.ng.core.service.yaml.NGServiceV2InfoConfig;
import io.harness.pms.contracts.ambiance.Ambiance;
import io.harness.pms.contracts.execution.AsyncExecutableResponse;
import io.harness.pms.contracts.execution.Status;
import io.harness.pms.contracts.steps.StepCategory;
import io.harness.pms.contracts.steps.StepType;
import io.harness.pms.execution.utils.AmbianceUtils;
import io.harness.pms.sdk.core.data.OptionalSweepingOutput;
import io.harness.pms.sdk.core.resolver.RefObjectUtils;
import io.harness.pms.sdk.core.resolver.outputs.ExecutionSweepingOutputService;
import io.harness.pms.sdk.core.steps.executables.AsyncExecutable;
import io.harness.pms.sdk.core.steps.io.PassThroughData;
import io.harness.pms.sdk.core.steps.io.StepInputPackage;
import io.harness.pms.sdk.core.steps.io.StepResponse;
import io.harness.pms.yaml.ParameterField;
import io.harness.service.DelegateGrpcClientWrapper;
import io.harness.tasks.ResponseData;

import software.wings.beans.LogColor;
import software.wings.beans.LogHelper;
import software.wings.beans.LogWeight;

import com.google.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetch all artifacts ( primary + sidecars using async strategy and produce artifact outcome )
 */
@Slf4j
public class ArtifactsStepV2 implements AsyncExecutable<EmptyStepParameters> {
  public static final StepType STEP_TYPE = StepType.newBuilder()
                                               .setType(ExecutionNodeType.ARTIFACTS_V2.getName())
                                               .setStepCategory(StepCategory.STEP)
                                               .build();

  private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(1);
  static final String ARTIFACTS_STEP_V_2 = "artifacts_step_v2";
  @Inject private ExecutionSweepingOutputService sweepingOutputService;
  @Inject private ServiceStepsHelper serviceStepsHelper;
  @Inject private ArtifactStepHelper artifactStepHelper;
  @Inject private DelegateGrpcClientWrapper delegateGrpcClientWrapper;

  @Inject private CDStepHelper cdStepHelper;
  @Inject private CDExpressionResolver cdExpressionResolver;

  @Override
  public Class<EmptyStepParameters> getStepParametersClass() {
    return EmptyStepParameters.class;
  }

  @Override
  public AsyncExecutableResponse executeAsync(Ambiance ambiance, EmptyStepParameters stepParameters,
      StepInputPackage inputPackage, PassThroughData passThroughData) {
    final Optional<NGServiceV2InfoConfig> serviceOptional = cdStepHelper.fetchServiceConfigFromSweepingOutput(ambiance);
    if (serviceOptional.isEmpty()) {
      return AsyncExecutableResponse.newBuilder().build();
    }

    final NGServiceV2InfoConfig service = serviceOptional.get();

    if (service.getServiceDefinition().getServiceSpec() == null
        || service.getServiceDefinition().getServiceSpec().getArtifacts() == null) {
      log.info("No artifact configuration found");
      return AsyncExecutableResponse.newBuilder().build();
    }

    final ArtifactListConfig artifacts = service.getServiceDefinition().getServiceSpec().getArtifacts();

    processArtifactSourcesIfPresent(artifacts);

    resolveExpressions(ambiance, artifacts);

    final Set<String> taskIds = new HashSet<>();
    String primaryArtifactTaskId = null;
    final Map<String, ArtifactConfig> artifactConfigMap = new HashMap<>();
    final List<ArtifactConfig> artifactConfigMapForNonDelegateTaskTypes = new ArrayList<>();
    final NGLogCallback logCallback = serviceStepsHelper.getServiceLogCallback(ambiance);
    if (artifacts.getPrimary() != null) {
      if (shouldCreateDelegateTask(artifacts.getPrimary().getSourceType(), artifacts.getPrimary().getSpec())) {
        primaryArtifactTaskId = handle(
            ambiance, logCallback, artifacts.getPrimary().getSpec(), artifacts.getPrimary().getSourceType(), true);
        taskIds.add(primaryArtifactTaskId);
        artifactConfigMap.put(primaryArtifactTaskId, artifacts.getPrimary().getSpec());
      } else {
        artifactConfigMapForNonDelegateTaskTypes.add(artifacts.getPrimary().getSpec());
      }
    }

    if (isNotEmpty(artifacts.getSidecars())) {
      for (SidecarArtifactWrapper sidecar : artifacts.getSidecars()) {
        if (shouldCreateDelegateTask(sidecar.getSidecar().getSourceType(), sidecar.getSidecar().getSpec())) {
          String taskId = handle(
              ambiance, logCallback, sidecar.getSidecar().getSpec(), sidecar.getSidecar().getSourceType(), false);
          taskIds.add(taskId);
          artifactConfigMap.put(taskId, sidecar.getSidecar().getSpec());
        } else {
          artifactConfigMapForNonDelegateTaskTypes.add(sidecar.getSidecar().getSpec());
        }
      }
    }
    sweepingOutputService.consume(ambiance, ARTIFACTS_STEP_V_2,
        new ArtifactsStepV2SweepingOutput(
            primaryArtifactTaskId, artifactConfigMap, artifactConfigMapForNonDelegateTaskTypes),
        "");
    return AsyncExecutableResponse.newBuilder().addAllCallbackIds(taskIds).build();
  }

  private void processArtifactSourcesIfPresent(ArtifactListConfig artifacts) {
    if (artifacts.getPrimary() == null) {
      return;
    }
    PrimaryArtifact primary = artifacts.getPrimary();
    if (artifacts.getPrimary().getSpec() == null && ParameterField.isNotNull(primary.getPrimaryArtifactRef())
        && !primary.getPrimaryArtifactRef().isExpression() && isNotEmpty(primary.getSources())) {
      Optional<ArtifactSource> primaryArtifact =
          primary.getSources()
              .stream()
              .filter(s -> primary.getPrimaryArtifactRef().getValue().equals(s.getIdentifier()))
              .findFirst();
      primaryArtifact.ifPresent(p -> {
        p.getSpec().setPrimaryArtifact(true);
        p.getSpec().setIdentifier(YamlTypes.PRIMARY_ARTIFACT);
        artifacts.setPrimary(PrimaryArtifact.builder()
                                 .spec(p.getSpec())
                                 .sourceType(p.getSourceType())
                                 .metadata(p.getMetadata())
                                 .build());
      });
    }
  }

  private void resolveExpressions(Ambiance ambiance, ArtifactListConfig artifacts) {
    final List<Object> toResolve = new ArrayList<>();
    if (artifacts.getPrimary() != null) {
      toResolve.add(artifacts.getPrimary());
    }
    if (isNotEmpty(artifacts.getSidecars())) {
      toResolve.add(artifacts.getSidecars());
    }
    cdExpressionResolver.updateExpressions(ambiance, toResolve);
  }

  @Override
  public StepResponse handleAsyncResponse(
      Ambiance ambiance, EmptyStepParameters stepParameters, Map<String, ResponseData> responseDataMap) {
    final OptionalSweepingOutput outputOptional =
        sweepingOutputService.resolveOptional(ambiance, RefObjectUtils.getSweepingOutputRefObject(ARTIFACTS_STEP_V_2));

    // If there were some artifacts that did not require a delegate task, we cannot skip here.
    if (isEmpty(responseDataMap) && !nonDelegateTaskArtifactsExist(outputOptional)) {
      return StepResponse.builder().status(Status.SKIPPED).build();
    }

    final List<ErrorNotifyResponseData> failedResponses = responseDataMap.values()
                                                              .stream()
                                                              .filter(ErrorNotifyResponseData.class ::isInstance)
                                                              .map(ErrorNotifyResponseData.class ::cast)
                                                              .collect(Collectors.toList());

    if (isNotEmpty(failedResponses)) {
      log.error("Error notify response found for artifacts step " + failedResponses);
      throw new ArtifactServerException("Failed to fetch artifacts. " + failedResponses.get(0).getErrorMessage());
    }

    if (!outputOptional.isFound()) {
      log.error(ARTIFACTS_STEP_V_2 + " sweeping output not found. Failing...");
      throw new InvalidRequestException("Unable to read artifacts");
    }

    ArtifactsStepV2SweepingOutput artifactsSweepingOutput = (ArtifactsStepV2SweepingOutput) outputOptional.getOutput();

    final NGLogCallback logCallback = serviceStepsHelper.getServiceLogCallback(ambiance);
    final ArtifactsOutcomeBuilder outcomeBuilder = ArtifactsOutcome.builder();
    final SidecarsOutcome sidecarsOutcome = new SidecarsOutcome();
    for (String taskId : responseDataMap.keySet()) {
      final ArtifactConfig artifactConfig = artifactsSweepingOutput.getArtifactConfigMap().get(taskId);
      final ArtifactTaskResponse taskResponse = (ArtifactTaskResponse) responseDataMap.get(taskId);
      final boolean isPrimary = taskId.equals(artifactsSweepingOutput.getPrimaryArtifactTaskId());

      logArtifactFetchedMessage(logCallback, artifactConfig, taskResponse, isPrimary);

      switch (taskResponse.getCommandExecutionStatus()) {
        case SUCCESS:
          ArtifactOutcome artifactOutcome = ArtifactResponseToOutcomeMapper.toArtifactOutcome(artifactConfig,
              taskResponse.getArtifactTaskExecutionResponse().getArtifactDelegateResponses().get(0), true);
          if (isPrimary) {
            outcomeBuilder.primary(artifactOutcome);
          } else {
            sidecarsOutcome.put(artifactConfig.getIdentifier(), artifactOutcome);
          }
          break;
        case FAILURE:
          throw new ArtifactServerException("Artifact delegate task failed: " + taskResponse.getErrorMessage());
        default:
          throw new ArtifactServerException("Unhandled command execution status: "
              + (taskResponse.getCommandExecutionStatus() == null ? "null"
                                                                  : taskResponse.getCommandExecutionStatus().name()));
      }
    }

    // Create outcomes for artifacts that did not require a delegate task
    if (isNotEmpty(artifactsSweepingOutput.getArtifactConfigMapForNonDelegateTaskTypes())) {
      artifactsSweepingOutput.artifactConfigMapForNonDelegateTaskTypes.forEach(ac -> {
        ArtifactOutcome outcome = ArtifactResponseToOutcomeMapper.toArtifactOutcome(ac, null, false);
        logArtifactFetchedMessage(logCallback, ac, null, ac.isPrimaryArtifact());
        if (ac.isPrimaryArtifact()) {
          outcomeBuilder.primary(outcome);
        } else {
          sidecarsOutcome.put(ac.getIdentifier(), outcome);
        }
      });
    }

    final ArtifactsOutcome artifactsOutcome = outcomeBuilder.sidecars(sidecarsOutcome).build();

    sweepingOutputService.consume(
        ambiance, OutcomeExpressionConstants.ARTIFACTS, artifactsOutcome, StepCategory.STAGE.name());

    return StepResponse.builder().status(Status.SUCCEEDED).build();
  }

  @Override
  public void handleAbort(
      Ambiance ambiance, EmptyStepParameters stepParameters, AsyncExecutableResponse executableResponse) {
    final NGLogCallback logCallback = serviceStepsHelper.getServiceLogCallback(ambiance);
    logCallback.saveExecutionLog("Artifacts Step was aborted", LogLevel.ERROR, CommandExecutionStatus.FAILURE);
  }

  private String handle(final Ambiance ambiance, final NGLogCallback logCallback, final ArtifactConfig artifactConfig,
      final ArtifactSourceType sourceType, final boolean isPrimary) {
    if (isPrimary) {
      logCallback.saveExecutionLog("Processing primary artifact...");
      logCallback.saveExecutionLog(
          String.format("Primary artifact info: %s", ArtifactUtils.getLogInfo(artifactConfig, sourceType)));
    } else {
      logCallback.saveExecutionLog(
          String.format("Processing sidecar artifact [%s]...", artifactConfig.getIdentifier()));
      logCallback.saveExecutionLog(String.format("Sidecar artifact [%s] info: %s", artifactConfig.getIdentifier(),
          ArtifactUtils.getLogInfo(artifactConfig, sourceType)));
    }

    final ArtifactSourceDelegateRequest artifactSourceDelegateRequest =
        artifactStepHelper.toSourceDelegateRequest(artifactConfig, ambiance);
    final ArtifactTaskParameters taskParameters = ArtifactTaskParameters.builder()
                                                      .accountId(AmbianceUtils.getAccountId(ambiance))
                                                      .artifactTaskType(ArtifactTaskType.GET_LAST_SUCCESSFUL_BUILD)
                                                      .attributes(artifactSourceDelegateRequest)
                                                      .build();
    logCallback.saveExecutionLog(
        LogHelper.color("Starting delegate task to fetch details of primary artifact", LogColor.Cyan, LogWeight.Bold));

    final List<TaskSelector> delegateSelectors = artifactStepHelper.getDelegateSelectors(artifactConfig, ambiance);

    final DelegateTaskRequest delegateTaskRequest =
        DelegateTaskRequest.builder()
            .accountId(AmbianceUtils.getAccountId(ambiance))
            .taskParameters(taskParameters)
            .taskSelectors(delegateSelectors.stream().map(TaskSelector::getSelector).collect(Collectors.toSet()))
            .taskType(artifactStepHelper.getArtifactStepTaskType(artifactConfig).name())
            .executionTimeout(DEFAULT_TIMEOUT)
            .taskSetupAbstraction("ng", "true")
            .build();

    return delegateGrpcClientWrapper.submitAsyncTask(delegateTaskRequest, Duration.ZERO);
  }

  private void logArtifactFetchedMessage(
      NGLogCallback logCallback, ArtifactConfig artifactConfig, ArtifactTaskResponse taskResponse, boolean isPrimary) {
    if (isPrimary) {
      logCallback.saveExecutionLog(LogHelper.color(
          String.format("Fetched details of primary artifact [status:%s]",
              taskResponse != null ? taskResponse.getCommandExecutionStatus().name() : CommandExecutionStatus.SUCCESS),
          LogColor.Cyan, LogWeight.Bold));
    } else {
      logCallback.saveExecutionLog(LogHelper.color(
          String.format("Fetched details of sidecar artifact [%s] [status: %s]", artifactConfig.getIdentifier(),
              taskResponse != null ? taskResponse.getCommandExecutionStatus().name() : CommandExecutionStatus.SUCCESS),
          LogColor.Cyan, LogWeight.Bold));
    }
    if (taskResponse != null && taskResponse.getArtifactTaskExecutionResponse() != null
        && taskResponse.getArtifactTaskExecutionResponse().getArtifactDelegateResponses() != null) {
      logCallback.saveExecutionLog(LogHelper.color(
          taskResponse.getArtifactTaskExecutionResponse().getArtifactDelegateResponses().get(0).describe(),
          LogColor.Green, LogWeight.Bold));
    } else if (artifactConfig != null) {
      logCallback.saveExecutionLog(
          LogHelper.color(ArtifactUtils.getLogInfo(artifactConfig, artifactConfig.getSourceType()), LogColor.Green));
    }
  }

  public boolean shouldCreateDelegateTask(ArtifactSourceType type, ArtifactConfig config) {
    if (ArtifactSourceType.CUSTOM_ARTIFACT != type) {
      return true;
    }
    if (((CustomArtifactConfig) config).getScripts() == null) {
      return false;
    } else {
      CustomScriptInlineSource customScriptInlineSource = (CustomScriptInlineSource) ((CustomArtifactConfig) config)
                                                              .getScripts()
                                                              .getFetchAllArtifacts()
                                                              .getShellScriptBaseStepInfo()
                                                              .getSource()
                                                              .getSpec();
      if (isEmpty(customScriptInlineSource.getScript().getValue().trim())) {
        return false;
      }
    }
    return true;
  }

  private boolean nonDelegateTaskArtifactsExist(OptionalSweepingOutput outputOptional) {
    return outputOptional != null && outputOptional.isFound()
        && EmptyPredicate.isNotEmpty(
            ((ArtifactsStepV2SweepingOutput) outputOptional.getOutput()).getArtifactConfigMapForNonDelegateTaskTypes());
  }
}
