/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.cdng.artifact.steps;

import static io.harness.cdng.artifact.steps.ArtifactsStepV2.ARTIFACTS_STEP_V_2;
import static io.harness.data.structure.UUIDGenerator.generateUuid;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.harness.CategoryTest;
import io.harness.beans.DelegateTaskRequest;
import io.harness.category.element.UnitTests;
import io.harness.cdng.CDStepHelper;
import io.harness.cdng.artifact.bean.ArtifactConfig;
import io.harness.cdng.artifact.bean.yaml.ArtifactListConfig;
import io.harness.cdng.artifact.bean.yaml.ArtifactSource;
import io.harness.cdng.artifact.bean.yaml.CustomArtifactConfig;
import io.harness.cdng.artifact.bean.yaml.DockerHubArtifactConfig;
import io.harness.cdng.artifact.bean.yaml.GcrArtifactConfig;
import io.harness.cdng.artifact.bean.yaml.PrimaryArtifact;
import io.harness.cdng.artifact.bean.yaml.SidecarArtifact;
import io.harness.cdng.artifact.bean.yaml.SidecarArtifactWrapper;
import io.harness.cdng.artifact.outcome.ArtifactsOutcome;
import io.harness.cdng.artifact.utils.ArtifactStepHelper;
import io.harness.cdng.expressions.CDExpressionResolver;
import io.harness.cdng.service.beans.KubernetesServiceSpec;
import io.harness.cdng.service.beans.ServiceDefinition;
import io.harness.cdng.service.beans.ServiceDefinitionType;
import io.harness.cdng.service.steps.ServiceStepsHelper;
import io.harness.cdng.steps.EmptyStepParameters;
import io.harness.data.structure.UUIDGenerator;
import io.harness.delegate.beans.ErrorNotifyResponseData;
import io.harness.delegate.task.artifacts.ArtifactSourceType;
import io.harness.delegate.task.artifacts.docker.DockerArtifactDelegateRequest;
import io.harness.delegate.task.artifacts.docker.DockerArtifactDelegateResponse;
import io.harness.delegate.task.artifacts.response.ArtifactBuildDetailsNG;
import io.harness.delegate.task.artifacts.response.ArtifactTaskExecutionResponse;
import io.harness.delegate.task.artifacts.response.ArtifactTaskResponse;
import io.harness.exception.ArtifactServerException;
import io.harness.logging.CommandExecutionStatus;
import io.harness.logstreaming.NGLogCallback;
import io.harness.ng.core.service.yaml.NGServiceConfig;
import io.harness.ng.core.service.yaml.NGServiceV2InfoConfig;
import io.harness.pms.contracts.ambiance.Ambiance;
import io.harness.pms.contracts.ambiance.Level;
import io.harness.pms.contracts.execution.AsyncExecutableResponse;
import io.harness.pms.contracts.execution.Status;
import io.harness.pms.sdk.core.data.OptionalSweepingOutput;
import io.harness.pms.sdk.core.resolver.RefObjectUtils;
import io.harness.pms.sdk.core.resolver.outputs.ExecutionSweepingOutputService;
import io.harness.pms.sdk.core.steps.io.StepInputPackage;
import io.harness.pms.sdk.core.steps.io.StepResponse;
import io.harness.pms.yaml.ParameterField;
import io.harness.pms.yaml.YamlUtils;
import io.harness.rule.Owner;
import io.harness.rule.OwnerRule;
import io.harness.service.DelegateGrpcClientWrapper;

import software.wings.beans.TaskType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

public class ArtifactsStepV2Test extends CategoryTest {
  private static final String ACCOUNT_ID = "ACCOUNT_ID";
  @Mock private NGLogCallback mockNgLogCallback;
  @Mock private ServiceStepsHelper serviceStepsHelper;
  @Mock private DelegateGrpcClientWrapper delegateGrpcClientWrapper;
  @Mock private ArtifactStepHelper artifactStepHelper;
  @Mock private ExecutionSweepingOutputService mockSweepingOutputService;
  @Mock private CDStepHelper cdStepHelper;
  @Mock private CDExpressionResolver expressionResolver;
  @InjectMocks private ArtifactsStepV2 step = new ArtifactsStepV2();

  private final EmptyStepParameters stepParameters = new EmptyStepParameters();
  private final StepInputPackage inputPackage = StepInputPackage.builder().build();
  private AutoCloseable mocks;

