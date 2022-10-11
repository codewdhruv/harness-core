/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.ngmigration.service.entity;

import static io.harness.beans.EnvironmentType.PROD;
import static io.harness.ng.core.environment.beans.EnvironmentType.PreProduction;
import static io.harness.ng.core.environment.beans.EnvironmentType.Production;

import static software.wings.beans.ServiceVariableType.ENCRYPTED_TEXT;
import static software.wings.service.intfc.ServiceVariableService.EncryptedFieldMode.OBTAIN_VALUE;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.beans.MigratedEntityMapping;
import io.harness.cdng.environment.yaml.EnvironmentYaml;
import io.harness.connector.ConnectorResponseDTO;
import io.harness.data.structure.EmptyPredicate;
import io.harness.encryption.Scope;
import io.harness.gitsync.beans.YamlDTO;
import io.harness.ng.core.dto.ResponseDTO;
import io.harness.ng.core.environment.beans.EnvironmentType;
import io.harness.ng.core.environment.dto.EnvironmentRequestDTO;
import io.harness.ng.core.environment.yaml.NGEnvironmentConfig;
import io.harness.ng.core.environment.yaml.NGEnvironmentInfoConfig;
import io.harness.ngmigration.beans.BaseEntityInput;
import io.harness.ngmigration.beans.BaseInputDefinition;
import io.harness.ngmigration.beans.MigrationInputDTO;
import io.harness.ngmigration.beans.MigratorInputType;
import io.harness.ngmigration.beans.NGYamlFile;
import io.harness.ngmigration.beans.NgEntityDetail;
import io.harness.ngmigration.client.NGClient;
import io.harness.ngmigration.client.PmsClient;
import io.harness.ngmigration.dto.ImportError;
import io.harness.ngmigration.dto.MigrationImportSummaryDTO;
import io.harness.ngmigration.service.MigratorMappingService;
import io.harness.ngmigration.service.MigratorUtility;
import io.harness.ngmigration.service.NgMigrationService;
import io.harness.serializer.JsonUtils;
import io.harness.yaml.core.variables.NGVariable;

import software.wings.beans.Environment;
import software.wings.beans.ServiceVariable;
import software.wings.infra.InfrastructureDefinition;
import software.wings.ngmigration.CgBasicInfo;
import software.wings.ngmigration.CgEntityId;
import software.wings.ngmigration.CgEntityNode;
import software.wings.ngmigration.DiscoveryNode;
import software.wings.ngmigration.NGMigrationEntity;
import software.wings.ngmigration.NGMigrationEntityType;
import software.wings.ngmigration.NGMigrationStatus;
import software.wings.service.intfc.EnvironmentService;
import software.wings.service.intfc.InfrastructureDefinitionService;
import software.wings.service.intfc.ServiceVariableService;

import com.google.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import retrofit2.Response;

@Slf4j
@OwnedBy(HarnessTeam.CDC)
public class EnvironmentMigrationService extends NgMigrationService {
  @Inject private EnvironmentService environmentService;
  @Inject private InfrastructureDefinitionService infrastructureDefinitionService;
  @Inject private ServiceVariableService serviceVariableService;

  @Override
  public MigratedEntityMapping generateMappingEntity(NGYamlFile yamlFile) {
    // TODO: @deepakputhraya fix org & project identifier.
    CgBasicInfo basicInfo = yamlFile.getCgBasicInfo();
    NGEnvironmentConfig environmentYaml = (NGEnvironmentConfig) yamlFile.getYaml();
    return MigratedEntityMapping.builder()
        .appId(basicInfo.getAppId())
        .accountId(basicInfo.getAccountId())
        .cgEntityId(basicInfo.getId())
        .entityType(NGMigrationEntityType.ENVIRONMENT.name())
        .accountIdentifier(basicInfo.getAccountId())
        .orgIdentifier(null)
        .projectIdentifier(null)
        .identifier(environmentYaml.getNgEnvironmentInfoConfig().getIdentifier())
        .scope(Scope.PROJECT)
        .fullyQualifiedIdentifier(MigratorMappingService.getFullyQualifiedIdentifier(
            basicInfo.getAccountId(), null, null, environmentYaml.getNgEnvironmentInfoConfig().getIdentifier()))
        .build();
  }

