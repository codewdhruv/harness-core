/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.cdng.customDeployment;

import static io.harness.logging.CommandExecutionStatus.SUCCESS;

import static software.wings.sm.states.customdeploymentng.InstanceMapperUtils.getHostnameFieldName;

import static java.lang.String.format;
import static java.util.Collections.emptyList;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.beans.FeatureName;
import io.harness.cdng.CDStepHelper;
import io.harness.cdng.expressions.CDExpressionResolveFunctor;
import io.harness.cdng.featureFlag.CDFeatureFlagHelper;
import io.harness.cdng.infra.beans.CustomDeploymentInfrastructureOutcome;
import io.harness.cdng.instance.info.InstanceInfoService;
import io.harness.data.structure.CollectionUtils;
import io.harness.data.structure.UUIDGenerator;
import io.harness.delegate.beans.TaskData;
import io.harness.delegate.beans.instancesync.ServerInstanceInfo;
import io.harness.delegate.beans.instancesync.info.CustomDeploymentServerInstanceInfo;
import io.harness.delegate.task.TaskParameters;
import io.harness.delegate.task.shell.ShellScriptTaskNG;
import io.harness.delegate.task.shell.ShellScriptTaskParametersNG;
import io.harness.delegate.task.shell.ShellScriptTaskParametersNG.ShellScriptTaskParametersNGBuilder;
import io.harness.delegate.task.shell.ShellScriptTaskResponseNG;
import io.harness.encryption.Scope;
import io.harness.encryption.SecretRefData;
import io.harness.eraro.ErrorCode;
import io.harness.exception.AccessDeniedException;
import io.harness.exception.ExceptionUtils;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.WingsException;
import io.harness.executions.steps.ExecutionNodeType;
import io.harness.expression.EngineExpressionEvaluator;
import io.harness.expression.ExpressionEvaluatorUtils;
import io.harness.logstreaming.ILogStreamingStepClient;
import io.harness.logstreaming.LogStreamingStepClientFactory;
import io.harness.plancreator.steps.TaskSelectorYaml;
import io.harness.plancreator.steps.common.StepElementParameters;
import io.harness.plancreator.steps.common.rollback.TaskExecutableWithRollbackAndRbac;
import io.harness.pms.contracts.ambiance.Ambiance;
import io.harness.pms.contracts.execution.Status;
import io.harness.pms.contracts.execution.tasks.TaskRequest;
import io.harness.pms.contracts.steps.StepCategory;
import io.harness.pms.contracts.steps.StepType;
import io.harness.pms.execution.utils.AmbianceUtils;
import io.harness.pms.expression.EngineExpressionService;
import io.harness.pms.sdk.core.steps.io.StepInputPackage;
import io.harness.pms.sdk.core.steps.io.StepResponse;
import io.harness.pms.sdk.core.steps.io.StepResponse.StepResponseBuilder;
import io.harness.serializer.KryoSerializer;
import io.harness.shell.ScriptType;
import io.harness.shell.ShellExecutionData;
import io.harness.steps.StepHelper;
import io.harness.steps.StepUtils;
import io.harness.supplier.ThrowingSupplier;
import io.harness.yaml.utils.NGVariablesUtils;

import software.wings.beans.TaskType;
import software.wings.sm.states.customdeploymentng.InstanceMapperUtils;

import com.google.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@OwnedBy(HarnessTeam.CDP)
@Slf4j
public class FetchInstanceScriptStep extends TaskExecutableWithRollbackAndRbac<ShellScriptTaskResponseNG> {
  public static final StepType STEP_TYPE = StepType.newBuilder()
                                               .setType(ExecutionNodeType.FETCH_INSTANCE_SCRIPT.getYamlType())
                                               .setStepCategory(StepCategory.STEP)
                                               .build();
  public static final String OUTPUT_PATH_KEY = "INSTANCE_OUTPUT_PATH";
  public static final String WORKING_DIRECTORY = "/tmp";
  @Inject private CDStepHelper cdStepHelper;
  @Inject private KryoSerializer kryoSerializer;
  @Inject private StepHelper stepHelper;
  @Inject private LogStreamingStepClientFactory logStreamingStepClientFactory;
  @Inject private InstanceInfoService instanceInfoService;
  @Inject private CDFeatureFlagHelper cdFeatureFlagHelper;
  @Inject private EngineExpressionService engineExpressionService;