  private final Ambiance ambiance = buildAmbiance();
  private final ArtifactTaskResponse successResponse = sampleArtifactTaskResponse();
  private final ErrorNotifyResponseData errorNotifyResponse = sampleErrorNotifyResponse();

  @Before
  public void setUp() throws Exception {
    mocks = MockitoAnnotations.openMocks(this);

    // mock serviceStepsHelper
    doReturn(mockNgLogCallback).when(serviceStepsHelper).getServiceLogCallback(Mockito.any());
    doReturn(mockNgLogCallback).when(serviceStepsHelper).getServiceLogCallback(Mockito.any(), Mockito.anyBoolean());

    // mock artifactStepHelper
    doReturn(DockerArtifactDelegateRequest.builder().build())
        .when(artifactStepHelper)
        .toSourceDelegateRequest(any(ArtifactConfig.class), any(Ambiance.class));
    doReturn(TaskType.DOCKER_ARTIFACT_TASK_NG)
        .when(artifactStepHelper)
        .getArtifactStepTaskType(any(ArtifactConfig.class));

    // mock delegateGrpcClientWrapper
    doAnswer(invocationOnMock -> UUIDGenerator.generateUuid())
        .when(delegateGrpcClientWrapper)
        .submitAsyncTask(any(DelegateTaskRequest.class), any(Duration.class));
  }