  @Override
  public DiscoveryNode discover(NGMigrationEntity entity) {
    if (entity == null) {
      return null;
    }
    Environment environment = (Environment) entity;
    String entityId = environment.getUuid();
    CgEntityId environmentEntityId = CgEntityId.builder().type(NGMigrationEntityType.ENVIRONMENT).id(entityId).build();
    CgEntityNode environmentNode = CgEntityNode.builder()
                                       .id(entityId)
                                       .appId(environment.getAppId())
                                       .type(NGMigrationEntityType.ENVIRONMENT)
                                       .entityId(environmentEntityId)
                                       .entity(environment)
                                       .build();

    Set<CgEntityId> children = new HashSet<>();
    List<InfrastructureDefinition> infraDefs = infrastructureDefinitionService.getNameAndIdForEnvironments(
        environment.getAppId(), Collections.singletonList(entityId));
    if (EmptyPredicate.isNotEmpty(infraDefs)) {
      children.addAll(
          infraDefs.stream()
              .map(infra -> CgEntityId.builder().id(infra.getUuid()).type(NGMigrationEntityType.INFRA).build())
              .collect(Collectors.toSet()));
    }
    List<ServiceVariable> serviceVariablesForAllServices = serviceVariableService.getServiceVariablesForEntity(
        environment.getAppId(), environment.getUuid(), OBTAIN_VALUE);
    if (EmptyPredicate.isNotEmpty(serviceVariablesForAllServices)) {
      children.addAll(serviceVariablesForAllServices.stream()
                          .filter(serviceVariable -> serviceVariable.getType().equals(ENCRYPTED_TEXT))
                          .map(serviceVariable
                              -> CgEntityId.builder()
                                     .type(NGMigrationEntityType.SECRET)
                                     .id(serviceVariable.getEncryptedValue())
                                     .build())
                          .collect(Collectors.toList()));
    }
    return DiscoveryNode.builder().children(children).entityNode(environmentNode).build();
  }

  @Override
  public DiscoveryNode discover(String accountId, String appId, String entityId) {
    return discover(environmentService.get(appId, entityId));
  }

  @Override
  public NGMigrationStatus canMigrate(NGMigrationEntity entity) {
    return NGMigrationStatus.builder().status(true).build();
  }

  @Override
  public NGMigrationStatus canMigrate(
      Map<CgEntityId, CgEntityNode> entities, Map<CgEntityId, Set<CgEntityId>> graph, CgEntityId entityId) {
    return NGMigrationStatus.builder().status(true).build();
  }

  @Override
  public MigrationImportSummaryDTO migrate(String auth, NGClient ngClient, PmsClient pmsClient,
      MigrationInputDTO inputDTO, NGYamlFile yamlFile) throws IOException {
    if (yamlFile.isExists()) {
      return MigrationImportSummaryDTO.builder()
          .errors(
              Collections.singletonList(ImportError.builder()
                                            .message("Environment was not migrated as it was already imported before")
                                            .entity(yamlFile.getCgBasicInfo())
                                            .build()))
          .build();
    }
    NGEnvironmentInfoConfig environmentConfig = ((NGEnvironmentConfig) yamlFile.getYaml()).getNgEnvironmentInfoConfig();
    EnvironmentRequestDTO environmentRequestDTO = EnvironmentRequestDTO.builder()
                                                      .identifier(environmentConfig.getIdentifier())
                                                      .type(environmentConfig.getType())
                                                      .orgIdentifier(environmentConfig.getOrgIdentifier())
                                                      .projectIdentifier(environmentConfig.getProjectIdentifier())
                                                      .name(environmentConfig.getName())
                                                      .tags(environmentConfig.getTags())
                                                      .description(environmentConfig.getDescription())
                                                      .yaml(getYamlString(yamlFile))
                                                      .build();
    Response<ResponseDTO<ConnectorResponseDTO>> resp =
        ngClient.createEnvironment(auth, inputDTO.getAccountIdentifier(), JsonUtils.asTree(environmentRequestDTO))
            .execute();
    log.info("Environment creation Response details {} {}", resp.code(), resp.message());
    return handleResp(yamlFile, resp);
  }

