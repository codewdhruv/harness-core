/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */
package io.harness.pms.pipeline.api;

import static io.harness.annotations.dev.HarnessTeam.PIPELINE;
import static io.harness.rule.OwnerRule.MANKRIT;

import static junit.framework.TestCase.assertEquals;
import static org.apache.commons.lang3.RandomStringUtils.randomAlphabetic;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import io.harness.CategoryTest;
import io.harness.annotations.dev.OwnedBy;
import io.harness.category.element.UnitTests;
import io.harness.engine.governance.PolicyEvaluationFailureException;
import io.harness.exception.InvalidRequestException;
import io.harness.git.model.ChangeType;
import io.harness.governance.GovernanceMetadata;
import io.harness.ng.core.template.TemplateMergeResponseDTO;
import io.harness.pms.pipeline.PipelineEntity;
import io.harness.pms.pipeline.PipelineEntity.PipelineEntityKeys;
import io.harness.pms.pipeline.service.PMSPipelineService;
import io.harness.pms.pipeline.service.PMSPipelineServiceHelper;
import io.harness.pms.pipeline.service.PMSPipelineTemplateHelper;
import io.harness.pms.pipeline.service.PipelineCRUDResult;
import io.harness.pms.pipeline.service.PipelineMetadataService;
import io.harness.rule.Owner;
import io.harness.spec.server.pipeline.model.PipelineCreateRequestBody;
import io.harness.spec.server.pipeline.model.PipelineGetResponseBody;
import io.harness.spec.server.pipeline.model.PipelineListResponseBody;
import io.harness.spec.server.pipeline.model.PipelineUpdateRequestBody;
import io.harness.yaml.validator.InvalidYamlException;

import com.google.common.io.Resources;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.ws.rs.core.Response;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Direction;

@OwnedBy(PIPELINE)
public class PipelinesApiImplTest extends CategoryTest {
  PipelinesApiImpl pipelinesApiImpl;
  @Mock PMSPipelineService pmsPipelineService;
  @Mock PMSPipelineServiceHelper pipelineServiceHelper;
  @Mock PMSPipelineTemplateHelper pipelineTemplateHelper;
  @Mock PipelineMetadataService pipelineMetadataService;

  String slug = "basichttpFail";
  String name = "basichttpFail";
  String account = randomAlphabetic(10);
  String org = randomAlphabetic(10);
  String project = randomAlphabetic(10);
  int page = 0;
  int limit = 1;
  PipelineEntity entity;
  PipelineEntity entityModified;
  private String yaml;

  @Before
  public void setup() throws IOException {
    MockitoAnnotations.initMocks(this);
    pipelinesApiImpl = new PipelinesApiImpl(
        pmsPipelineService, pipelineServiceHelper, pipelineTemplateHelper, pipelineMetadataService);
    ClassLoader classLoader = this.getClass().getClassLoader();
    String filename = "simplified-yaml.yaml";
    yaml = Resources.toString(Objects.requireNonNull(classLoader.getResource(filename)), StandardCharsets.UTF_8);
    entity = PipelineEntity.builder()
                 .accountId(account)
                 .orgIdentifier(org)
                 .projectIdentifier(project)
                 .identifier(slug)
                 .name(name)
                 .yaml(yaml)
                 .isDraft(false)
                 .allowStageExecutions(false)
                 .build();

    entityModified = PipelineEntity.builder()
                         .accountId(account)
                         .orgIdentifier(org)
                         .projectIdentifier(project)
                         .identifier(slug)
                         .name(name)
                         .yaml(yaml)
                         .stageCount(1)
                         .stageName("qaStage")
                         .allowStageExecutions(false)
                         .build();
  }