  @After
  public void tearDown() throws Exception {
    if (mocks != null) {
      mocks.close();
    }
  }

  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void executeAsyncServiceSweepingOutputNotPresent() {
    AsyncExecutableResponse response =
        step.executeAsync(ambiance, stepParameters, StepInputPackage.builder().build(), null);

    assertThat(response.getCallbackIdsCount()).isEqualTo(0);
  }

  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void executeAsyncOnlyPrimary() {
    ArgumentCaptor<ArtifactsStepV2SweepingOutput> captor = ArgumentCaptor.forClass(ArtifactsStepV2SweepingOutput.class);

    doReturn(getServiceConfig(ArtifactListConfig.builder()
                                  .primary(PrimaryArtifact.builder()
                                               .sourceType(ArtifactSourceType.DOCKER_REGISTRY)
                                               .spec(DockerHubArtifactConfig.builder()
                                                         .connectorRef(ParameterField.createValueField("connector"))
                                                         .tag(ParameterField.createValueField("latest"))
                                                         .imagePath(ParameterField.createValueField("nginx"))
                                                         .build())
                                               .build())
                                  .build()))
        .when(cdStepHelper)
        .fetchServiceConfigFromSweepingOutput(Mockito.any(Ambiance.class));

    AsyncExecutableResponse response = step.executeAsync(ambiance, stepParameters, inputPackage, null);

    verify(mockSweepingOutputService).consume(any(Ambiance.class), anyString(), captor.capture(), eq(""));
    verify(expressionResolver, times(1)).updateExpressions(any(Ambiance.class), any());

    ArtifactsStepV2SweepingOutput output = captor.getValue();

    assertThat(output.getArtifactConfigMap()).hasSize(1);
    assertThat(output.getPrimaryArtifactTaskId()).isNotEmpty();
    assertThat(
        output.getArtifactConfigMap().values().stream().map(ArtifactConfig::getIdentifier).collect(Collectors.toSet()))
        .containsExactly("primary");
    assertThat(response.getCallbackIdsCount()).isEqualTo(1);
  }

  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void executeAsyncOnlyPrimaryNoDelegateTaskNeeded() {
    ArgumentCaptor<ArtifactsStepV2SweepingOutput> captor = ArgumentCaptor.forClass(ArtifactsStepV2SweepingOutput.class);

    doReturn(
        getServiceConfig(
            ArtifactListConfig.builder()
                .primary(
                    PrimaryArtifact.builder()
                        .sourceType(ArtifactSourceType.CUSTOM_ARTIFACT)
                        .spec(CustomArtifactConfig.builder().version(ParameterField.createValueField("1.0")).build())
                        .build())
                .build()))
        .when(cdStepHelper)
        .fetchServiceConfigFromSweepingOutput(Mockito.any(Ambiance.class));

    AsyncExecutableResponse response = step.executeAsync(ambiance, stepParameters, inputPackage, null);

    verify(mockSweepingOutputService).consume(any(Ambiance.class), anyString(), captor.capture(), eq(""));
    verify(expressionResolver, times(1)).updateExpressions(any(Ambiance.class), any());

    ArtifactsStepV2SweepingOutput output = captor.getValue();

    assertThat(output.getArtifactConfigMap()).hasSize(0);
    assertThat(output.getPrimaryArtifactTaskId()).isNull();
    assertThat(response.getCallbackIdsCount()).isEqualTo(0);

    assertThat(output.getArtifactConfigMapForNonDelegateTaskTypes()).hasSize(1);
    assertThat(
        ((CustomArtifactConfig) output.getArtifactConfigMapForNonDelegateTaskTypes().get(0)).getVersion().getValue())
        .isEqualTo("1.0");
    assertThat(output.getArtifactConfigMapForNonDelegateTaskTypes().get(0).isPrimaryArtifact()).isTrue();
  }

  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void executeAsyncWithArtifactSources() {
    ArgumentCaptor<ArtifactsStepV2SweepingOutput> captor = ArgumentCaptor.forClass(ArtifactsStepV2SweepingOutput.class);

    // Prepare test data
    ArtifactSource source1 = ArtifactSource.builder()
                                 .identifier("source1-id")
                                 .sourceType(ArtifactSourceType.DOCKER_REGISTRY)
                                 .spec(DockerHubArtifactConfig.builder()
                                           .connectorRef(ParameterField.createValueField("connector"))
                                           .tag(ParameterField.createValueField("latest"))
                                           .imagePath(ParameterField.createValueField("nginx"))
                                           .build())
                                 .build();
    ArtifactSource source2 = ArtifactSource.builder()
                                 .identifier("source2-id")
                                 .sourceType(ArtifactSourceType.GCR)
                                 .spec(GcrArtifactConfig.builder()
                                           .connectorRef(ParameterField.createValueField("connector"))
                                           .tag(ParameterField.createValueField("latest"))
                                           .imagePath(ParameterField.createValueField("nginx"))
                                           .build())
                                 .build();
    doReturn(getServiceConfig(
                 ArtifactListConfig.builder()
                     .primary(PrimaryArtifact.builder()
                                  .sources(List.of(source1, source2))
                                  .primaryArtifactRef(ParameterField.createValueField(source1.getIdentifier()))
                                  .build())
                     .sidecar(SidecarArtifactWrapper.builder()
                                  .sidecar(SidecarArtifact.builder()
                                               .identifier("s1")
                                               .sourceType(ArtifactSourceType.DOCKER_REGISTRY)
                                               .spec(DockerHubArtifactConfig.builder()
                                                         .connectorRef(ParameterField.createValueField("connector"))
                                                         .tag(ParameterField.createValueField("latest"))
                                                         .imagePath(ParameterField.createValueField("nginx"))
                                                         .build())
                                               .build())
                                  .build())
                     .sidecar(SidecarArtifactWrapper.builder()
                                  .sidecar(SidecarArtifact.builder()
                                               .identifier("s2")
                                               .sourceType(ArtifactSourceType.DOCKER_REGISTRY)
                                               .spec(DockerHubArtifactConfig.builder()
                                                         .connectorRef(ParameterField.createValueField("connector"))
                                                         .tag(ParameterField.createValueField("latest"))
                                                         .imagePath(ParameterField.createValueField("nginx"))
                                                         .build())
                                               .build())
                                  .build())
                     .build()))
        .when(cdStepHelper)
        .fetchServiceConfigFromSweepingOutput(Mockito.any(Ambiance.class));

    AsyncExecutableResponse response = step.executeAsync(ambiance, stepParameters, inputPackage, null);

    verify(mockSweepingOutputService).consume(any(Ambiance.class), anyString(), captor.capture(), eq(""));
    verify(expressionResolver, times(1)).updateExpressions(any(Ambiance.class), any());

    ArtifactsStepV2SweepingOutput output = captor.getValue();

    assertThat(output.getArtifactConfigMap()).hasSize(3);
    assertThat(output.getPrimaryArtifactTaskId()).isNotEmpty();
    assertThat(
        output.getArtifactConfigMap().values().stream().map(ArtifactConfig::getIdentifier).collect(Collectors.toSet()))
        .containsExactlyInAnyOrder("primary", "s1", "s2");
    assertThat(response.getCallbackIdsCount()).isEqualTo(3);
  }

  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void executeAsyncPrimaryAndSidecars() {
    ArgumentCaptor<ArtifactsStepV2SweepingOutput> captor = ArgumentCaptor.forClass(ArtifactsStepV2SweepingOutput.class);
    doReturn(getServiceConfig(
                 ArtifactListConfig.builder()
                     .primary(PrimaryArtifact.builder()
                                  .sourceType(ArtifactSourceType.DOCKER_REGISTRY)
                                  .spec(DockerHubArtifactConfig.builder()
                                            .tag(ParameterField.createValueField("latest"))
                                            .connectorRef(ParameterField.createValueField("connector"))
                                            .imagePath(ParameterField.createValueField("nginx"))
                                            .build())
                                  .build())
                     .sidecar(SidecarArtifactWrapper.builder()
                                  .sidecar(SidecarArtifact.builder()
                                               .identifier("s1")
                                               .sourceType(ArtifactSourceType.DOCKER_REGISTRY)
                                               .spec(DockerHubArtifactConfig.builder()
                                                         .connectorRef(ParameterField.createValueField("connector"))
                                                         .tag(ParameterField.createValueField("latest"))
                                                         .imagePath(ParameterField.createValueField("nginx"))
                                                         .build())
                                               .build())
                                  .build())
                     .sidecar(SidecarArtifactWrapper.builder()
                                  .sidecar(SidecarArtifact.builder()
                                               .identifier("s2")
                                               .sourceType(ArtifactSourceType.DOCKER_REGISTRY)
                                               .spec(DockerHubArtifactConfig.builder()
                                                         .connectorRef(ParameterField.createValueField("connector"))
                                                         .tag(ParameterField.createValueField("latest"))
                                                         .imagePath(ParameterField.createValueField("nginx"))
                                                         .build())
                                               .build())
                                  .build())
                     .sidecar(SidecarArtifactWrapper.builder()
                                  .sidecar(SidecarArtifact.builder()
                                               .identifier("s3")
                                               .sourceType(ArtifactSourceType.CUSTOM_ARTIFACT)
                                               .spec(CustomArtifactConfig.builder()
                                                         .version(ParameterField.createValueField("1.0"))
                                                         .build())
                                               .build())
                                  .build())
                     .build()))
        .when(cdStepHelper)
        .fetchServiceConfigFromSweepingOutput(Mockito.any(Ambiance.class));

    AsyncExecutableResponse response = step.executeAsync(ambiance, stepParameters, inputPackage, null);

    verify(mockSweepingOutputService).consume(any(Ambiance.class), anyString(), captor.capture(), eq(""));
    verify(expressionResolver, times(1)).updateExpressions(any(Ambiance.class), any());

    ArtifactsStepV2SweepingOutput output = captor.getValue();

    assertThat(output.getArtifactConfigMap()).hasSize(3);
    assertThat(output.getPrimaryArtifactTaskId()).isNotEmpty();
    assertThat(
        output.getArtifactConfigMap().values().stream().map(ArtifactConfig::getIdentifier).collect(Collectors.toSet()))
        .containsExactlyInAnyOrder("s1", "s2", "primary");
    assertThat(response.getCallbackIdsCount()).isEqualTo(3);

    assertThat(output.getArtifactConfigMapForNonDelegateTaskTypes()).hasSize(1);
    assertThat(
        ((CustomArtifactConfig) output.getArtifactConfigMapForNonDelegateTaskTypes().get(0)).getVersion().getValue())
        .isEqualTo("1.0");
    assertThat(output.getArtifactConfigMapForNonDelegateTaskTypes().get(0).isPrimaryArtifact()).isFalse();
  }

  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void executeAsyncOnlySidecars() {
    ArgumentCaptor<ArtifactsStepV2SweepingOutput> captor = ArgumentCaptor.forClass(ArtifactsStepV2SweepingOutput.class);
    doReturn(getServiceConfig(
                 ArtifactListConfig.builder()
                     .sidecar(SidecarArtifactWrapper.builder()
                                  .sidecar(SidecarArtifact.builder()
                                               .identifier("s1")
                                               .sourceType(ArtifactSourceType.DOCKER_REGISTRY)
                                               .spec(DockerHubArtifactConfig.builder()
                                                         .connectorRef(ParameterField.createValueField("connector"))
                                                         .tag(ParameterField.createValueField("latest"))
                                                         .imagePath(ParameterField.createValueField("nginx"))
                                                         .build())
                                               .build())
                                  .build())
                     .sidecar(SidecarArtifactWrapper.builder()
                                  .sidecar(SidecarArtifact.builder()
                                               .identifier("s2")
                                               .sourceType(ArtifactSourceType.DOCKER_REGISTRY)
                                               .spec(DockerHubArtifactConfig.builder()
                                                         .connectorRef(ParameterField.createValueField("connector"))
                                                         .tag(ParameterField.createValueField("latest"))
                                                         .imagePath(ParameterField.createValueField("nginx"))
                                                         .build())
                                               .build())
                                  .build())
                     .build()))
        .when(cdStepHelper)
        .fetchServiceConfigFromSweepingOutput(Mockito.any(Ambiance.class));

    AsyncExecutableResponse response = step.executeAsync(ambiance, stepParameters, inputPackage, null);

    verify(mockSweepingOutputService).consume(any(Ambiance.class), anyString(), captor.capture(), eq(""));
    verify(expressionResolver, times(1)).updateExpressions(any(Ambiance.class), any());

    ArtifactsStepV2SweepingOutput output = captor.getValue();

    assertThat(output.getArtifactConfigMap()).hasSize(2);
    assertThat(output.getPrimaryArtifactTaskId()).isNull();
    assertThat(
        output.getArtifactConfigMap().values().stream().map(ArtifactConfig::getIdentifier).collect(Collectors.toSet()))
        .containsExactly("s1", "s2");
    assertThat(response.getCallbackIdsCount()).isEqualTo(2);
  }

  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void handleAsyncResponseEmpty() {
    StepResponse stepResponse = step.handleAsyncResponse(ambiance, stepParameters, new HashMap<>());

    assertThat(stepResponse.getStatus()).isEqualTo(Status.SKIPPED);
  }

  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void handleAsyncResponsePrimaryOnly() {
    doReturn(OptionalSweepingOutput.builder()
                 .found(true)
                 .output(ArtifactsStepV2SweepingOutput.builder()
                             .primaryArtifactTaskId("taskId-1")
                             .artifactConfigMap(Map.of("taskId-1", sampleDockerConfig("image1")))
                             .build())
                 .build())
        .when(mockSweepingOutputService)
        .resolveOptional(any(Ambiance.class), eq(RefObjectUtils.getSweepingOutputRefObject(ARTIFACTS_STEP_V_2)));

    StepResponse stepResponse = step.handleAsyncResponse(ambiance, stepParameters, Map.of("taskId-1", successResponse));

    final ArgumentCaptor<ArtifactsOutcome> captor = ArgumentCaptor.forClass(ArtifactsOutcome.class);
    verify(mockSweepingOutputService, times(1))
        .consume(any(Ambiance.class), eq("artifacts"), captor.capture(), eq("STAGE"));

    final ArtifactsOutcome outcome = captor.getValue();

    assertThat(stepResponse.getStatus()).isEqualTo(Status.SUCCEEDED);
    assertThat(outcome.getPrimary()).isNotNull();
    assertThat(outcome.getSidecars()).isEmpty();
  }

  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void handleAsyncErrorNotifyResponse() {
    doReturn(OptionalSweepingOutput.builder()
                 .found(true)
                 .output(ArtifactsStepV2SweepingOutput.builder()
                             .primaryArtifactTaskId("taskId-1")
                             .artifactConfigMap(Map.of("taskId-1", sampleDockerConfig("image1")))
                             .build())
                 .build())
        .when(mockSweepingOutputService)
        .resolveOptional(any(Ambiance.class), eq(RefObjectUtils.getSweepingOutputRefObject(ARTIFACTS_STEP_V_2)));

    try {
      step.handleAsyncResponse(
          ambiance, stepParameters, Map.of("taskId-1", errorNotifyResponse, "taskId-2", successResponse));
    } catch (ArtifactServerException ase) {
      assertThat(ase.getMessage()).contains("No Eligible Delegates");
      return;
    }
    fail("ArtifactServerException expected");
  }

  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void handleAsyncResponsePrimaryAndSidecars() {
    doReturn(OptionalSweepingOutput.builder()
                 .found(true)
                 .output(ArtifactsStepV2SweepingOutput.builder()
                             .primaryArtifactTaskId("taskId-1")
                             .artifactConfigMap(Map.of("taskId-1", sampleDockerConfig("image1"), "taskId-2",
                                 sampleDockerConfig("image2"), "taskId-3", sampleDockerConfig("image3")))
                             .build())
                 .build())
        .when(mockSweepingOutputService)
        .resolveOptional(any(Ambiance.class), eq(RefObjectUtils.getSweepingOutputRefObject(ARTIFACTS_STEP_V_2)));

    StepResponse stepResponse = step.handleAsyncResponse(ambiance, stepParameters,
        Map.of("taskId-1", successResponse, "taskId-2", successResponse, "taskId-3", successResponse));

    final ArgumentCaptor<ArtifactsOutcome> captor = ArgumentCaptor.forClass(ArtifactsOutcome.class);
    verify(mockSweepingOutputService, times(1))
        .consume(any(Ambiance.class), eq("artifacts"), captor.capture(), eq("STAGE"));

    final ArtifactsOutcome outcome = captor.getValue();

    assertThat(stepResponse.getStatus()).isEqualTo(Status.SUCCEEDED);
    assertThat(outcome.getPrimary()).isNotNull();
    assertThat(outcome.getSidecars()).hasSize(2);
  }