  @Override
  public List<NGYamlFile> generateYaml(MigrationInputDTO inputDTO, Map<CgEntityId, CgEntityNode> entities,
      Map<CgEntityId, Set<CgEntityId>> graph, CgEntityId entityId, Map<CgEntityId, NGYamlFile> migratedEntities,
      NgEntityDetail ngEntityDetail) {
    Environment environment = (Environment) entities.get(entityId).getEntity();
    String name = MigratorUtility.generateName(inputDTO.getOverrides(), entityId, environment.getName());
    String identifier = MigratorUtility.generateIdentifierDefaultName(inputDTO.getOverrides(), entityId, name);
    String projectIdentifier = MigratorUtility.getProjectIdentifier(Scope.PROJECT, inputDTO);
    String orgIdentifier = MigratorUtility.getOrgIdentifier(Scope.PROJECT, inputDTO);
    List<ServiceVariable> serviceVariablesForAllServices = serviceVariableService.getServiceVariablesForEntity(
        environment.getAppId(), environment.getUuid(), OBTAIN_VALUE);

    NGEnvironmentConfig environmentConfig =
        NGEnvironmentConfig.builder()
            .ngEnvironmentInfoConfig(
                NGEnvironmentInfoConfig.builder()
                    .name(name)
                    .identifier(identifier)
                    .description(environment.getDescription())
                    .tags(null)
                    .orgIdentifier(orgIdentifier)
                    .projectIdentifier(projectIdentifier)
                    .variables(getGlobalVariables(migratedEntities, serviceVariablesForAllServices))
                    .type(PROD == environment.getEnvironmentType() ? Production : PreProduction)
                    .build())
            .build();

    List<NGYamlFile> files = new ArrayList<>();
    NGYamlFile ngYamlFile = NGYamlFile.builder()
                                .filename(String.format("environment/%s/%s.yaml", environment.getAppId(), name))
                                .yaml(environmentConfig)
                                .ngEntityDetail(NgEntityDetail.builder()
                                                    .identifier(identifier)
                                                    .orgIdentifier(inputDTO.getOrgIdentifier())
                                                    .projectIdentifier(inputDTO.getProjectIdentifier())
                                                    .build())
                                .type(NGMigrationEntityType.ENVIRONMENT)
                                .cgBasicInfo(environment.getCgBasicInfo())
                                .build();
    files.add(ngYamlFile);

    migratedEntities.putIfAbsent(entityId, ngYamlFile);

    return files;
  }

  private List<NGVariable> getGlobalVariables(
      Map<CgEntityId, NGYamlFile> migratedEntities, List<ServiceVariable> serviceVariablesForAllServices) {
    List<NGVariable> variables = new ArrayList<>();
    if (EmptyPredicate.isNotEmpty(serviceVariablesForAllServices)) {
      variables.addAll(MigratorUtility.getVariables(
          serviceVariablesForAllServices.stream()
              .filter(serviceVariable -> StringUtils.isBlank(serviceVariable.getServiceId()))
              .collect(Collectors.toList()),
          migratedEntities));
    }
    return variables;
  }

  @Override
  protected YamlDTO getNGEntity(NgEntityDetail ngEntityDetail, String accountIdentifier) {
    return null;
  }

  @Override
  protected boolean isNGEntityExists() {
    return true;
  }

  @Override
  public BaseEntityInput generateInput(
      Map<CgEntityId, CgEntityNode> entities, Map<CgEntityId, Set<CgEntityId>> graph, CgEntityId entityId) {
    Environment environment = (Environment) entities.get(entityId).getEntity();
    return BaseEntityInput.builder()
        .migrationStatus(MigratorInputType.CREATE_NEW)
        .identifier(BaseInputDefinition.buildIdentifier(MigratorUtility.generateIdentifier(environment.getName())))
        .name(BaseInputDefinition.buildName(environment.getName()))
        .spec(null)
        .build();
  }

  public EnvironmentYaml getEnvironmentYaml(MigrationInputDTO inputDTO, Map<CgEntityId, CgEntityNode> entities,
      Map<CgEntityId, Set<CgEntityId>> graph, CgEntityId entityId) {
    Environment environment = (Environment) entities.get(entityId).getEntity();
    EnvironmentType environmentType = (environment.getEnvironmentType() == PROD) ? Production : PreProduction;
    return EnvironmentYaml.builder()
        .name(environment.getName())
        .identifier(environment.getName())
        .type(environmentType)
        .tags(null)
        .build();
  }
}