  @Test
  @Owner(developers = MANKRIT)
  @Category(UnitTests.class)
  public void testPipelineCreate() {
    PipelineCreateRequestBody pipelineRequestBody = new PipelineCreateRequestBody();
    pipelineRequestBody.setPipelineYaml(yaml);
    pipelineRequestBody.setSlug(slug);
    pipelineRequestBody.setName(name);
    when(pmsPipelineService.create(any()))
        .thenReturn(PipelineCRUDResult.builder()
                        .pipelineEntity(entity)
                        .governanceMetadata(GovernanceMetadata.newBuilder().setDeny(false).build())
                        .build());
    Response response = pipelinesApiImpl.createPipeline(pipelineRequestBody, org, project, account);
    assertEquals(slug, response.getEntity());
  }

  @Test
  @Owner(developers = MANKRIT)
  @Category(UnitTests.class)
  public void testPipelineDelete() {
    doReturn(true).when(pmsPipelineService).delete(account, org, project, slug, null);
    Response deleteResponse = pipelinesApiImpl.deletePipeline(org, project, slug, account);
    assertThat(deleteResponse.getStatus()).isEqualTo(204);
  }

  @Test
  @Owner(developers = MANKRIT)
  @Category(UnitTests.class)
  public void testPipelineDeleteFail() {
    doReturn(false).when(pmsPipelineService).delete(account, org, project, slug, null);
    try {
      pipelinesApiImpl.deletePipeline(org, project, slug, account);
    } catch (InvalidRequestException e) {
      assertEquals(e.getMessage(), String.format("Pipeline with identifier %s cannot be deleted.", slug));
    }
  }

  @Test
  @Owner(developers = MANKRIT)
  @Category(UnitTests.class)
  public void testPipelineUpdate() {
    GovernanceMetadata governanceMetadata = GovernanceMetadata.newBuilder().setDeny(false).build();
    PipelineCRUDResult pipelineCRUDResult =
        PipelineCRUDResult.builder().governanceMetadata(governanceMetadata).pipelineEntity(entityModified).build();
    doReturn(pipelineCRUDResult).when(pmsPipelineService).updatePipelineYaml(entity, ChangeType.MODIFY);
    TemplateMergeResponseDTO templateMergeResponseDTO =
        TemplateMergeResponseDTO.builder().mergedPipelineYaml(yaml).build();
    doReturn(templateMergeResponseDTO)
        .when(pipelineTemplateHelper)
        .resolveTemplateRefsInPipeline(account, org, project, yaml);
    PipelineUpdateRequestBody requestBody = new PipelineUpdateRequestBody();
    requestBody.setPipelineYaml(yaml);
    requestBody.setSlug(slug);
    requestBody.setName(name);
    Response response = pipelinesApiImpl.updatePipeline(requestBody, org, project, slug, account);
    assertThat(response.getEntity()).isEqualTo(slug);
  }

  @Test
  @Owner(developers = MANKRIT)
  @Category(UnitTests.class)
  public void testPipelineUpdateFail() {
    GovernanceMetadata governanceMetadata = GovernanceMetadata.newBuilder().setDeny(true).build();
    PipelineCRUDResult pipelineCRUDResult =
        PipelineCRUDResult.builder().governanceMetadata(governanceMetadata).pipelineEntity(entityModified).build();
    doReturn(pipelineCRUDResult).when(pmsPipelineService).updatePipelineYaml(entity, ChangeType.MODIFY);
    TemplateMergeResponseDTO templateMergeResponseDTO =
        TemplateMergeResponseDTO.builder().mergedPipelineYaml(yaml).build();
    doReturn(templateMergeResponseDTO)
        .when(pipelineTemplateHelper)
        .resolveTemplateRefsInPipeline(account, org, project, yaml);
    PipelineUpdateRequestBody requestBody = new PipelineUpdateRequestBody();
    requestBody.setPipelineYaml(yaml);
    requestBody.setSlug(slug);
    requestBody.setName(name);
    try {
      pipelinesApiImpl.updatePipeline(requestBody, org, project, slug, account);
    } catch (PolicyEvaluationFailureException e) {
      assertEquals(e.getMessage(), "Policy Evaluation Failure");
    }
  }

