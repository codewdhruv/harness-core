/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.cdng.creator.plan.stage;

import static io.harness.annotations.dev.HarnessTeam.CDC;
import static io.harness.cdng.pipeline.steps.MultiDeploymentSpawnerUtils.SERVICE_REF_EXPRESSION;

import io.harness.annotations.dev.OwnedBy;
import io.harness.cdng.creator.plan.envGroup.EnvGroupPlanCreatorHelper;
import io.harness.cdng.creator.plan.environment.EnvironmentPlanCreatorHelper;
import io.harness.cdng.creator.plan.infrastructure.InfrastructurePmsPlanCreator;
import io.harness.cdng.creator.plan.service.ServiceAllInOnePlanCreatorUtils;
import io.harness.cdng.creator.plan.service.ServicePlanCreatorHelper;
import io.harness.cdng.creator.plan.stage.OverridesFromEnvironment.OverridesFromEnvironmentBuilder;
import io.harness.cdng.envGroup.yaml.EnvGroupPlanCreatorConfig;
import io.harness.cdng.envgroup.yaml.EnvironmentGroupYaml;
import io.harness.cdng.environment.yaml.EnvironmentPlanCreatorConfig;
import io.harness.cdng.environment.yaml.EnvironmentYamlV2;
import io.harness.cdng.environment.yaml.EnvironmentsYaml;
import io.harness.cdng.pipeline.PipelineInfrastructure;
import io.harness.cdng.pipeline.beans.DeploymentStageStepParameters;
import io.harness.cdng.pipeline.beans.MultiDeploymentStepParameters;
import io.harness.cdng.pipeline.steps.CdStepParametersUtils;
import io.harness.cdng.pipeline.steps.DeploymentStageStep;
import io.harness.cdng.pipeline.steps.MultiDeploymentSpawnerUtils;
import io.harness.cdng.service.beans.ServiceDefinitionType;
import io.harness.cdng.service.beans.ServiceYamlV2;
import io.harness.cdng.visitor.YamlTypes;
import io.harness.data.structure.EmptyPredicate;
import io.harness.data.structure.UUIDGenerator;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.InvalidYamlException;
import io.harness.ng.core.environment.services.EnvironmentService;
import io.harness.ng.core.infrastructure.services.InfrastructureEntityService;
import io.harness.ng.core.serviceoverride.services.ServiceOverrideService;
import io.harness.plancreator.stages.AbstractStagePlanCreator;
import io.harness.plancreator.steps.GenericStepPMSPlanCreator;
import io.harness.plancreator.steps.common.SpecParameters;
import io.harness.plancreator.steps.common.StageElementParameters.StageElementParametersBuilder;
import io.harness.plancreator.strategy.StrategyType;
import io.harness.plancreator.strategy.StrategyUtils;
import io.harness.pms.contracts.advisers.AdviserObtainment;
import io.harness.pms.contracts.facilitators.FacilitatorObtainment;
import io.harness.pms.contracts.facilitators.FacilitatorType;
import io.harness.pms.contracts.plan.Dependencies;
import io.harness.pms.contracts.plan.Dependency;
import io.harness.pms.contracts.plan.EdgeLayoutList;
import io.harness.pms.contracts.plan.GraphLayoutNode;
import io.harness.pms.contracts.plan.YamlUpdates;
import io.harness.pms.contracts.steps.StepType;
import io.harness.pms.execution.OrchestrationFacilitatorType;
import io.harness.pms.execution.utils.SkipInfoUtils;
import io.harness.pms.merger.helpers.RuntimeInputFormHelper;
import io.harness.pms.sdk.core.plan.PlanNode;
import io.harness.pms.sdk.core.plan.PlanNode.PlanNodeBuilder;
import io.harness.pms.sdk.core.plan.creation.beans.GraphLayoutResponse;
import io.harness.pms.sdk.core.plan.creation.beans.PlanCreationContext;
import io.harness.pms.sdk.core.plan.creation.beans.PlanCreationResponse;
import io.harness.pms.sdk.core.plan.creation.yaml.StepOutcomeGroup;
import io.harness.pms.yaml.DependenciesUtils;
import io.harness.pms.yaml.ParameterField;
import io.harness.pms.yaml.YAMLFieldNameConstants;
import io.harness.pms.yaml.YamlField;
import io.harness.pms.yaml.YamlNode;
import io.harness.pms.yaml.YamlUtils;
import io.harness.serializer.KryoSerializer;
import io.harness.strategy.StrategyValidationUtils;
import io.harness.utils.NGFeatureFlagHelperService;
import io.harness.when.utils.RunInfoUtils;
import io.harness.yaml.core.failurestrategy.FailureStrategyConfig;

