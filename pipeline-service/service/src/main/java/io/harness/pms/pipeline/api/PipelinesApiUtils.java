/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.pms.pipeline.api;

import static io.harness.data.structure.EmptyPredicate.isEmpty;
import static io.harness.data.structure.EmptyPredicate.isNotEmpty;

import static javax.ws.rs.core.UriBuilder.fromPath;

import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.ngexception.beans.yamlschema.NodeErrorInfo;
import io.harness.exception.ngexception.beans.yamlschema.YamlSchemaErrorDTO;
import io.harness.exception.ngexception.beans.yamlschema.YamlSchemaErrorWrapperDTO;
import io.harness.filter.FilterType;
import io.harness.gitsync.beans.StoreType;
import io.harness.gitsync.interceptor.GitEntityInfo;
import io.harness.gitsync.sdk.EntityGitDetails;
import io.harness.ng.core.common.beans.NGTag;
import io.harness.pms.contracts.plan.TriggerType;
import io.harness.pms.execution.ExecutionStatus;
import io.harness.pms.pipeline.ExecutionSummaryInfoDTO;
import io.harness.pms.pipeline.ExecutorInfoDTO;
import io.harness.pms.pipeline.PMSPipelineSummaryResponseDTO;
import io.harness.pms.pipeline.PipelineEntity;
import io.harness.pms.pipeline.PipelineFilterPropertiesDto;
import io.harness.pms.pipeline.mappers.PMSPipelineDtoMapper;
import io.harness.spec.server.pipeline.model.ExecutionSummary;
import io.harness.spec.server.pipeline.model.ExecutorInfo;
import io.harness.spec.server.pipeline.model.ExecutorInfo.TriggerTypeEnum;
import io.harness.spec.server.pipeline.model.GitCreateDetails;
import io.harness.spec.server.pipeline.model.GitDetails;
import io.harness.spec.server.pipeline.model.GitUpdateDetails;
import io.harness.spec.server.pipeline.model.NodeInfo;
import io.harness.spec.server.pipeline.model.PipelineCreateRequestBody;
import io.harness.spec.server.pipeline.model.PipelineGetResponseBody;
import io.harness.spec.server.pipeline.model.PipelineListResponseBody;
import io.harness.spec.server.pipeline.model.PipelineListResponseBody.StoreTypeEnum;
import io.harness.spec.server.pipeline.model.PipelineUpdateRequestBody;
import io.harness.spec.server.pipeline.model.RecentExecutionInfo;
import io.harness.spec.server.pipeline.model.RecentExecutionInfo.ExecutionStatusEnum;
import io.harness.spec.server.pipeline.model.YAMLSchemaErrorWrapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.ws.rs.core.Link;
import javax.ws.rs.core.Response.ResponseBuilder;
import org.bson.Document;

@OwnedBy(HarnessTeam.PIPELINE)
public class PipelinesApiUtils {
  public static GitDetails getGitDetails(EntityGitDetails entityGitDetails) {
    if (entityGitDetails == null) {
      return null;
    }
    GitDetails gitDetails = new GitDetails();
    gitDetails.setBranchName(entityGitDetails.getBranch());
    gitDetails.setCommitId(entityGitDetails.getCommitId());
    gitDetails.setFilePath(entityGitDetails.getFilePath());
    gitDetails.setEntityIdentifier(entityGitDetails.getObjectId());
    gitDetails.setFileUrl(entityGitDetails.getFileUrl());
    gitDetails.setRepoUrl(entityGitDetails.getRepoUrl());
    gitDetails.setRepoName(entityGitDetails.getRepoName());
    return gitDetails;
  }

  public static List<YAMLSchemaErrorWrapper> getListYAMLErrorWrapper(YamlSchemaErrorWrapperDTO errorWrapperDTO) {
    if (errorWrapperDTO == null) {
      return null;
    }
    return errorWrapperDTO.getSchemaErrors()
        .stream()
        .map(PipelinesApiUtils::getYAMLErrorWrapper)
        .collect(Collectors.toList());
  }

