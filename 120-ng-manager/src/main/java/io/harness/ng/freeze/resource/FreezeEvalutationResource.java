/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.ng.freeze.resource;

import io.harness.NGCommonEntityConstants;
import io.harness.accesscontrol.AccountIdentifier;
import io.harness.accesscontrol.OrgIdentifier;
import io.harness.accesscontrol.ProjectIdentifier;
import io.harness.freeze.service.FreezeEvaluateService;
import io.harness.ng.core.dto.ErrorDTO;
import io.harness.ng.core.dto.FailureDTO;
import io.harness.ng.core.dto.ResponseDTO;
import io.harness.security.annotations.NextGenManagerAuth;

import com.google.inject.Inject;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import javax.validation.constraints.NotNull;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@NextGenManagerAuth
@Api("/freeze")
@Path("/freeze/evaluate")
@Produces({"application/json", "application/yaml"})
@Consumes({"application/json", "application/yaml"})
@AllArgsConstructor(access = AccessLevel.PACKAGE, onConstructor = @__({ @Inject }))
@ApiResponses(value =
    {
      @ApiResponse(code = 400, response = FailureDTO.class, message = "Bad Request")
      , @ApiResponse(code = 500, response = ErrorDTO.class, message = "Internal server error")
    })
@Tag(name = "Freeze Evaluation", description = "This contains APIs related to evaluation Freeze status")
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = NGCommonEntityConstants.BAD_REQUEST_CODE,
    description = NGCommonEntityConstants.BAD_REQUEST_PARAM_MESSAGE,
    content =
    {
      @Content(mediaType = NGCommonEntityConstants.APPLICATION_JSON_MEDIA_TYPE,
          schema = @Schema(implementation = FailureDTO.class))
      ,
          @Content(mediaType = NGCommonEntityConstants.APPLICATION_YAML_MEDIA_TYPE,
              schema = @Schema(implementation = FailureDTO.class))
    })
@io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = NGCommonEntityConstants.INTERNAL_SERVER_ERROR_CODE,
    description = NGCommonEntityConstants.INTERNAL_SERVER_ERROR_MESSAGE,
    content =
    {
      @Content(mediaType = NGCommonEntityConstants.APPLICATION_JSON_MEDIA_TYPE,
          schema = @Schema(implementation = ErrorDTO.class))
      ,
          @Content(mediaType = NGCommonEntityConstants.APPLICATION_YAML_MEDIA_TYPE,
              schema = @Schema(implementation = ErrorDTO.class))
    })
@Slf4j
public class FreezeEvalutationResource {
  private final FreezeEvaluateService freezeEvaluateService;

  @GET
  @Path("/isGlobalFreezeActive")
  @ApiOperation(value = "Get if global freeze is Active", nickname = "isGlobalFreezeActive")
  @Operation(operationId = "isGlobalFreezeActive", summary = "Get if global freeze is Active",
      responses =
      {
        @io.swagger.v3.oas.annotations.responses.
        ApiResponse(responseCode = "default", description = "Returns true if global freeze is active")
      })
  @Hidden
  public ResponseDTO<Boolean>
  isGlobalFreezeActive(@Parameter(description = NGCommonEntityConstants.ACCOUNT_PARAM_MESSAGE) @NotNull @QueryParam(
                           NGCommonEntityConstants.ACCOUNT_KEY) @AccountIdentifier String accountId,
      @Parameter(description = NGCommonEntityConstants.ORG_PARAM_MESSAGE) @QueryParam(
          NGCommonEntityConstants.ORG_KEY) @OrgIdentifier String orgId,
      @Parameter(description = NGCommonEntityConstants.PROJECT_PARAM_MESSAGE) @QueryParam(
          NGCommonEntityConstants.PROJECT_KEY) @ProjectIdentifier String projectId) {
    return ResponseDTO.newResponse(freezeEvaluateService.globalFreezeActive(accountId, orgId, projectId));
  }

  @GET
  @Path("/shouldDisableDeployment")
  @ApiOperation(value = "If to disable run button for deployment", nickname = "shouldDisableDeployment")
  @Operation(operationId = "isGlobalFreezeActive", summary = "If to disable run button for deployment",
      responses =
      {
        @io.swagger.v3.oas.annotations.responses.
        ApiResponse(responseCode = "default", description = "Returns true if run button is t be disabled")
      })
  @Hidden
  public ResponseDTO<Boolean>
  shouldDisableDeployment(@Parameter(description = NGCommonEntityConstants.ACCOUNT_PARAM_MESSAGE) @NotNull @QueryParam(
                              NGCommonEntityConstants.ACCOUNT_KEY) @AccountIdentifier String accountId,
      @Parameter(description = NGCommonEntityConstants.ORG_PARAM_MESSAGE) @QueryParam(
          NGCommonEntityConstants.ORG_KEY) @OrgIdentifier String orgId,
      @Parameter(description = NGCommonEntityConstants.PROJECT_PARAM_MESSAGE) @QueryParam(
          NGCommonEntityConstants.PROJECT_KEY) @ProjectIdentifier String projectId) {
    return ResponseDTO.newResponse(freezeEvaluateService.shouldDisableDeployment(accountId, orgId, projectId));
  }
}