import com.google.common.base.Preconditions;
import com.google.inject.Inject;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

/**
 * Stage plan graph V1 -
 *  Stage
 *      spec (1 children, serviceConfig, next = infra)
 *          serviceConfig (1 children, serviceNode)
 *              service (next = serviceDefinitionNode)
 *              serviceDefinition (1 children, env)
 *                Environment (next = spec node)
 *                spec
 *                  artifacts
 *                  manifests
 *          infrastructureSection(UI visible)
 *            infraDefinition
 *              spec
 *          execution
 *
 * Stage plan graph V2 -
 *  Stage
 *      spec (1 children, service)
 *          serviceSection (1 children, service, next = infra) [Done to keep previous plan creators in sync with v2]
 *              service (next = serviceDef)
 *              serviceDefinition (1 children, env)
 *                Environment (next = spec node)
 *                spec
 *                  artifacts
 *                  manifests
 *          infrastructureSection(UI visible)/Gitops(UI visible)
 *            infraDefinition
 *              spec
 *          execution
 */

@OwnedBy(CDC)
@Slf4j
public class DeploymentStagePMSPlanCreatorV2 extends AbstractStagePlanCreator<DeploymentStageNode> {
  @Inject private KryoSerializer kryoSerializer;
  @Inject private EnvironmentService environmentService;
  @Inject private NGFeatureFlagHelperService featureFlagHelperService;

  @Inject private InfrastructureEntityService infrastructure;
  @Inject private ServiceOverrideService serviceOverrideService;
  @Inject private EnvGroupPlanCreatorHelper envGroupPlanCreatorHelper;
  @Inject private ServicePlanCreatorHelper servicePlanCreatorHelper;

  @Override
  public Set<String> getSupportedStageTypes() {
    return Collections.singleton("Deployment");
  }

  @Override
  public StepType getStepType(DeploymentStageNode stageElementConfig) {
    return DeploymentStageStep.STEP_TYPE;
  }

  @Override
  public SpecParameters getSpecParameters(String childNodeId, PlanCreationContext ctx, DeploymentStageNode stageNode) {
    return DeploymentStageStepParameters.getStepParameters(childNodeId);
  }

  @Override
  public Class<DeploymentStageNode> getFieldClass() {
    return DeploymentStageNode.class;
  }

  @Override
  public String getExecutionInputTemplateAndModifyYamlField(YamlField yamlField) {
    return RuntimeInputFormHelper.createExecutionInputFormAndUpdateYamlFieldForStage(
        yamlField.getNode().getParentNode().getCurrJsonNode());
  }