  public static YAMLSchemaErrorWrapper getYAMLErrorWrapper(YamlSchemaErrorDTO yamlSchemaErrorDTO) {
    YAMLSchemaErrorWrapper yamlSchemaErrorWrapper = new YAMLSchemaErrorWrapper();
    yamlSchemaErrorWrapper.setFqn(yamlSchemaErrorDTO.getFqn());
    yamlSchemaErrorWrapper.setMessage(yamlSchemaErrorDTO.getMessage());
    yamlSchemaErrorWrapper.setHintMessage(yamlSchemaErrorDTO.getHintMessage());
    yamlSchemaErrorWrapper.setMessageFqn(yamlSchemaErrorDTO.getMessageWithFQN());
    yamlSchemaErrorWrapper.setStageInfo(getNodeInfo(yamlSchemaErrorDTO.getStageInfo()));
    yamlSchemaErrorWrapper.setStepInfo(getNodeInfo(yamlSchemaErrorDTO.getStepInfo()));
    return yamlSchemaErrorWrapper;
  }

  public static NodeInfo getNodeInfo(NodeErrorInfo errorInfo) {
    if (errorInfo == null) {
      return null;
    }
    NodeInfo nodeInfo = new NodeInfo();
    nodeInfo.setFqn(errorInfo.getFqn());
    nodeInfo.setName(errorInfo.getName());
    nodeInfo.setSlug(errorInfo.getIdentifier());
    nodeInfo.setType(errorInfo.getType());
    return nodeInfo;
  }

  public static PipelineGetResponseBody getGetResponseBody(PipelineEntity pipelineEntity) {
    PipelineGetResponseBody pipelineGetResponseBody = new PipelineGetResponseBody();
    pipelineGetResponseBody.setPipelineYaml(pipelineEntity.getYaml());
    pipelineGetResponseBody.setGitDetails(getGitDetails(PMSPipelineDtoMapper.getEntityGitDetails(pipelineEntity)));
    pipelineGetResponseBody.setModules(getModules(pipelineEntity.getFilters().keySet()));
    pipelineGetResponseBody.setCreated(pipelineEntity.getCreatedAt());
    pipelineGetResponseBody.setUpdated(pipelineEntity.getLastUpdatedAt());
    pipelineGetResponseBody.setValid(true);
    return pipelineGetResponseBody;
  }

  public static List<String> getModules(Set<String> modules) {
    if (modules == null) {
      return null;
    }
    return new ArrayList<>(modules);
  }

  public static PipelineFilterPropertiesDto getFilterProperties(List<String> pipelineIds, String name,
      String description, List<String> tags, List<String> services, List<String> envs, String deploymentType,
      String repoName) {
    Document moduleProperties = getModuleProperties(services, envs, deploymentType, repoName);
    PipelineFilterPropertiesDto propertiesDto = PipelineFilterPropertiesDto.builder()
                                                    .pipelineTags(getPipelineTags(tags))
                                                    .pipelineIdentifiers(pipelineIds)
                                                    .name(name)
                                                    .description(description)
                                                    .moduleProperties(moduleProperties)
                                                    .build();
    propertiesDto.setTags(getTags(tags));
    propertiesDto.setFilterType(FilterType.PIPELINESETUP);
    return propertiesDto;
  }

  public static List<NGTag> getPipelineTags(List<String> tags) {
    if (isEmpty(tags)) {
      return null;
    }
    return tags.stream().map(PipelinesApiUtils::getNGTags).collect(Collectors.toList());
  }

  public static NGTag getNGTags(String tag) {
    String[] tagComps = tag.split(":");
    if (tagComps.length == 1) {
      return NGTag.builder().key(tagComps[0]).value("").build();
    }
    return NGTag.builder().key(tagComps[0]).value(tagComps[1]).build();
  }

  public static Map<String, String> getTags(List<String> tags) {
    if (isEmpty(tags)) {
      return null;
    }
    Map<String, String> map = new HashMap<>();
    for (String tag : tags) {
      String[] tagComps = tag.split(":");
      if (tagComps.length == 1) {
        map.put(tagComps[0], null);
      } else {
        map.put(tagComps[0], tagComps[1]);
      }
    }
    return map;
  }

  public static Document getModuleProperties(
      List<String> services, List<String> envs, String deploymentType, String repoName) {
    Map<String, Object> ci = new HashMap<>();
    Map<String, Object> cd = new HashMap<>();
    if (repoName != null) {
      ci.put("repoName", repoName);
    }
    if (deploymentType != null) {
      cd.put("deploymentTypes", deploymentType);
    }
    if (isNotEmpty(services)) {
      cd.put("serviceNames", services);
    }
    if (isNotEmpty(envs)) {
      cd.put("environmentNames", envs);
    }
    Map<String, Object> map = new HashMap<>();
    if (!ci.isEmpty()) {
      map.put("ci", ci);
    }
    if (!cd.isEmpty()) {
      map.put("cd", cd);
    }
    return (map.isEmpty()) ? null : new Document(map);
  }

