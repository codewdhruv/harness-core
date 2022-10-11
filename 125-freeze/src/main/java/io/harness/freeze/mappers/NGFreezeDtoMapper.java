/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.freeze.mappers;

import io.harness.data.structure.EmptyPredicate;
import io.harness.encryption.Scope;
import io.harness.exception.InvalidRequestException;
import io.harness.freeze.beans.FreezeType;
import io.harness.freeze.beans.response.FreezeResponseDTO;
import io.harness.freeze.beans.response.FreezeSummaryResponseDTO;
import io.harness.freeze.beans.yaml.FreezeConfig;
import io.harness.freeze.entity.FreezeConfigEntity;
import io.harness.freeze.helpers.FreezeTimeUtils;
import io.harness.ng.core.mapper.TagMapper;
import io.harness.utils.YamlPipelineUtils;

import java.io.IOException;
import lombok.experimental.UtilityClass;

@UtilityClass
public class NGFreezeDtoMapper {
  public FreezeConfigEntity toFreezeConfigEntity(
      String accountId, String orgId, String projectId, String freezeConfigYaml, FreezeType type) {
    FreezeConfig freezeConfig = toFreezeConfig(freezeConfigYaml);
    return toFreezeConfigEntityResponse(accountId, freezeConfig, freezeConfigYaml, type, orgId, projectId);
  }

  public FreezeConfigEntity toFreezeConfigEntityGlobal(
      String accountId, String orgId, String projectId, String freezeConfigYaml) {
    return toFreezeConfigEntity(accountId, orgId, projectId, freezeConfigYaml, FreezeType.GLOBAL);
  }

  public FreezeConfigEntity toFreezeConfigEntityManual(
      String accountId, String orgId, String projectId, String freezeConfigYaml) {
    return toFreezeConfigEntity(accountId, orgId, projectId, freezeConfigYaml, FreezeType.MANUAL);
  }

  public FreezeConfig toFreezeConfig(String freezeConfigYaml) {
    try {
      return YamlPipelineUtils.read(freezeConfigYaml, FreezeConfig.class);
    } catch (IOException e) {
      throw new InvalidRequestException("Cannot create template entity due to " + e.getMessage());
    }
  }

  public FreezeResponseDTO prepareFreezeResponseDto(FreezeConfigEntity freezeConfigEntity) {
    return FreezeResponseDTO.builder()
        .accountId(freezeConfigEntity.getAccountId())
        .orgIdentifier(freezeConfigEntity.getOrgIdentifier())
        .projectIdentifier(freezeConfigEntity.getProjectIdentifier())
        .yaml(freezeConfigEntity.getYaml())
        .identifier(freezeConfigEntity.getIdentifier())
        .description(freezeConfigEntity.getDescription())
        .name(freezeConfigEntity.getName())
        .status(freezeConfigEntity.getStatus())
        .freezeScope(freezeConfigEntity.getFreezeScope())
        .tags(TagMapper.convertToMap(freezeConfigEntity.getTags()))
        .lastUpdatedAt(freezeConfigEntity.getLastUpdatedAt())
        .createdAt(freezeConfigEntity.getCreatedAt())
        .type(freezeConfigEntity.getType())
        .lastUpdatedAt(freezeConfigEntity.getLastUpdatedAt())
        .build();
  }

  public FreezeSummaryResponseDTO prepareFreezeResponseSummaryDto(FreezeConfigEntity freezeConfigEntity) {
    FreezeConfig freezeConfig = toFreezeConfig(freezeConfigEntity.getYaml());
    return FreezeSummaryResponseDTO.builder()
        .accountId(freezeConfigEntity.getAccountId())
        .orgIdentifier(freezeConfigEntity.getOrgIdentifier())
        .projectIdentifier(freezeConfigEntity.getProjectIdentifier())
        .freezeWindows(freezeConfig.getFreezeInfoConfig().getWindows())
        .rules(freezeConfig.getFreezeInfoConfig().getRules())
        .identifier(freezeConfigEntity.getIdentifier())
        .description(freezeConfigEntity.getDescription())
        .name(freezeConfigEntity.getName())
        .status(freezeConfigEntity.getStatus())
        .freezeScope(freezeConfigEntity.getFreezeScope())
        .tags(TagMapper.convertToMap(freezeConfigEntity.getTags()))
        .lastUpdatedAt(freezeConfigEntity.getLastUpdatedAt())
        .createdAt(freezeConfigEntity.getCreatedAt())
        .type(freezeConfigEntity.getType())
        .lastUpdatedAt(freezeConfigEntity.getLastUpdatedAt())
        .currentOrUpcomingActiveWindow(
            FreezeTimeUtils.fetchCurrentOrUpcomingTimeWindow(freezeConfig.getFreezeInfoConfig().getWindows()))
        .build();
  }

  public String toYaml(FreezeConfig freezeConfig) {
    return YamlPipelineUtils.writeYamlString(freezeConfig);
  }

  private FreezeConfigEntity toFreezeConfigEntityResponse(String accountId, FreezeConfig freezeConfig,
      String freezeConfigYaml, FreezeType type, String orgId, String projectId) {
    //    validateFreezeYaml(freezeConfig, orgId, projectId);
    String description = null;
    if (freezeConfig.getFreezeInfoConfig().getDescription() != null) {
      description = (String) freezeConfig.getFreezeInfoConfig().getDescription().fetchFinalValue();
      description = description == null ? "" : description;
    }
    return FreezeConfigEntity.builder()
        .yaml(freezeConfigYaml)
        .identifier(freezeConfig.getFreezeInfoConfig().getIdentifier())
        .accountId(accountId)
        .orgIdentifier(orgId)
        .projectIdentifier(projectId)
        .name(freezeConfig.getFreezeInfoConfig().getName())
        .status(freezeConfig.getFreezeInfoConfig().getStatus())
        .description(description)
        .tags(TagMapper.convertToList(freezeConfig.getFreezeInfoConfig().getTags()))
        .type(type)
        .freezeScope(getScopeFromFreezeDto(orgId, projectId))
        .build();
  }

  public Scope getScopeFromFreezeDto(String orgId, String projId) {
    if (EmptyPredicate.isNotEmpty(projId)) {
      return Scope.PROJECT;
    }
    if (EmptyPredicate.isNotEmpty(orgId)) {
      return Scope.ORG;
    }
    return Scope.ACCOUNT;
  }
}