  @SneakyThrows
  @Override
  public PlanNode createPlanForParentNode(
      PlanCreationContext ctx, DeploymentStageNode stageNode, List<String> childrenNodeIds) {
    stageNode.setIdentifier(getIdentifierWithExpression(ctx, stageNode, stageNode.getIdentifier()));
    stageNode.setName(getIdentifierWithExpression(ctx, stageNode, stageNode.getName()));
    DeploymentStageConfig config = stageNode.getDeploymentStageConfig();
    StageElementParametersBuilder stageParameters = CdStepParametersUtils.getStageParameters(stageNode);
    YamlField specField =
        Preconditions.checkNotNull(ctx.getCurrentField().getNode().getField(YAMLFieldNameConstants.SPEC));
    stageParameters.specConfig(getSpecParameters(specField.getNode().getUuid(), ctx, stageNode));
    String uuid = MultiDeploymentSpawnerUtils.getUuidForMultiDeployment(stageNode);

    // We need to swap the ids if strategy is present
    PlanNodeBuilder builder =
        PlanNode.builder()
            .uuid(StrategyUtils.getSwappedPlanNodeId(ctx, uuid))
            .name(stageNode.getName())
            .identifier(stageNode.getIdentifier())
            .group(StepOutcomeGroup.STAGE.name())
            .stepParameters(stageParameters.build())
            .stepType(getStepType(stageNode))
            .skipCondition(SkipInfoUtils.getSkipCondition(stageNode.getSkipCondition()))
            .whenCondition(RunInfoUtils.getRunCondition(stageNode.getWhen()))
            .facilitatorObtainment(
                FacilitatorObtainment.newBuilder()
                    .setType(FacilitatorType.newBuilder().setType(OrchestrationFacilitatorType.CHILD).build())
                    .build())
            .adviserObtainments(getAdviserObtainmentFromMetaData(ctx.getCurrentField()));

    if (!EmptyPredicate.isEmpty(ctx.getExecutionInputTemplate())) {
      builder.executionInputTemplate(ctx.getExecutionInputTemplate());
    }
    return builder.build();
  }

  public String getIdentifierWithExpression(PlanCreationContext ctx, DeploymentStageNode node, String identifier) {
    if (node.getDeploymentStageConfig().getServices() != null
        || node.getDeploymentStageConfig().getEnvironments() != null
        || node.getDeploymentStageConfig().getEnvironmentGroup() != null) {
      return identifier + StrategyValidationUtils.STRATEGY_IDENTIFIER_POSTFIX;
    }
    return StrategyUtils.getIdentifierWithExpression(ctx, identifier);
  }
  @Override
  public LinkedHashMap<String, PlanCreationResponse> createPlanForChildrenNodes(
      PlanCreationContext ctx, DeploymentStageNode stageNode) {
    LinkedHashMap<String, PlanCreationResponse> planCreationResponseMap = new LinkedHashMap<>();
    try {
      // Validate Stage Failure strategy.
      validateFailureStrategy(stageNode);

      Map<String, ByteString> metadataMap = new HashMap<>();

      YamlField specField =
          Preconditions.checkNotNull(ctx.getCurrentField().getNode().getField(YAMLFieldNameConstants.SPEC));

      if (useNewFlow(ctx, stageNode)) {
        List<AdviserObtainment> adviserObtainments =
            addResourceConstraintDependencyWithWhenCondition(planCreationResponseMap, specField);
        String infraNodeId = addInfrastructureNode(planCreationResponseMap, stageNode, adviserObtainments);
        String serviceNodeId = addServiceNode(specField, planCreationResponseMap, stageNode, infraNodeId);
        addSpecNode(planCreationResponseMap, specField, serviceNodeId);
      } else {
        final String postServiceStepUuid = "service-" + UUIDGenerator.generateUuid();
        final String environmentUuid = "environment-" + UUIDGenerator.generateUuid();
        final YamlField serviceField = servicePlanCreatorHelper.getResolvedServiceField(specField, stageNode, ctx);

        PipelineInfrastructure pipelineInfrastructure = stageNode.getDeploymentStageConfig().getInfrastructure();

        final OverridesFromEnvironment overridesFromEnvironment =
            addEnvAndInfraDependency(ctx, stageNode, planCreationResponseMap, specField, pipelineInfrastructure,
                postServiceStepUuid, environmentUuid, environmentUuid, serviceField);
        addServiceDependency(planCreationResponseMap, specField, stageNode, environmentUuid, postServiceStepUuid,
            serviceField, overridesFromEnvironment);
      }

      // Add dependency for execution
      YamlField executionField = specField.getNode().getField(YAMLFieldNameConstants.EXECUTION);
      if (executionField == null) {
        throw new InvalidRequestException("Execution section cannot be absent in a pipeline");
      }
      addCDExecutionDependencies(planCreationResponseMap, executionField);
      addMultiDeploymentDependency(planCreationResponseMap, stageNode, ctx);

      StrategyUtils.addStrategyFieldDependencyIfPresent(kryoSerializer, ctx, stageNode.getUuid(), stageNode.getName(),
          stageNode.getIdentifier(), planCreationResponseMap, metadataMap,
          StrategyUtils.getAdviserObtainments(ctx.getCurrentField(), kryoSerializer, false), false);

      return planCreationResponseMap;
    } catch (IOException e) {
      throw new InvalidRequestException(
          "Invalid yaml for Deployment stage with identifier - " + stageNode.getIdentifier(), e);
    }
  }