  public static ResponseBuilder addLinksHeader(
      ResponseBuilder responseBuilder, String path, int currentResultCount, int page, int limit) {
    ArrayList<Link> links = new ArrayList();
    links.add(Link.fromUri(fromPath(path)
                               .queryParam("page", new Object[] {page})
                               .queryParam("page_size", new Object[] {limit})
                               .build(new Object[0]))
                  .rel("self")
                  .build(new Object[0]));
    if (page >= 1) {
      links.add(Link.fromUri(fromPath(path)
                                 .queryParam("page", new Object[] {page - 1})
                                 .queryParam("page_size", new Object[] {limit})
                                 .build(new Object[0]))
                    .rel("previous")
                    .build(new Object[0]));
    }

    if (limit == currentResultCount) {
      links.add(Link.fromUri(fromPath(path)
                                 .queryParam("page", new Object[] {page + 1})
                                 .queryParam("page_size", new Object[] {limit})
                                 .build(new Object[0]))
                    .rel("next")
                    .build(new Object[0]));
    }

    return responseBuilder.links((Link[]) links.toArray(new Link[links.size()]));
  }

  public static PipelineListResponseBody getPipelines(PMSPipelineSummaryResponseDTO pipelineDTO) {
    PipelineListResponseBody responseBody = new PipelineListResponseBody();
    responseBody.setSlug(pipelineDTO.getIdentifier());
    responseBody.setName(pipelineDTO.getName());
    responseBody.setDescription(pipelineDTO.getDescription());
    responseBody.setTags(pipelineDTO.getTags());
    responseBody.setCreated(pipelineDTO.getCreatedAt());
    responseBody.setUpdated(pipelineDTO.getLastUpdatedAt());
    if (pipelineDTO.getModules() != null) {
      responseBody.setModules(new ArrayList<>(pipelineDTO.getModules()));
    }
    responseBody.setExecutionSummary(getExecutionSummary(pipelineDTO.getExecutionSummaryInfo()));
    responseBody.setStoreType(getStoreType(pipelineDTO.getStoreType()));
    responseBody.setConnectorRef(pipelineDTO.getConnectorRef());
    responseBody.setValid((pipelineDTO.getIsDraft() == null) ? null : !pipelineDTO.getIsDraft());
    responseBody.setGitDetails(getGitDetails(pipelineDTO.getGitDetails()));
    if (pipelineDTO.getRecentExecutionsInfo() != null) {
      responseBody.setRecentExecutionInfo(pipelineDTO.getRecentExecutionsInfo()
                                              .stream()
                                              .map(PipelinesApiUtils::getRecentExecutionInfo)
                                              .collect(Collectors.toList()));
    }
    return responseBody;
  }

  public static ExecutionSummary getExecutionSummary(ExecutionSummaryInfoDTO executionSummaryInfo) {
    if (executionSummaryInfo == null) {
      return null;
    }
    ExecutionSummary executionSummary = new ExecutionSummary();
    executionSummary.setErrorsCount(executionSummaryInfo.getNumOfErrors());
    executionSummary.setDeploymentsCount(executionSummaryInfo.getDeployments());
    return executionSummary;
  }

  public static StoreTypeEnum getStoreType(StoreType storeType) {
    if (storeType == null) {
      return null;
    }
    if (storeType.equals(StoreType.INLINE)) {
      return StoreTypeEnum.INLINE;
    }
    if (storeType.equals(StoreType.REMOTE)) {
      return StoreTypeEnum.REMOTE;
    }
    return null;
  }

  public static RecentExecutionInfo getRecentExecutionInfo(
      io.harness.pms.pipeline.RecentExecutionInfoDTO executionInfo) {
    RecentExecutionInfo recentExecutionInfo = new RecentExecutionInfo();
    recentExecutionInfo.setRunNumber(executionInfo.getRunSequence());
    recentExecutionInfo.setExecutionId(executionInfo.getPlanExecutionId());
    recentExecutionInfo.setStarted(executionInfo.getStartTs());
    recentExecutionInfo.setEnded(executionInfo.getEndTs());
    recentExecutionInfo.setExecutionStatus(getExecutionStatus(executionInfo.getStatus()));
    recentExecutionInfo.setExecutorInfo(getExecutorInfo(executionInfo.getExecutorInfo()));
    return recentExecutionInfo;
  }

