/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.template.remote;

import io.harness.NGCommonEntityConstants;
import io.harness.NGResourceFilterConstants;
import io.harness.annotations.dev.HarnessTeam;
import io.harness.annotations.dev.OwnedBy;
import io.harness.eventsframework.schemas.entity.EntityDetailProtoDTO;
import io.harness.gitsync.sdk.GitSyncApiConstants;
import io.harness.ng.beans.PageResponse;
import io.harness.ng.core.dto.ResponseDTO;
import io.harness.ng.core.template.RefreshRequestDTO;
import io.harness.ng.core.template.RefreshResponseDTO;
import io.harness.ng.core.template.TemplateApplyRequestDTO;
import io.harness.ng.core.template.TemplateListType;
import io.harness.ng.core.template.TemplateMergeResponseDTO;
import io.harness.ng.core.template.TemplateReferenceRequestDTO;
import io.harness.ng.core.template.TemplateSummaryResponseDTO;
import io.harness.template.TemplateFilterPropertiesDTO;
import io.harness.template.beans.TemplateResponseDTO;
import io.harness.template.beans.refresh.ValidateTemplateInputsResponseDTO;
import io.harness.template.beans.refresh.YamlFullRefreshResponseDTO;

import java.util.List;
import org.hibernate.validator.constraints.NotEmpty;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Path;
import retrofit2.http.Query;

@OwnedBy(HarnessTeam.CDC)
public interface TemplateResourceClient {
  String TEMPLATE_ENDPOINT = "templates/";
  String TEMPLATE_REFRESH_ENDPOINT = "refresh-template/";

  // list templates
  @GET(TEMPLATE_ENDPOINT + "{templateIdentifier}")
  Call<ResponseDTO<TemplateResponseDTO>> get(@Path("templateIdentifier") String templateIdentifier,
      @Query(NGCommonEntityConstants.ACCOUNT_KEY) @NotEmpty String accountIdentifier,
      @Query(NGCommonEntityConstants.ORG_KEY) String orgIdentifier,
      @Query(NGCommonEntityConstants.PROJECT_KEY) String projectIdentifier,
      @Query(NGCommonEntityConstants.VERSION_LABEL_KEY) String versionLabel,
      @Query(NGCommonEntityConstants.DELETED_KEY) boolean deleted);

  @POST(TEMPLATE_ENDPOINT + "list")
  Call<ResponseDTO<PageResponse<TemplateSummaryResponseDTO>>> listTemplates(
      @Query(value = NGCommonEntityConstants.ACCOUNT_KEY) @NotEmpty String accountIdentifier,
      @Query(value = NGCommonEntityConstants.ORG_KEY) String orgIdentifier,
      @Query(value = NGCommonEntityConstants.PROJECT_KEY) String projectIdentifier,
      @Query(value = "templateListType") TemplateListType templateListType,
      @Query(value = NGResourceFilterConstants.PAGE_KEY) int page, @Query(NGCommonEntityConstants.SIZE) int size,
      @Body TemplateFilterPropertiesDTO filterProperties);

  @POST(TEMPLATE_ENDPOINT + "applyTemplates")
  Call<ResponseDTO<TemplateMergeResponseDTO>> applyTemplatesOnGivenYaml(
      @Query(value = NGCommonEntityConstants.ACCOUNT_KEY) @NotEmpty String accountIdentifier,
      @Query(value = NGCommonEntityConstants.ORG_KEY) String orgIdentifier,
      @Query(value = NGCommonEntityConstants.PROJECT_KEY) String projectIdentifier,
      @Query(value = GitSyncApiConstants.BRANCH_KEY) String branch,
      @Query(value = GitSyncApiConstants.REPO_IDENTIFIER_KEY) String repoIdentifier,
      @Query(value = GitSyncApiConstants.DEFAULT_FROM_OTHER_REPO) Boolean defaultFromOtherRepo,
      @Body TemplateApplyRequestDTO templateApplyRequestDTO);