  @Override
  public GraphLayoutResponse getLayoutNodeInfo(PlanCreationContext context, DeploymentStageNode config) {
    Map<String, GraphLayoutNode> stageYamlFieldMap = new LinkedHashMap<>();
    YamlField yamlField = context.getCurrentField();
    if (config.getDeploymentStageConfig().getServices() != null
        || config.getDeploymentStageConfig().getEnvironments() != null) {
      YamlField siblingField = yamlField.getNode().nextSiblingFromParentArray(
          yamlField.getName(), Arrays.asList(YAMLFieldNameConstants.STAGE, YAMLFieldNameConstants.PARALLEL));
      EdgeLayoutList edgeLayoutList;
      String planNodeId = MultiDeploymentSpawnerUtils.getUuidForMultiDeployment(config);
      if (siblingField == null) {
        edgeLayoutList = EdgeLayoutList.newBuilder().addCurrentNodeChildren(planNodeId).build();
      } else {
        edgeLayoutList = EdgeLayoutList.newBuilder()
                             .addNextIds(siblingField.getNode().getUuid())
                             .addCurrentNodeChildren(planNodeId)
                             .build();
      }
      stageYamlFieldMap.put(yamlField.getNode().getUuid(),
          GraphLayoutNode.newBuilder()
              .setNodeUUID(yamlField.getNode().getUuid())
              .setNodeType(StrategyType.MATRIX.name())
              .setName(yamlField.getNode().getName())
              .setNodeGroup(StepOutcomeGroup.STRATEGY.name())
              .setNodeIdentifier(yamlField.getNode().getIdentifier())
              .setEdgeLayoutList(edgeLayoutList)
              .build());
      stageYamlFieldMap.put(planNodeId,
          GraphLayoutNode.newBuilder()
              .setNodeUUID(planNodeId)
              .setNodeType(yamlField.getNode().getType())
              .setName(config.getName())
              .setNodeGroup(StepOutcomeGroup.STAGE.name())
              .setNodeIdentifier(config.getIdentifier())
              .setEdgeLayoutList(EdgeLayoutList.newBuilder().build())
              .build());
      return GraphLayoutResponse.builder().layoutNodes(stageYamlFieldMap).build();
    }
    YamlField stageYamlField = context.getCurrentField();
    if (StrategyUtils.isWrappedUnderStrategy(context.getCurrentField())) {
      stageYamlFieldMap = StrategyUtils.modifyStageLayoutNodeGraph(stageYamlField);
    }
    return GraphLayoutResponse.builder().layoutNodes(stageYamlFieldMap).build();
  }

  private List<AdviserObtainment> addResourceConstraintDependencyWithWhenCondition(
      LinkedHashMap<String, PlanCreationResponse> planCreationResponseMap, YamlField specField) {
    return InfrastructurePmsPlanCreator.addResourceConstraintDependency(
        planCreationResponseMap, specField, kryoSerializer);
  }