  public static ExecutionStatusEnum getExecutionStatus(ExecutionStatus executionStatus) {
    if (executionStatus == null) {
      return null;
    }
    return ExecutionStatusEnum.fromValue(executionStatus.getDisplayName());
  }

  public static ExecutorInfo getExecutorInfo(ExecutorInfoDTO infoDTO) {
    if (infoDTO == null) {
      return null;
    }
    ExecutorInfo executorInfo = new ExecutorInfo();
    executorInfo.setUsername(infoDTO.getUsername());
    executorInfo.setEmail(infoDTO.getEmail());
    executorInfo.setTriggerType(getTrigger(infoDTO.getTriggerType()));
    return executorInfo;
  }

  public static TriggerTypeEnum getTrigger(TriggerType triggerType) {
    if (triggerType == null) {
      return null;
    }
    switch (triggerType.getNumber()) {
      case 0:
        return TriggerTypeEnum.NOOP;
      case 1:
        return TriggerTypeEnum.MANUAL;
      case 2:
        return TriggerTypeEnum.WEBHOOK;
      case 3:
        return TriggerTypeEnum.WEBHOOK_CUSTOM;
      case 4:
        return TriggerTypeEnum.SCHEDULER_CRON;
      default:
        return null;
    }
  }

  public static List<String> getSorting(String field, String order) {
    if (field == null) {
      return null;
    }
    if (order == null || (!order.equalsIgnoreCase("asc") && !order.equalsIgnoreCase("desc"))) {
      throw new InvalidRequestException("Order of sorting unidentified or null. Accepted values: ASC / DESC");
    }
    switch (field) {
      case "slug":
        field = "identifier";
        break;
      case "name":
        break;
      case "created":
        field = "createdAt";
        break;
      case "updated":
        field = "lastUpdatedAt";
        break;
      default:
        throw new InvalidRequestException(
            "Field provided for sorting unidentified. Accepted values: slug / name / created / updated");
    }
    return new ArrayList<>(Collections.singleton(field + "," + order));
  }

  public static GitEntityInfo populateGitCreateDetails(GitCreateDetails gitDetails) {
    if (gitDetails == null) {
      return GitEntityInfo.builder().build();
    }
    return GitEntityInfo.builder()
        .branch(gitDetails.getBranchName())
        .filePath(gitDetails.getFilePath())
        .commitMsg(gitDetails.getCommitMessage())
        .isNewBranch(gitDetails.getBranchName() != null && gitDetails.getBaseBranch() != null)
        .baseBranch(gitDetails.getBaseBranch())
        .connectorRef(gitDetails.getConnectorRef())
        .storeType(StoreType.getFromStringOrNull(gitDetails.getStoreType().toString()))
        .repoName(gitDetails.getRepoName())
        .build();
  }

  public static GitEntityInfo populateGitUpdateDetails(GitUpdateDetails gitDetails) {
    if (gitDetails == null) {
      return GitEntityInfo.builder().build();
    }
    return GitEntityInfo.builder()
        .branch(gitDetails.getBranchName())
        .commitMsg(gitDetails.getCommitMessage())
        .isNewBranch(gitDetails.getBranchName() != null && gitDetails.getBaseBranch() != null)
        .baseBranch(gitDetails.getBaseBranch())
        .lastCommitId(gitDetails.getLastCommitId())
        .lastObjectId(gitDetails.getLastObjectId())
        .build();
  }

  public static PipelineRequestInfoDTO mapCreateToRequestInfoDTO(PipelineCreateRequestBody createRequestBody) {
    if (createRequestBody == null) {
      throw new InvalidRequestException("Create Request Body cannot be null.");
    }
    return PipelineRequestInfoDTO.builder()
        .identifier(createRequestBody.getSlug())
        .name(createRequestBody.getName())
        .yaml(createRequestBody.getPipelineYaml())
        .description(createRequestBody.getDescription())
        .tags(createRequestBody.getTags())
        .build();
  }

  public static PipelineRequestInfoDTO mapUpdateToRequestInfoDTO(PipelineUpdateRequestBody updateRequestBody) {
    if (updateRequestBody == null) {
      throw new InvalidRequestException("Update Request Body cannot be null.");
    }
    return PipelineRequestInfoDTO.builder()
        .identifier(updateRequestBody.getSlug())
        .name(updateRequestBody.getName())
        .yaml(updateRequestBody.getPipelineYaml())
        .description(updateRequestBody.getDescription())
        .tags(updateRequestBody.getTags())
        .build();
  }
}