  @POST(TEMPLATE_ENDPOINT + "v2/applyTemplates")
  Call<ResponseDTO<TemplateMergeResponseDTO>> applyTemplatesOnGivenYamlV2(
      @Query(value = NGCommonEntityConstants.ACCOUNT_KEY) @NotEmpty String accountIdentifier,
      @Query(value = NGCommonEntityConstants.ORG_KEY) String orgIdentifier,
      @Query(value = NGCommonEntityConstants.PROJECT_KEY) String projectIdentifier,
      @Query(value = GitSyncApiConstants.BRANCH_KEY) String branch,
      @Query(value = GitSyncApiConstants.REPO_IDENTIFIER_KEY) String repoIdentifier,
      @Query(value = GitSyncApiConstants.DEFAULT_FROM_OTHER_REPO) Boolean defaultFromOtherRepo,
      @Query(value = GitSyncApiConstants.PARENT_ENTITY_CONNECTOR_REF) String parentEntityConnectorRef,
      @Query(value = GitSyncApiConstants.PARENT_ENTITY_REPO_NAME) String parentEntityRepoName,
      @Query(value = GitSyncApiConstants.PARENT_ENTITY_ACCOUNT_IDENTIFIER) String parentEntityAccountIdentifier,
      @Query(value = GitSyncApiConstants.PARENT_ENTITY_ORG_IDENTIFIER) String parentEntityOrgIdentifier,
      @Query(value = GitSyncApiConstants.PARENT_ENTITY_PROJECT_IDENTIFIER) String parentEntityProjectIdentifier,
      @Body TemplateApplyRequestDTO templateApplyRequestDTO);

  @POST(TEMPLATE_ENDPOINT + "templateReferences")
  Call<ResponseDTO<List<EntityDetailProtoDTO>>> getTemplateReferenceForGivenYaml(
      @Query(value = NGCommonEntityConstants.ACCOUNT_KEY) @NotEmpty String accountIdentifier,
      @Query(value = NGCommonEntityConstants.ORG_KEY) String orgIdentifier,
      @Query(value = NGCommonEntityConstants.PROJECT_KEY) String projectIdentifier,
      @Query(value = GitSyncApiConstants.BRANCH_KEY) String branch,
      @Query(value = GitSyncApiConstants.REPO_IDENTIFIER_KEY) String repoIdentifier,
      @Query(value = GitSyncApiConstants.DEFAULT_FROM_OTHER_REPO) Boolean defaultFromOtherRepo,
      @Body TemplateReferenceRequestDTO templateReferenceRequestDTO);

  // Refresh Template APIs
  @POST(TEMPLATE_REFRESH_ENDPOINT + "refreshed-yaml")
  Call<ResponseDTO<RefreshResponseDTO>> getRefreshedYaml(
      @Query(value = NGCommonEntityConstants.ACCOUNT_KEY) @NotEmpty String accountIdentifier,
      @Query(value = NGCommonEntityConstants.ORG_KEY) String orgIdentifier,
      @Query(value = NGCommonEntityConstants.PROJECT_KEY) String projectIdentifier,
      @Query(value = GitSyncApiConstants.BRANCH_KEY) String branch,
      @Query(value = GitSyncApiConstants.REPO_IDENTIFIER_KEY) String repoIdentifier,
      @Query(value = GitSyncApiConstants.DEFAULT_FROM_OTHER_REPO) Boolean defaultFromOtherRepo,
      @Body RefreshRequestDTO refreshRequest);

  @POST(TEMPLATE_REFRESH_ENDPOINT + "validate-template-inputs/internal")
  Call<ResponseDTO<ValidateTemplateInputsResponseDTO>> validateTemplateInputsForGivenYaml(
      @Query(value = NGCommonEntityConstants.ACCOUNT_KEY) @NotEmpty String accountIdentifier,
      @Query(value = NGCommonEntityConstants.ORG_KEY) String orgIdentifier,
      @Query(value = NGCommonEntityConstants.PROJECT_KEY) String projectIdentifier,
      @Query(value = GitSyncApiConstants.BRANCH_KEY) String branch,
      @Query(value = GitSyncApiConstants.REPO_IDENTIFIER_KEY) String repoIdentifier,
      @Query(value = GitSyncApiConstants.DEFAULT_FROM_OTHER_REPO) Boolean defaultFromOtherRepo,
      @Body RefreshRequestDTO refreshRequest);

  @POST(TEMPLATE_REFRESH_ENDPOINT + "refresh-all/internal")
  Call<ResponseDTO<YamlFullRefreshResponseDTO>> refreshAllTemplatesForYaml(
      @Query(value = NGCommonEntityConstants.ACCOUNT_KEY) @NotEmpty String accountIdentifier,
      @Query(value = NGCommonEntityConstants.ORG_KEY) String orgIdentifier,
      @Query(value = NGCommonEntityConstants.PROJECT_KEY) String projectIdentifier,
      @Query(value = GitSyncApiConstants.BRANCH_KEY) String branch,
      @Query(value = GitSyncApiConstants.REPO_IDENTIFIER_KEY) String repoIdentifier,
      @Query(value = GitSyncApiConstants.DEFAULT_FROM_OTHER_REPO) Boolean defaultFromOtherRepo,
      @Body RefreshRequestDTO refreshRequest);
}