  private boolean useNewFlow(PlanCreationContext ctx, DeploymentStageNode stageNode) {
    final DeploymentStageConfig deploymentStageConfig = stageNode.getDeploymentStageConfig();
    // Todo(Yogesh): For gitops, use the old flow only, for now
    if (deploymentStageConfig.getGitOpsEnabled()) {
      return false;
    }
    boolean isServiceV2 = deploymentStageConfig.getService() != null
        && ParameterField.isNotNull(deploymentStageConfig.getService().getServiceRef());
    boolean serviceV2UseFromStage = deploymentStageConfig.getService() != null
        && deploymentStageConfig.getService().getUseFromStage() != null
        && EmptyPredicate.isNotEmpty(deploymentStageConfig.getService().getUseFromStage().getStage());
    boolean isServices = deploymentStageConfig.getServices() != null;
    return isServices || isServiceV2 || serviceV2UseFromStage;
  }

  private OverridesFromEnvironment addEnvAndInfraDependency(PlanCreationContext ctx, DeploymentStageNode stageNode,
      LinkedHashMap<String, PlanCreationResponse> planCreationResponseMap, YamlField specField,
      PipelineInfrastructure pipelineInfrastructure, String postServiceStepUuid, String environmentUuid,
      String envGroupUuid, YamlField serviceField) throws IOException {
    final OverridesFromEnvironmentBuilder overridesBuilder = OverridesFromEnvironment.builder();
    YamlField infraField = specField.getNode().getField(YamlTypes.PIPELINE_INFRASTRUCTURE);
    EnvironmentYamlV2 environmentV2 = stageNode.getDeploymentStageConfig().getEnvironment();
    EnvironmentsYaml environments = stageNode.getDeploymentStageConfig().getEnvironments();

    EnvironmentGroupYaml envGroupYaml = stageNode.getDeploymentStageConfig().getEnvironmentGroup();

    if (infraField != null && environmentV2 != null) {
      throw new InvalidRequestException("Infrastructure and Environment cannot be siblings of each other");
    }

    if (infraField == null && environmentV2 == null && envGroupYaml == null && environments == null) {
      throw new InvalidRequestException("Infrastructure Or Environment or Environment Group section is missing");
    }

    if (environmentV2 != null && environmentV2.getDeployToAll().isExpression()) {
      throw new InvalidRequestException("Value for deploy to all must be provided");
    }

    String serviceSpecNodeUuid = servicePlanCreatorHelper.fetchServiceSpecUuid(serviceField);

    if (infraField != null) {
      // Adding infrastructure node
      PlanNode infraStepNode = InfrastructurePmsPlanCreator.getInfraStepPlanNode(
          pipelineInfrastructure.getInfrastructureDefinition().getSpec());
      planCreationResponseMap.put(
          infraStepNode.getUuid(), PlanCreationResponse.builder().node(infraStepNode.getUuid(), infraStepNode).build());
      String infraSectionNodeChildId = infraStepNode.getUuid();

      if (InfrastructurePmsPlanCreator.isProvisionerConfigured(pipelineInfrastructure)) {
        planCreationResponseMap.putAll(InfrastructurePmsPlanCreator.createPlanForProvisioner(
            pipelineInfrastructure, infraField, infraStepNode.getUuid(), kryoSerializer));
        infraSectionNodeChildId = InfrastructurePmsPlanCreator.getProvisionerNodeId(infraField);
      }

      YamlField infrastructureDefField =
          Preconditions.checkNotNull(infraField.getNode().getField(YamlTypes.INFRASTRUCTURE_DEF));
      PlanNode infraDefPlanNode =
          InfrastructurePmsPlanCreator.getInfraDefPlanNode(infrastructureDefField, infraSectionNodeChildId);
      planCreationResponseMap.put(infraDefPlanNode.getUuid(),
          PlanCreationResponse.builder().node(infraDefPlanNode.getUuid(), infraDefPlanNode).build());

      YamlNode infraNode = infraField.getNode();
      planCreationResponseMap.putAll(InfrastructurePmsPlanCreator.createPlanForInfraSectionV1(
          infraNode, infraDefPlanNode.getUuid(), pipelineInfrastructure, kryoSerializer, infraNode.getUuid()));
    } else if (envGroupYaml != null) {
      final boolean gitOpsEnabled = isGitopsEnabled(stageNode.getDeploymentStageConfig());
      EnvGroupPlanCreatorConfig config =
          envGroupPlanCreatorHelper.createEnvGroupPlanCreatorConfig(ctx.getMetadata(), envGroupYaml);
      envGroupPlanCreatorHelper.addEnvironmentGroupDependency(planCreationResponseMap, config,
          specField.getNode().getField(YamlTypes.ENVIRONMENT_GROUP_YAML), gitOpsEnabled, envGroupUuid,
          postServiceStepUuid, serviceSpecNodeUuid);
    } else {
      final boolean gitOpsEnabled = isGitopsEnabled(stageNode.getDeploymentStageConfig());
      final boolean skipInstances = isSkipInstances(stageNode);
      String serviceRef;
      if (stageNode.getDeploymentStageConfig().getServices() != null) {
        serviceRef = SERVICE_REF_EXPRESSION;
      } else {
        serviceRef = servicePlanCreatorHelper.getServiceRef(serviceField);
      }
      EnvironmentPlanCreatorConfig environmentPlanCreatorConfig =
          EnvironmentPlanCreatorHelper.getResolvedEnvRefs(ctx.getMetadata(), environmentV2, gitOpsEnabled, serviceRef,
              serviceOverrideService, environmentService, infrastructure);

      overridesBuilder.serviceOverrideConfig(environmentPlanCreatorConfig.getServiceOverrideConfig());
      overridesBuilder.environmentGlobalOverride(environmentPlanCreatorConfig.getEnvironmentGlobalOverride());
      overridesBuilder.envIdentifier(environmentPlanCreatorConfig.getIdentifier());

      EnvironmentPlanCreatorHelper.addEnvironmentV2Dependency(planCreationResponseMap, environmentPlanCreatorConfig,
          specField.getNode().getField(YamlTypes.ENVIRONMENT_YAML), gitOpsEnabled, skipInstances, environmentUuid,
          postServiceStepUuid, serviceSpecNodeUuid, kryoSerializer);
    }
    return overridesBuilder.build();
  }