  static Function<InstanceMapperUtils.HostProperties, CustomDeploymentServerInstanceInfo> instanceElementMapper =
      hostProperties -> {
    return CustomDeploymentServerInstanceInfo.builder()
        .hostId(UUIDGenerator.generateUuid())
        .hostName(hostProperties.getHostName())
        .properties(hostProperties.getOtherPropeties())
        .build();
  };

  @Override
  public void validateResources(Ambiance ambiance, StepElementParameters stepParameters) {
    if (!cdFeatureFlagHelper.isEnabled(AmbianceUtils.getAccountId(ambiance), FeatureName.NG_DEPLOYMENT_TEMPLATE)) {
      throw new AccessDeniedException(
          "Custom Deployment Template NG is not enabled for this account. Please contact harness customer care.",
          ErrorCode.NG_ACCESS_DENIED, WingsException.USER);
    }
  }
  private static String findSecretIdentifierByScope(SecretRefData secret) {
    if (secret.getScope() == Scope.PROJECT) {
      return secret.getIdentifier();
    } else if (secret.getScope() == Scope.ORG) {
      return "org." + secret.getIdentifier();
    } else if (secret.getScope() == Scope.ACCOUNT) {
      return "account." + secret.getIdentifier();
    } else {
      throw new RuntimeException("Invalid scope for a secret");
    }
  }
  private String getResolvedFetchInstanceScript(
      Ambiance ambiance, CustomDeploymentInfrastructureOutcome infrastructureOutcome) {
    List<String> expressions =
        EngineExpressionEvaluator.findExpressions(infrastructureOutcome.getInstanceFetchScript());
    String fetchInstanceScript = infrastructureOutcome.getInstanceFetchScript();
    for (Map.Entry<String, Object> entry : infrastructureOutcome.getVariables().entrySet()) {
      if (entry.getValue() instanceof SecretRefData) {
        Optional<String> exprToReplace =
            expressions.stream().filter(expr -> expr.contains(entry.getKey() + ">")).findFirst();
        if (exprToReplace.isPresent()) {
          fetchInstanceScript = fetchInstanceScript.replace(exprToReplace.get(),
              NGVariablesUtils.fetchSecretExpression(findSecretIdentifierByScope((SecretRefData) entry.getValue())));
        }
      }
    }

    return (String) ExpressionEvaluatorUtils.updateExpressions(
        fetchInstanceScript, new CDExpressionResolveFunctor(engineExpressionService, ambiance));
  }

  @Override
  public TaskRequest obtainTaskAfterRbac(
      Ambiance ambiance, StepElementParameters stepParameters, StepInputPackage inputPackage) {
    FetchInstanceScriptStepParameters stepSpec = (FetchInstanceScriptStepParameters) stepParameters.getSpec();
    ShellScriptTaskParametersNGBuilder taskParametersNGBuilder = ShellScriptTaskParametersNG.builder();
    ILogStreamingStepClient logStreamingStepClient = logStreamingStepClientFactory.getLogStreamingStepClient(ambiance);
    logStreamingStepClient.openStream(ShellScriptTaskNG.COMMAND_UNIT);

    CustomDeploymentInfrastructureOutcome infrastructureOutcome =
        (CustomDeploymentInfrastructureOutcome) cdStepHelper.getInfrastructureOutcome(ambiance);

    TaskParameters taskParameters = taskParametersNGBuilder.accountId(AmbianceUtils.getAccountId(ambiance))
                                        .environmentVariables(new HashMap<>())
                                        .executionId(AmbianceUtils.obtainCurrentRuntimeId(ambiance))
                                        .script(getResolvedFetchInstanceScript(ambiance, infrastructureOutcome))
                                        .executeOnDelegate(true)
                                        .scriptType(ScriptType.BASH)
                                        .workingDirectory(WORKING_DIRECTORY)
                                        .outputVars(Collections.singletonList(OUTPUT_PATH_KEY))
                                        .build();

    final TaskData taskData = TaskData.builder()
                                  .async(true)
                                  .timeout(CDStepHelper.getTimeoutInMillis(stepParameters))
                                  .taskType(TaskType.SHELL_SCRIPT_TASK_NG.name())
                                  .parameters(new Object[] {taskParameters})
                                  .build();
    return StepUtils.prepareCDTaskRequest(ambiance, taskData, kryoSerializer,
        CollectionUtils.emptyIfNull(StepUtils.generateLogKeys(
            StepUtils.generateLogAbstractions(ambiance), Collections.singletonList(ShellScriptTaskNG.COMMAND_UNIT))),
        null, null, TaskSelectorYaml.toTaskSelector(stepSpec.getDelegateSelectors()),
        stepHelper.getEnvironmentType(ambiance));
  }