  @Test
  @Owner(developers = OwnerRule.YOGESH)
  @Category(UnitTests.class)
  public void handleAsyncResponseSidecarsOnly() {
    doReturn(OptionalSweepingOutput.builder()
                 .found(true)
                 .output(ArtifactsStepV2SweepingOutput.builder()
                             .artifactConfigMap(Map.of("taskId-1", sampleDockerConfig("image1"), "taskId-2",
                                 sampleDockerConfig("image2"), "taskId-3", sampleDockerConfig("image3")))
                             .build())
                 .build())
        .when(mockSweepingOutputService)
        .resolveOptional(any(Ambiance.class), eq(RefObjectUtils.getSweepingOutputRefObject(ARTIFACTS_STEP_V_2)));

    StepResponse stepResponse = step.handleAsyncResponse(ambiance, stepParameters,
        Map.of("taskId-1", successResponse, "taskId-2", successResponse, "taskId-3", successResponse));

    final ArgumentCaptor<ArtifactsOutcome> captor = ArgumentCaptor.forClass(ArtifactsOutcome.class);
    verify(mockSweepingOutputService, times(1))
        .consume(any(Ambiance.class), eq("artifacts"), captor.capture(), eq("STAGE"));

    final ArtifactsOutcome outcome = captor.getValue();

    assertThat(stepResponse.getStatus()).isEqualTo(Status.SUCCEEDED);
    assertThat(outcome.getPrimary()).isNull();
    assertThat(outcome.getSidecars()).hasSize(3);
  }