  private boolean isSkipInstances(DeploymentStageNode stageNode) {
    boolean value = false;
    if (null != stageNode.getSkipInstances()) {
      value = stageNode.getSkipInstances().getValue();
    }
    return value;
  }

  private void addMultiDeploymentDependency(LinkedHashMap<String, PlanCreationResponse> planCreationResponseMap,
      DeploymentStageNode stageNode, PlanCreationContext ctx) {
    DeploymentStageConfig stageConfig = stageNode.getDeploymentStageConfig();
    if (stageConfig.getServices() == null && stageConfig.getEnvironments() == null
        && stageConfig.getEnvironmentGroup() == null) {
      return;
    }

    String subType;
    if (stageConfig.getEnvironments() == null) {
      subType = MultiDeploymentSpawnerUtils.MULTI_SERVICE_DEPLOYMENT;
    } else if (stageConfig.getServices() == null) {
      subType = MultiDeploymentSpawnerUtils.MULTI_ENV_DEPLOYMENT;
    } else {
      subType = MultiDeploymentSpawnerUtils.MULTI_SERVICE_ENV_DEPLOYMENT;
    }
    if (stageConfig.getServices() != null && ParameterField.isBlank(stageConfig.getServices().getValues())) {
      throw new InvalidYamlException(
          "No values of services provided. Please provide at least one service for deployment");
    }
    if (stageConfig.getEnvironments() != null && ParameterField.isBlank(stageConfig.getEnvironments().getValues())) {
      throw new InvalidYamlException(
          "No values of environments provided. Please provide at least one service for deployment");
    }
    MultiDeploymentStepParameters stepParameters =
        MultiDeploymentStepParameters.builder()
            .strategyType(StrategyType.MATRIX)
            .childNodeId(MultiDeploymentSpawnerUtils.getUuidForMultiDeployment(stageNode))
            .environments(stageConfig.getEnvironments())
            .environmentGroup(stageConfig.getEnvironmentGroup())
            .services(stageConfig.getServices())
            .subType(subType)
            .build();

    MultiDeploymentMetadata metadata =
        MultiDeploymentMetadata.builder()
            .multiDeploymentNodeId(ctx.getCurrentField().getNode().getUuid())
            .multiDeploymentStepParameters(stepParameters)
            .strategyNodeIdentifier(stageNode.getIdentifier())
            .strategyNodeName(stageNode.getName())
            .adviserObtainments(StrategyUtils.getAdviserObtainments(ctx.getCurrentField(), kryoSerializer, false))
            .build();

    PlanNode node = MultiDeploymentStepPlanCreator.createPlan(metadata);
    planCreationResponseMap.put(UUIDGenerator.generateUuid(), PlanCreationResponse.builder().planNode(node).build());
  }