  @Test
  @Owner(developers = MANKRIT)
  @Category(UnitTests.class)
  public void testPipelineGetNoTemplates() {
    Optional<PipelineEntity> optional = Optional.ofNullable(entity);
    doReturn(optional).when(pmsPipelineService).get(account, org, project, slug, false);
    Response response = pipelinesApiImpl.getPipeline(org, project, slug, account, null, false);
    PipelineGetResponseBody responseBody = (PipelineGetResponseBody) response.getEntity();
    assertEquals(yaml, responseBody.getPipelineYaml());
    assertEquals(true, responseBody.isValid().booleanValue());
  }

  @Test
  @Owner(developers = MANKRIT)
  @Category(UnitTests.class)
  public void testPipelineGetWithTemplates() {
    Optional<PipelineEntity> optional = Optional.ofNullable(entity);
    doReturn(optional).when(pmsPipelineService).get(account, org, project, slug, false);
    String extraYaml = yaml + "extra";
    TemplateMergeResponseDTO templateMergeResponseDTO =
        TemplateMergeResponseDTO.builder().mergedPipelineYaml(extraYaml).build();
    doReturn(templateMergeResponseDTO).when(pipelineTemplateHelper).resolveTemplateRefsInPipeline(entity);
    Response response = pipelinesApiImpl.getPipeline(org, project, slug, account, null, true);
    PipelineGetResponseBody responseBody = (PipelineGetResponseBody) response.getEntity();
    assertEquals(extraYaml, responseBody.getTemplateAppliedPipelineYaml());
    assertEquals(true, responseBody.isValid().booleanValue());
  }

  @Test
  @Owner(developers = MANKRIT)
  @Category(UnitTests.class)
  public void testPipelineGetFailPolicyEvaluation() {
    doThrow(PolicyEvaluationFailureException.class).when(pmsPipelineService).get(account, org, project, slug, false);
    PipelineGetResponseBody response =
        (PipelineGetResponseBody) pipelinesApiImpl.getPipeline(org, project, slug, account, null, false).getEntity();
    assertEquals(false, response.isValid().booleanValue());
  }

  @Test
  @Owner(developers = MANKRIT)
  @Category(UnitTests.class)
  public void testPipelineGetFailInvalidYaml() {
    doThrow(InvalidYamlException.class).when(pmsPipelineService).get(account, org, project, slug, false);
    PipelineGetResponseBody response =
        (PipelineGetResponseBody) pipelinesApiImpl.getPipeline(org, project, slug, account, null, false).getEntity();
    assertEquals(false, response.isValid().booleanValue());
  }

  @Test
  @Owner(developers = MANKRIT)
  @Category(UnitTests.class)
  public void testPipelineList() {
    Pageable pageable = PageRequest.of(0, 10, Sort.by(Direction.DESC, PipelineEntityKeys.createdAt));
    Page<PipelineEntity> pipelineEntities = new PageImpl<>(Collections.singletonList(entityModified), pageable, 1);
    doReturn(pipelineEntities).when(pmsPipelineService).list(any(), any(), any(), any(), any(), any());
    doReturn(Collections.emptyMap())
        .when(pipelineMetadataService)
        .getMetadataForGivenPipelineIds(account, org, project, Collections.singletonList(slug));
    List<PipelineListResponseBody> content = (List<PipelineListResponseBody>) pipelinesApiImpl
                                                 .listPipelines(org, project, account, 0, 25, null, null, null, null,
                                                     null, null, null, null, null, null, null, null, null)
                                                 .getEntity();
    assertThat(content).isNotEmpty();
    assertThat(content.size()).isEqualTo(1);

    PipelineListResponseBody responseBody = content.get(0);
    assertThat(responseBody.getSlug()).isEqualTo(slug);
    assertThat(responseBody.getName()).isEqualTo(name);
  }
}