  private DockerHubArtifactConfig sampleDockerConfig(String imagePath) {
    return DockerHubArtifactConfig.builder()
        .identifier(UUIDGenerator.generateUuid())
        .connectorRef(ParameterField.createValueField("dockerhub"))
        .imagePath(ParameterField.createValueField(imagePath))
        .build();
  }

  private ArtifactTaskResponse sampleArtifactTaskResponse() {
    return ArtifactTaskResponse.builder()
        .commandExecutionStatus(CommandExecutionStatus.SUCCESS)
        .artifactTaskExecutionResponse(
            ArtifactTaskExecutionResponse.builder()
                .artifactDelegateResponse(DockerArtifactDelegateResponse.builder()
                                              .sourceType(ArtifactSourceType.DOCKER_REGISTRY)
                                              .buildDetails(ArtifactBuildDetailsNG.builder().number("1").build())
                                              .build())
                .build())
        .build();
  }

  private ErrorNotifyResponseData sampleErrorNotifyResponse() {
    return ErrorNotifyResponseData.builder().errorMessage("No Eligible Delegates").build();
  }

  private Optional<NGServiceV2InfoConfig> getServiceConfig(ArtifactListConfig artifactListConfig) {
    NGServiceV2InfoConfig config =
        NGServiceV2InfoConfig.builder()
            .identifier("service-id")
            .name("service-name")
            .serviceDefinition(ServiceDefinition.builder()
                                   .type(ServiceDefinitionType.KUBERNETES)
                                   .serviceSpec(KubernetesServiceSpec.builder().artifacts(artifactListConfig).build())
                                   .build())
            .build();
    String serviceYaml = YamlUtils.write(NGServiceConfig.builder().ngServiceV2InfoConfig(config).build());
    return Optional.of(config);
  }

  private Ambiance buildAmbiance() {
    List<Level> levels = new ArrayList<>();
    levels.add(Level.newBuilder()
                   .setRuntimeId(generateUuid())
                   .setSetupId(generateUuid())
                   .setStepType(ArtifactsStepV2.STEP_TYPE)
                   .build());
    return Ambiance.newBuilder()
        .setPlanExecutionId(generateUuid())
        .putAllSetupAbstractions(Map.of("accountId", ACCOUNT_ID))
        .addAllLevels(levels)
        .setExpressionFunctorToken(1234)
        .build();
  }
}