  // This function adds the service dependency and returns the resolved service field
  private void addServiceDependency(LinkedHashMap<String, PlanCreationResponse> planCreationResponseMap,
      YamlField specField, DeploymentStageNode stageNode, String environmentUuid, String infraSectionUuid,
      YamlField serviceField, OverridesFromEnvironment overridesFromEnvironment) throws IOException {
    // Adding service child by resolving the serviceField
    String serviceNodeUuid = serviceField.getNode().getUuid();

    // Adding Spec node
    planCreationResponseMap.put(specField.getNode().getUuid(),
        PlanCreationResponse.builder().dependencies(getDependenciesForSpecNode(specField, serviceNodeUuid)).build());

    // Adding dependency for service
    // Adding serviceField to yamlUpdates as its resolved value should be updated.
    planCreationResponseMap.put(serviceNodeUuid,
        PlanCreationResponse.builder()
            .dependencies(servicePlanCreatorHelper.getDependenciesForService(
                serviceField, stageNode, environmentUuid, infraSectionUuid, overridesFromEnvironment))
            .yamlUpdates(YamlUpdates.newBuilder()
                             .putFqnToYaml(serviceField.getYamlPath(),
                                 YamlUtils.writeYamlString(serviceField).replace("---\n", ""))
                             .build())
            .build());
  }