  @Override
  public StepResponse handleTaskResultWithSecurityContext(Ambiance ambiance, StepElementParameters stepParameters,
      ThrowingSupplier<ShellScriptTaskResponseNG> responseDataSupplier) throws Exception {
    ShellScriptTaskResponseNG response;
    try {
      response = responseDataSupplier.get();
    } catch (Exception ex) {
      log.error("Error while processing Fetch Instance script task response: {}", ExceptionUtils.getMessage(ex), ex);
      throw ex;
    }
    StepResponseBuilder builder = StepResponse.builder()
                                      .unitProgressList(response.getUnitProgressData().getUnitProgresses())
                                      .status(Status.FAILED);
    CustomDeploymentInfrastructureOutcome infrastructureOutcome =
        (CustomDeploymentInfrastructureOutcome) cdStepHelper.getInfrastructureOutcome(ambiance);
    List<CustomDeploymentServerInstanceInfo> instanceElements = new ArrayList<>();
    if (response.getStatus() == SUCCESS) {
      builder.status(Status.SUCCEEDED);
      if (response.getExecuteCommandResponse().getCommandExecutionData() instanceof ShellExecutionData) {
        Map<String, String> output =
            ((ShellExecutionData) response.getExecuteCommandResponse().getCommandExecutionData())
                .getSweepingOutputEnvVariables();
        instanceElements = InstanceMapperUtils.mapJsonToInstanceElements(infrastructureOutcome.getInstanceAttributes(),
            infrastructureOutcome.getInstancesListPath(), output.get(OUTPUT_PATH_KEY), instanceElementMapper);
        instanceElements.forEach(serverInstanceInfo -> {
          serverInstanceInfo.setInstanceFetchScript(getResolvedFetchInstanceScript(ambiance, infrastructureOutcome));
          serverInstanceInfo.setInfrastructureKey(infrastructureOutcome.getInfrastructureKey());
        });
        validateInstanceElements(instanceElements, infrastructureOutcome);
      }
    }
    StepResponse.StepOutcome stepOutcome = instanceInfoService.saveServerInstancesIntoSweepingOutput(
        ambiance, instanceElements.stream().map(element -> (ServerInstanceInfo) element).collect(Collectors.toList()));
    closeLogStream(ambiance);
    return builder.stepOutcome(stepOutcome).build();
  }

  private void closeLogStream(Ambiance ambiance) {
    try {
      Thread.sleep(500, 0);
    } catch (InterruptedException e) {
      log.error("Close Log Stream was interrupted", e);
    } finally {
      ILogStreamingStepClient logStreamingStepClient =
          logStreamingStepClientFactory.getLogStreamingStepClient(ambiance);
      logStreamingStepClient.closeAllOpenStreamsWithPrefix(StepUtils.generateLogKeys(ambiance, emptyList()).get(0));
    }
  }

  private void validateInstanceElements(
      List<CustomDeploymentServerInstanceInfo> instanceElements, CustomDeploymentInfrastructureOutcome infrastructure) {
    final boolean elementWithoutHostnameExists =
        instanceElements.stream().map(CustomDeploymentServerInstanceInfo::getHostName).anyMatch(StringUtils::isBlank);
    if (elementWithoutHostnameExists) {
      throw new InvalidRequestException(format("Could not find \"%s\" field from Json Array",
                                            getHostnameFieldName(infrastructure.getInstanceAttributes())),
          WingsException.USER);
    }
  }

  @Override
  public Class<StepElementParameters> getStepParametersClass() {
    return StepElementParameters.class;
  }
}
