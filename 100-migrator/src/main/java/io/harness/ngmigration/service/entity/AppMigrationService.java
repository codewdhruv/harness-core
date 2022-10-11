/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.ngmigration.service.entity;

import io.harness.beans.MigratedEntityMapping;
import io.harness.data.structure.EmptyPredicate;
import io.harness.gitsync.beans.YamlDTO;
import io.harness.ngmigration.beans.BaseEntityInput;
import io.harness.ngmigration.beans.MigrationInputDTO;
import io.harness.ngmigration.beans.NGYamlFile;
import io.harness.ngmigration.beans.NgEntityDetail;
import io.harness.ngmigration.service.NgMigrationService;
import io.harness.persistence.HPersistence;

import software.wings.beans.Application;
import software.wings.beans.Service;
import software.wings.ngmigration.CgEntityId;
import software.wings.ngmigration.CgEntityNode;
import software.wings.ngmigration.DiscoveryNode;
import software.wings.ngmigration.NGMigrationEntity;
import software.wings.ngmigration.NGMigrationEntityType;
import software.wings.ngmigration.NGMigrationStatus;
import software.wings.service.intfc.AppService;
import software.wings.service.intfc.EnvironmentService;
import software.wings.service.intfc.ServiceResourceService;

import com.google.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class AppMigrationService extends NgMigrationService {
  @Inject private AppService appService;
  @Inject private HPersistence hPersistence;
  @Inject private EnvironmentService environmentService;
  @Inject private ServiceResourceService serviceResourceService;

  @Override
  public MigratedEntityMapping generateMappingEntity(NGYamlFile yamlFile) {
    return null;
  }

  @Override
  public DiscoveryNode discover(NGMigrationEntity entity) {
    if (entity == null) {
      return null;
    }
    Application application = (Application) entity;
    String appId = application.getUuid();

    Set<CgEntityId> children = new HashSet<>();

    // For now we will not discover pipelines.
    //    List<Pipeline> pipelines = hPersistence.createQuery(Pipeline.class)
    //                                   .filter(PipelineKeys.accountId, application.getAccountId())
    //                                   .filter(PipelineKeys.appId, appId)
    //                                   .project(PipelineKeys.uuid, true)
    //                                   .asList();
    //    children.addAll(pipelines.stream()
    //                        .map(Pipeline::getUuid)
    //                        .distinct()
    //                        .map(id -> CgEntityId.builder().id(id).type(NGMigrationEntityType.PIPELINE).build())
    //                        .collect(Collectors.toSet()));

    List<Service> services = serviceResourceService.findServicesByAppInternal(appId);
    if (EmptyPredicate.isNotEmpty(services)) {
      children.addAll(services.stream()
                          .map(Service::getUuid)
                          .distinct()
                          .map(id -> CgEntityId.builder().id(id).type(NGMigrationEntityType.SERVICE).build())
                          .collect(Collectors.toSet()));
    }

    List<String> environments = environmentService.getEnvIdsByApp(appId);
    if (EmptyPredicate.isNotEmpty(environments)) {
      children.addAll(environments.stream()
                          .distinct()
                          .map(id -> CgEntityId.builder().id(id).type(NGMigrationEntityType.ENVIRONMENT).build())
                          .collect(Collectors.toSet()));
    }

    return DiscoveryNode.builder()
        .entityNode(CgEntityNode.builder()
                        .id(appId)
                        .appId(appId)
                        .type(NGMigrationEntityType.APPLICATION)
                        .entity(entity)
                        .entityId(CgEntityId.builder().type(NGMigrationEntityType.APPLICATION).id(appId).build())
                        .build())
        .children(children)
        .build();
  }

  @Override
  public DiscoveryNode discover(String accountId, String appId, String entityId) {
    return discover(appService.get(entityId));
  }

  @Override
  public NGMigrationStatus canMigrate(NGMigrationEntity entity) {
    return NGMigrationStatus.builder().status(true).build();
  }

  @Override
  public List<NGYamlFile> generateYaml(MigrationInputDTO inputDTO, Map<CgEntityId, CgEntityNode> entities,
      Map<CgEntityId, Set<CgEntityId>> graph, CgEntityId entityId, Map<CgEntityId, NGYamlFile> migratedEntities,
      NgEntityDetail ngEntityDetail) {
    return new ArrayList<>();
  }

  @Override
  protected YamlDTO getNGEntity(NgEntityDetail ngEntityDetail, String accountIdentifier) {
    return null;
  }

  @Override
  protected boolean isNGEntityExists() {
    return false;
  }

  @Override
  public BaseEntityInput generateInput(
      Map<CgEntityId, CgEntityNode> entities, Map<CgEntityId, Set<CgEntityId>> graph, CgEntityId entityId) {
    return null;
  }
}