  private String addServiceNode(YamlField specField,
      LinkedHashMap<String, PlanCreationResponse> planCreationResponseMap, DeploymentStageNode stageNode,
      String nextNodeId) throws IOException {
    // Adding service child by resolving the serviceField
    ServiceDefinitionType deploymentType = stageNode.getDeploymentStageConfig().getDeploymentType();
    ServiceYamlV2 service;
    if (stageNode.getDeploymentStageConfig().getServices() != null) {
      service = MultiDeploymentSpawnerUtils.getServiceYamlV2Node();
    } else {
      service = stageNode.getDeploymentStageConfig().getService();
    }

    EnvironmentYamlV2 environment;
    if (stageNode.getDeploymentStageConfig().getEnvironments() != null
        || stageNode.getDeploymentStageConfig().getEnvironmentGroup() != null) {
      environment = MultiDeploymentSpawnerUtils.getEnvironmentYamlV2Node();
    } else {
      environment = stageNode.getDeploymentStageConfig().getEnvironment();
    }
    String serviceNodeId = service.getUuid();
    planCreationResponseMap.putAll(ServiceAllInOnePlanCreatorUtils.addServiceNode(
        specField, kryoSerializer, service, environment, serviceNodeId, nextNodeId, deploymentType));
    return serviceNodeId;
  }
  private String addInfrastructureNode(LinkedHashMap<String, PlanCreationResponse> planCreationResponseMap,
      DeploymentStageNode stageNode, List<AdviserObtainment> adviserObtainments) throws IOException {
    EnvironmentYamlV2 environment;
    if (stageNode.getDeploymentStageConfig().getEnvironments() != null
        || stageNode.getDeploymentStageConfig().getEnvironmentGroup() != null) {
      environment = MultiDeploymentSpawnerUtils.getEnvironmentYamlV2Node();
    } else {
      environment = stageNode.getDeploymentStageConfig().getEnvironment();
    }
    PlanNode node = InfrastructurePmsPlanCreator.getInfraTaskExecutableStepV2PlanNode(
        environment, adviserObtainments, stageNode.getDeploymentStageConfig().getDeploymentType());
    planCreationResponseMap.put(node.getUuid(), PlanCreationResponse.builder().planNode(node).build());
    return node.getUuid();
  }

  private void addSpecNode(
      LinkedHashMap<String, PlanCreationResponse> planCreationResponseMap, YamlField specField, String nextNodeId) {
    // Adding Spec node
    planCreationResponseMap.put(specField.getNode().getUuid(),
        PlanCreationResponse.builder().dependencies(getDependenciesForSpecNode(specField, nextNodeId)).build());
  }

  public Dependencies getDependenciesForSpecNode(YamlField specField, String childNodeUuid) {
    Map<String, YamlField> specYamlFieldMap = new HashMap<>();
    String specNodeUuid = specField.getNode().getUuid();
    specYamlFieldMap.put(specNodeUuid, specField);

    Map<String, ByteString> specDependencyMap = new HashMap<>();
    specDependencyMap.put(
        YAMLFieldNameConstants.CHILD_NODE_OF_SPEC, ByteString.copyFrom(kryoSerializer.asDeflatedBytes(childNodeUuid)));

    Dependency specDependency = Dependency.newBuilder().putAllMetadata(specDependencyMap).build();
    return DependenciesUtils.toDependenciesProto(specYamlFieldMap)
        .toBuilder()
        .putDependencyMetadata(specNodeUuid, specDependency)
        .build();
  }

  public void addCDExecutionDependencies(
      Map<String, PlanCreationResponse> planCreationResponseMap, YamlField executionField) {
    Map<String, YamlField> executionYamlFieldMap = new HashMap<>();
    executionYamlFieldMap.put(executionField.getNode().getUuid(), executionField);

    planCreationResponseMap.put(executionField.getNode().getUuid(),
        PlanCreationResponse.builder()
            .dependencies(DependenciesUtils.toDependenciesProto(executionYamlFieldMap))
            .build());
  }

  private void validateFailureStrategy(DeploymentStageNode stageNode) {
    // Failure strategy should be present.
    List<FailureStrategyConfig> stageFailureStrategies = stageNode.getFailureStrategies();
    if (EmptyPredicate.isEmpty(stageFailureStrategies)) {
      throw new InvalidRequestException("There should be at least one failure strategy configured at stage level.");
    }

    // checking stageFailureStrategies is having one strategy with error type as AllErrors and along with that no
    // error type is involved
    if (!GenericStepPMSPlanCreator.containsOnlyAllErrorsInSomeConfig(stageFailureStrategies)) {
      throw new InvalidRequestException(
          "There should be a Failure strategy that contains one error type as AllErrors, with no other error type along with it in that Failure Strategy.");
    }
  }

  private boolean isGitopsEnabled(DeploymentStageConfig deploymentStageConfig) {
    return deploymentStageConfig.getGitOpsEnabled();
  }
}
