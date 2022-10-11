/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.ng.core.infrastructure.services.impl;

import static io.harness.annotations.dev.HarnessTeam.PIPELINE;
import static io.harness.data.structure.EmptyPredicate.isEmpty;
import static io.harness.data.structure.EmptyPredicate.isNotEmpty;
import static io.harness.exception.WingsException.USER;
import static io.harness.outbox.TransactionOutboxModule.OUTBOX_TRANSACTION_TEMPLATE;
import static io.harness.pms.yaml.YAMLFieldNameConstants.IDENTIFIER;
import static io.harness.springdata.PersistenceUtils.DEFAULT_RETRY_POLICY;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.commons.lang3.StringUtils.isBlank;

import io.harness.annotations.dev.OwnedBy;
import io.harness.cdng.customdeployment.helper.CustomDeploymentEntitySetupHelper;
import io.harness.cdng.visitor.YamlTypes;
import io.harness.data.structure.EmptyPredicate;
import io.harness.exception.DuplicateFieldException;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.UnexpectedException;
import io.harness.ng.DuplicateKeyExceptionParser;
import io.harness.ng.core.events.EnvironmentUpdatedEvent;
import io.harness.ng.core.infrastructure.InfrastructureType;
import io.harness.ng.core.infrastructure.dto.InfrastructureYamlMetadata;
import io.harness.ng.core.infrastructure.entity.InfrastructureEntity;
import io.harness.ng.core.infrastructure.entity.InfrastructureEntity.InfrastructureEntityKeys;
import io.harness.ng.core.infrastructure.services.InfrastructureEntityService;
import io.harness.outbox.api.OutboxService;
import io.harness.pms.merger.helpers.RuntimeInputFormHelper;
import io.harness.pms.yaml.YamlField;
import io.harness.pms.yaml.YamlUtils;
import io.harness.repositories.UpsertOptions;
import io.harness.repositories.infrastructure.spring.InfrastructureRepository;
import io.harness.setupusage.InfrastructureEntitySetupUsageHelper;
import io.harness.utils.YamlPipelineUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.RetryPolicy;
import org.json.JSONObject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.transaction.support.TransactionTemplate;

@OwnedBy(PIPELINE)
@Singleton
@AllArgsConstructor(onConstructor = @__({ @Inject }))
@Slf4j
public class InfrastructureEntityServiceImpl implements InfrastructureEntityService {
  private final InfrastructureRepository infrastructureRepository;
  @Inject @Named(OUTBOX_TRANSACTION_TEMPLATE) private final TransactionTemplate transactionTemplate;
  private final RetryPolicy<Object> transactionRetryPolicy = DEFAULT_RETRY_POLICY;
  private final OutboxService outboxService;
  @Inject CustomDeploymentEntitySetupHelper customDeploymentEntitySetupHelper;
  @Inject private InfrastructureEntitySetupUsageHelper infrastructureEntitySetupUsageHelper;

  private static final String DUP_KEY_EXP_FORMAT_STRING_FOR_PROJECT =
      "Infrastructure [%s] under Environment [%s] Project[%s], Organization [%s] in Account [%s] already exists";
  private static final String DUP_KEY_EXP_FORMAT_STRING_FOR_ORG =
      "Infrastructure [%s] under Organization [%s] in Account [%s] already exists";
  private static final String DUP_KEY_EXP_FORMAT_STRING_FOR_ACCOUNT =
      "Infrastructure [%s] in Account [%s] already exists";

  void validatePresenceOfRequiredFields(Object... fields) {
    Lists.newArrayList(fields).forEach(field -> Objects.requireNonNull(field, "One of the required fields is null."));
  }

  @Override
  public InfrastructureEntity create(@NotNull @Valid InfrastructureEntity infraEntity) {
    try {
      setObsoleteAsFalse(infraEntity);
      validatePresenceOfRequiredFields(
          infraEntity.getAccountId(), infraEntity.getIdentifier(), infraEntity.getEnvIdentifier());
      setNameIfNotPresent(infraEntity);
      modifyInfraRequest(infraEntity);
      if (infraEntity.getType() == InfrastructureType.CUSTOM_DEPLOYMENT) {
        customDeploymentEntitySetupHelper.addReferencesInEntitySetupUsage(infraEntity);
      }
      InfrastructureEntity createdInfra =
          Failsafe.with(transactionRetryPolicy).get(() -> transactionTemplate.execute(status -> {
            InfrastructureEntity infrastructureEntity = infrastructureRepository.save(infraEntity);
            outboxService.save(EnvironmentUpdatedEvent.builder()
                                   .accountIdentifier(infraEntity.getAccountId())
                                   .orgIdentifier(infraEntity.getOrgIdentifier())
                                   .status(EnvironmentUpdatedEvent.Status.CREATED)
                                   .resourceType(EnvironmentUpdatedEvent.ResourceType.INFRASTRUCTURE)
                                   .projectIdentifier(infraEntity.getProjectIdentifier())
                                   .newInfrastructureEntity(infraEntity)
                                   .build());
            return infrastructureEntity;
          }));
      infrastructureEntitySetupUsageHelper.updateSetupUsages(createdInfra);

      return createdInfra;

    } catch (DuplicateKeyException ex) {
      throw new DuplicateFieldException(
          getDuplicateInfrastructureExistsErrorMessage(infraEntity.getAccountId(), infraEntity.getOrgIdentifier(),
              infraEntity.getProjectIdentifier(), infraEntity.getEnvIdentifier(), infraEntity.getIdentifier()),
          USER, ex);
    }
  }

  @Override
  public Optional<InfrastructureEntity> get(
      String accountId, String orgIdentifier, String projectIdentifier, String envIdentifier, String infraIdentifier) {
    return infrastructureRepository.findByAccountIdAndOrgIdentifierAndProjectIdentifierAndEnvIdentifierAndIdentifier(
        accountId, orgIdentifier, projectIdentifier, envIdentifier, infraIdentifier);
  }

  @Override
  public InfrastructureEntity update(@Valid InfrastructureEntity requestInfra) {
    validatePresenceOfRequiredFields(requestInfra.getAccountId(), requestInfra.getIdentifier());
    setObsoleteAsFalse(requestInfra);
    setNameIfNotPresent(requestInfra);
    modifyInfraRequest(requestInfra);
    if (requestInfra.getType() == InfrastructureType.CUSTOM_DEPLOYMENT) {
      customDeploymentEntitySetupHelper.addReferencesInEntitySetupUsage(requestInfra);
    }
    Criteria criteria = getInfrastructureEqualityCriteria(requestInfra);
    Optional<InfrastructureEntity> infraEntityOptional =
        get(requestInfra.getAccountId(), requestInfra.getOrgIdentifier(), requestInfra.getProjectIdentifier(),
            requestInfra.getEnvIdentifier(), requestInfra.getIdentifier());
    if (infraEntityOptional.isPresent()) {
      InfrastructureEntity updatedInfra =
          Failsafe.with(transactionRetryPolicy).get(() -> transactionTemplate.execute(status -> {
            InfrastructureEntity updatedResult = infrastructureRepository.update(criteria, requestInfra);
            if (updatedResult == null) {
              throw new InvalidRequestException(String.format(
                  "Infrastructure [%s] under Environment [%s], Project [%s], Organization [%s] couldn't be updated or doesn't exist.",
                  requestInfra.getIdentifier(), requestInfra.getEnvIdentifier(), requestInfra.getProjectIdentifier(),
                  requestInfra.getOrgIdentifier()));
            }
            outboxService.save(EnvironmentUpdatedEvent.builder()
                                   .accountIdentifier(requestInfra.getAccountId())
                                   .orgIdentifier(requestInfra.getOrgIdentifier())
                                   .status(EnvironmentUpdatedEvent.Status.UPDATED)
                                   .resourceType(EnvironmentUpdatedEvent.ResourceType.INFRASTRUCTURE)
                                   .projectIdentifier(requestInfra.getProjectIdentifier())
                                   .newInfrastructureEntity(requestInfra)
                                   .oldInfrastructureEntity(infraEntityOptional.get())
                                   .build());
            return updatedResult;
          }));
      infrastructureEntitySetupUsageHelper.updateSetupUsages(updatedInfra);
      return updatedInfra;
    } else {
      throw new InvalidRequestException(
          String.format("Infrastructure [%s] under Environment [%s], Project [%s], Organization [%s] doesn't exist.",
              requestInfra.getIdentifier(), requestInfra.getEnvIdentifier(), requestInfra.getProjectIdentifier(),
              requestInfra.getOrgIdentifier()));
    }
  }

  @Override
  public InfrastructureEntity upsert(@Valid InfrastructureEntity requestInfra, UpsertOptions upsertOptions) {
    validatePresenceOfRequiredFields(requestInfra.getAccountId(), requestInfra.getIdentifier());
    setNameIfNotPresent(requestInfra);
    modifyInfraRequest(requestInfra);
    Criteria criteria = getInfrastructureEqualityCriteria(requestInfra);
    InfrastructureEntity upsertedInfra =
        Failsafe.with(transactionRetryPolicy).get(() -> transactionTemplate.execute(status -> {
          InfrastructureEntity result = infrastructureRepository.upsert(criteria, requestInfra);
          if (result == null) {
            throw new InvalidRequestException(String.format(
                "Infrastructure [%s] under Environment [%s] Project[%s], Organization [%s] couldn't be upserted.",
                requestInfra.getIdentifier(), requestInfra.getEnvIdentifier(), requestInfra.getProjectIdentifier(),
                requestInfra.getOrgIdentifier()));
          }
          outboxService.save(EnvironmentUpdatedEvent.builder()
                                 .accountIdentifier(requestInfra.getAccountId())
                                 .orgIdentifier(requestInfra.getOrgIdentifier())
                                 .status(EnvironmentUpdatedEvent.Status.UPSERTED)
                                 .resourceType(EnvironmentUpdatedEvent.ResourceType.INFRASTRUCTURE)
                                 .projectIdentifier(requestInfra.getProjectIdentifier())
                                 .newInfrastructureEntity(requestInfra)
                                 .build());
          return result;
        }));
    infrastructureEntitySetupUsageHelper.updateSetupUsages(upsertedInfra);
    return upsertedInfra;
  }

  @Override
  public Page<InfrastructureEntity> list(@NotNull Criteria criteria, @NotNull Pageable pageable) {
    return infrastructureRepository.findAll(criteria, pageable);
  }

  @Override
  public boolean delete(
      String accountId, String orgIdentifier, String projectIdentifier, String envIdentifier, String infraIdentifier) {
    InfrastructureEntity infraEntity = InfrastructureEntity.builder()
                                           .accountId(accountId)
                                           .orgIdentifier(orgIdentifier)
                                           .projectIdentifier(projectIdentifier)
                                           .envIdentifier(envIdentifier)
                                           .identifier(infraIdentifier)
                                           .build();
    // todo: check for infra usage in pipelines
    // todo: outbox events
    Criteria criteria = getInfrastructureEqualityCriteria(infraEntity);
    Optional<InfrastructureEntity> infraEntityOptional =
        get(accountId, orgIdentifier, projectIdentifier, envIdentifier, infraIdentifier);
    if (infraEntityOptional.isPresent()) {
      if (infraEntityOptional.get().getType() == InfrastructureType.CUSTOM_DEPLOYMENT) {
        customDeploymentEntitySetupHelper.deleteReferencesInEntitySetupUsage(infraEntityOptional.get());
      }
      return Failsafe.with(transactionRetryPolicy).get(() -> transactionTemplate.execute(status -> {
        DeleteResult deleteResult = infrastructureRepository.delete(criteria);
        if (!deleteResult.wasAcknowledged() || deleteResult.getDeletedCount() != 1) {
          throw new InvalidRequestException(String.format(
              "Infrastructure [%s] under Environment [%s], Project[%s], Organization [%s] couldn't be deleted.",
              infraIdentifier, envIdentifier, projectIdentifier, orgIdentifier));
        }

        infraEntityOptional.ifPresent(
            infrastructureEntity -> infrastructureEntitySetupUsageHelper.deleteSetupUsages(infrastructureEntity));

        outboxService.save(EnvironmentUpdatedEvent.builder()
                               .accountIdentifier(accountId)
                               .orgIdentifier(orgIdentifier)
                               .projectIdentifier(projectIdentifier)
                               .oldInfrastructureEntity(infraEntityOptional.get())
                               .status(EnvironmentUpdatedEvent.Status.DELETED)
                               .resourceType(EnvironmentUpdatedEvent.ResourceType.INFRASTRUCTURE)
                               .build());
        return true;
      }));
    } else {
      throw new InvalidRequestException(
          String.format("Infrastructure [%s] under Environment [%s], Project[%s], Organization [%s] doesn't exist.",
              infraIdentifier, envIdentifier, projectIdentifier, orgIdentifier));
    }
  }

  @Override
  public boolean forceDeleteAllInEnv(
      String accountId, String orgIdentifier, String projectIdentifier, String envIdentifier) {
    checkArgument(isNotEmpty(accountId), "account id must be present");
    checkArgument(isNotEmpty(orgIdentifier), "org id must be present");
    checkArgument(isNotEmpty(projectIdentifier), "project id must be present");
    checkArgument(isNotEmpty(envIdentifier), "env id must be present");

    Criteria criteria =
        getInfrastructureEqualityCriteriaForEnv(accountId, orgIdentifier, projectIdentifier, envIdentifier);
    DeleteResult deleteResult = infrastructureRepository.delete(criteria);
    return deleteResult.wasAcknowledged() && deleteResult.getDeletedCount() > 0;
  }

  @Override
  public boolean forceDeleteAllInProject(String accountId, String orgIdentifier, String projectIdentifier) {
    checkArgument(isNotEmpty(accountId), "account id must be present");
    checkArgument(isNotEmpty(orgIdentifier), "org id must be present");
    checkArgument(isNotEmpty(projectIdentifier), "project id must be present");

    Criteria criteria = getInfrastructureEqualityCriteriaForProject(accountId, orgIdentifier, projectIdentifier);
    DeleteResult deleteResult = infrastructureRepository.delete(criteria);
    return deleteResult.wasAcknowledged();
  }

  private void setObsoleteAsFalse(InfrastructureEntity requestInfra) {
    requestInfra.setObsolete(false);
  }
  private void setNameIfNotPresent(InfrastructureEntity requestInfra) {
    if (isEmpty(requestInfra.getName())) {
      requestInfra.setName(requestInfra.getIdentifier());
    }
  }
  private Criteria getInfrastructureEqualityCriteria(@Valid InfrastructureEntity requestInfra) {
    return Criteria.where(InfrastructureEntityKeys.accountId)
        .is(requestInfra.getAccountId())
        .and(InfrastructureEntityKeys.orgIdentifier)
        .is(requestInfra.getOrgIdentifier())
        .and(InfrastructureEntityKeys.projectIdentifier)
        .is(requestInfra.getProjectIdentifier())
        .and(InfrastructureEntityKeys.envIdentifier)
        .is(requestInfra.getEnvIdentifier())
        .and(InfrastructureEntityKeys.identifier)
        .is(requestInfra.getIdentifier());
  }

  @Override
  public Page<InfrastructureEntity> bulkCreate(String accountId, @NotNull List<InfrastructureEntity> infraEntities) {
    try {
      validateInfraList(infraEntities);
      populateDefaultNameIfNotPresent(infraEntities);
      modifyInfraRequestBatch(infraEntities);
      return Failsafe.with(transactionRetryPolicy).get(() -> transactionTemplate.execute(status -> {
        List<InfrastructureEntity> outputInfrastructureEntitiesList =
            (List<InfrastructureEntity>) infrastructureRepository.saveAll(infraEntities);
        for (InfrastructureEntity infraEntity : infraEntities) {
          outboxService.save(EnvironmentUpdatedEvent.builder()
                                 .accountIdentifier(infraEntity.getAccountId())
                                 .orgIdentifier(infraEntity.getOrgIdentifier())
                                 .status(EnvironmentUpdatedEvent.Status.CREATED)
                                 .resourceType(EnvironmentUpdatedEvent.ResourceType.INFRASTRUCTURE)
                                 .projectIdentifier(infraEntity.getProjectIdentifier())
                                 .newInfrastructureEntity(infraEntity)
                                 .build());
        }

        return new PageImpl<>(outputInfrastructureEntitiesList);
      }));
    } catch (DuplicateKeyException ex) {
      throw new DuplicateFieldException(
          getDuplicateInfrastructureExistsErrorMessage(accountId, ex.getMessage()), USER, ex);
    } catch (Exception ex) {
      String infraNames = infraEntities.stream().map(InfrastructureEntity::getName).collect(Collectors.joining(","));
      log.info("Encountered exception while saving the infrastructure entity records of [{}], with exception",
          infraNames, ex);
      throw new UnexpectedException("Encountered exception while saving the infrastructure entity records.");
    }
  }

  @Override
  public InfrastructureEntity find(String accountIdentifier, String orgIdentifier, String projectIdentifier,
      String envIdentifier, String infraIdentifier) {
    return infrastructureRepository.find(
        accountIdentifier, orgIdentifier, projectIdentifier, envIdentifier, infraIdentifier);
  }

  @Override
  public List<InfrastructureEntity> getAllInfrastructureFromIdentifierList(String accountIdentifier,
      String orgIdentifier, String projectIdentifier, String envIdentifier, List<String> infraIdentifierList) {
    return infrastructureRepository.findAllFromInfraIdentifierList(
        accountIdentifier, orgIdentifier, projectIdentifier, envIdentifier, infraIdentifierList);
  }

  @Override
  public List<InfrastructureEntity> getAllInfrastructureFromEnvIdentifier(
      String accountIdentifier, String orgIdentifier, String projectIdentifier, String envIdentifier) {
    return infrastructureRepository.findAllFromEnvIdentifier(
        accountIdentifier, orgIdentifier, projectIdentifier, envIdentifier);
  }
  @Override
  public String createInfrastructureInputsFromYaml(String accountId, String orgIdentifier, String projectIdentifier,
      String environmentIdentifier, List<String> infraIdentifiers, boolean deployToAll) {
    Map<String, Object> yamlInputs = createInfrastructureInputsYamlInternal(accountId, orgIdentifier, projectIdentifier,
        environmentIdentifier, deployToAll, infraIdentifiers, new HashMap<>());

    if (isEmpty(yamlInputs)) {
      return null;
    }
    return YamlPipelineUtils.writeYamlString(yamlInputs);
  }

  @Override
  public String createInfrastructureInputsFromYamlV2(String accountId, String orgIdentifier, String projectIdentifier,
      String environmentIdentifier, List<String> infraIdentifiers, boolean deployToAll) {
    Map<String, Object> infraDefsInputMap = new HashMap<>();
    createInfrastructureInputsYamlInternal(accountId, orgIdentifier, projectIdentifier, environmentIdentifier,
        deployToAll, infraIdentifiers, infraDefsInputMap);

    if (isEmpty(infraDefsInputMap)) {
      return null;
    }
    return YamlPipelineUtils.writeYamlString(infraDefsInputMap);
  }

  @Override
  public UpdateResult batchUpdateInfrastructure(String accountIdentifier, String orgIdentifier,
      String projectIdentifier, String envIdentifier, List<String> infraIdentifier, Update update) {
    return infrastructureRepository.batchUpdateInfrastructure(
        accountIdentifier, orgIdentifier, projectIdentifier, envIdentifier, infraIdentifier, update);
  }

  private Map<String, Object> createInfrastructureInputsYamlInternal(String accountId, String orgIdentifier,
      String projectIdentifier, String envIdentifier, boolean deployToAll, List<String> infraIdentifiers,
      Map<String, Object> infraDefsInputsMap) {
    Map<String, Object> yamlInputs = new HashMap<>();
    // create one mapper for all infra defs
    ObjectMapper mapper = new ObjectMapper();
    List<Object> infraDefinitionInputList = new ArrayList<>();
    List<Object> infraDefinitionInputListV2 = new ArrayList<>();
    if (deployToAll) {
      List<InfrastructureEntity> infrastructureEntities =
          getAllInfrastructureFromEnvIdentifier(accountId, orgIdentifier, projectIdentifier, envIdentifier);

      for (InfrastructureEntity infraEntity : infrastructureEntities) {
        createInfraDefinitionInputs(infraEntity, infraDefinitionInputList, mapper, infraDefinitionInputListV2);
      }
    } else {
      List<InfrastructureEntity> infrastructureEntities = getAllInfrastructureFromIdentifierList(
          accountId, orgIdentifier, projectIdentifier, envIdentifier, infraIdentifiers);
      for (InfrastructureEntity infraEntity : infrastructureEntities) {
        createInfraDefinitionInputs(infraEntity, infraDefinitionInputList, mapper, infraDefinitionInputListV2);
      }
    }
    if (isNotEmpty(infraDefinitionInputList)) {
      yamlInputs.put(YamlTypes.INFRASTRUCTURE_DEFS, infraDefinitionInputList);
    }
    if (isNotEmpty(infraDefinitionInputListV2)) {
      infraDefsInputsMap.put(YamlTypes.INFRASTRUCTURE_DEFS, infraDefinitionInputListV2);
    }
    return yamlInputs;
  }

  private void createInfraDefinitionInputs(InfrastructureEntity infraEntity, List<Object> infraDefinitionInputList,
      ObjectMapper mapper, List<Object> infraDefinitionInputListV2) {
    String yaml = infraEntity.getYaml();
    if (isEmpty(yaml)) {
      throw new InvalidRequestException("Infrastructure Yaml cannot be empty");
    }
    try {
      ObjectNode infraNode = mapper.createObjectNode();
      infraNode.put(IDENTIFIER, infraEntity.getIdentifier());
      String infraDefinitionInputs = RuntimeInputFormHelper.createRuntimeInputForm(yaml, true);
      if (isEmpty(infraDefinitionInputs)) {
        infraDefinitionInputListV2.add(infraNode);
        return;
      }

      YamlField infrastructureDefinitionYamlField =
          YamlUtils.readTree(infraDefinitionInputs).getNode().getField(YamlTypes.INFRASTRUCTURE_DEF);
      ObjectNode infraDefinitionNode = (ObjectNode) infrastructureDefinitionYamlField.getNode().getCurrJsonNode();
      infraNode.set(YamlTypes.INPUTS, infraDefinitionNode);

      infraDefinitionInputList.add(infraNode);
      infraDefinitionInputListV2.add(infraNode);
    } catch (IOException e) {
      throw new InvalidRequestException("Error occurred while creating Service Override inputs ", e);
    }
  }

  String getDuplicateInfrastructureExistsErrorMessage(String accountIdentifier, String orgIdentifier,
      String projectIdentifier, String envIdentifier, String infraIdentifier) {
    if (EmptyPredicate.isEmpty(orgIdentifier)) {
      return String.format(DUP_KEY_EXP_FORMAT_STRING_FOR_ACCOUNT, infraIdentifier, accountIdentifier);
    } else if (EmptyPredicate.isEmpty(projectIdentifier)) {
      return String.format(DUP_KEY_EXP_FORMAT_STRING_FOR_ORG, infraIdentifier, orgIdentifier, accountIdentifier);
    }
    return String.format(DUP_KEY_EXP_FORMAT_STRING_FOR_PROJECT, infraIdentifier, envIdentifier, projectIdentifier,
        orgIdentifier, accountIdentifier);
  }

  @VisibleForTesting
  String getDuplicateInfrastructureExistsErrorMessage(String accountId, String exceptionString) {
    String errorMessageToBeReturned;
    try {
      JSONObject jsonObjectOfDuplicateKey = DuplicateKeyExceptionParser.getDuplicateKey(exceptionString);
      if (jsonObjectOfDuplicateKey != null) {
        String orgIdentifier = jsonObjectOfDuplicateKey.getString("orgIdentifier");
        String projectIdentifier = jsonObjectOfDuplicateKey.getString("projectIdentifier");
        String envIdentifier = jsonObjectOfDuplicateKey.getString("envIdentifier");
        String identifier = jsonObjectOfDuplicateKey.getString("identifier");
        errorMessageToBeReturned = getDuplicateInfrastructureExistsErrorMessage(
            accountId, orgIdentifier, projectIdentifier, envIdentifier, identifier);
      } else {
        errorMessageToBeReturned = "A Duplicate Infrastructure already exists";
      }
    } catch (Exception ex) {
      errorMessageToBeReturned = "A Duplicate Infrastructure already exists";
    }
    return errorMessageToBeReturned;
  }

  private void validateInfraList(List<InfrastructureEntity> infraEntities) {
    if (isEmpty(infraEntities)) {
      return;
    }
    infraEntities.forEach(
        infraEntity -> validatePresenceOfRequiredFields(infraEntity.getAccountId(), infraEntity.getIdentifier()));
  }

  private void populateDefaultNameIfNotPresent(List<InfrastructureEntity> infraEntities) {
    if (isEmpty(infraEntities)) {
      return;
    }
    infraEntities.forEach(this::setNameIfNotPresent);
  }

  private void modifyInfraRequest(InfrastructureEntity requestInfra) {
    requestInfra.setName(requestInfra.getName().trim());
  }

  private void modifyInfraRequestBatch(List<InfrastructureEntity> infrastructureEntityList) {
    if (isEmpty(infrastructureEntityList)) {
      return;
    }
    infrastructureEntityList.forEach(this::modifyInfraRequest);
  }

  private Criteria getInfrastructureEqualityCriteriaForEnv(
      String accountId, String orgIdentifier, String projectIdentifier, String envIdentifier) {
    return Criteria.where(InfrastructureEntityKeys.accountId)
        .is(accountId)
        .and(InfrastructureEntityKeys.orgIdentifier)
        .is(orgIdentifier)
        .and(InfrastructureEntityKeys.projectIdentifier)
        .is(projectIdentifier)
        .and(InfrastructureEntityKeys.envIdentifier)
        .is(envIdentifier);
  }

  private Criteria getInfrastructureEqualityCriteriaForProject(
      String accountId, String orgIdentifier, String projectIdentifier) {
    return Criteria.where(InfrastructureEntityKeys.accountId)
        .is(accountId)
        .and(InfrastructureEntityKeys.orgIdentifier)
        .is(orgIdentifier)
        .and(InfrastructureEntityKeys.projectIdentifier)
        .is(projectIdentifier);
  }

  public List<InfrastructureYamlMetadata> createInfrastructureYamlMetadata(String accountId, String orgIdentifier,
      String projectIdentifier, String environmentIdentifier, List<String> infraIds) {
    List<InfrastructureEntity> infrastructureEntities = getAllInfrastructureFromIdentifierList(
        accountId, orgIdentifier, projectIdentifier, environmentIdentifier, infraIds);
    List<InfrastructureYamlMetadata> infrastructureYamlMetadataList = new ArrayList<>();
    infrastructureEntities.forEach(infrastructureEntity
        -> infrastructureYamlMetadataList.add(createInfrastructureYamlMetadataInternal(infrastructureEntity)));
    return infrastructureYamlMetadataList;
  }

  private InfrastructureYamlMetadata createInfrastructureYamlMetadataInternal(
      InfrastructureEntity infrastructureEntity) {
    if (isBlank(infrastructureEntity.getYaml())) {
      log.info(
          "Infrastructure with identifier {} is not configured with an Infrastructure definition. Infrastructure Yaml is empty",
          infrastructureEntity.getIdentifier());
      return InfrastructureYamlMetadata.builder()
          .infrastructureIdentifier(infrastructureEntity.getIdentifier())
          .infrastructureYaml("")
          .inputSetTemplateYaml("")
          .build();
    }

    final String infrastructureInputSetYaml = createInfrastructureInputsFromYaml(infrastructureEntity.getAccountId(),
        infrastructureEntity.getOrgIdentifier(), infrastructureEntity.getProjectIdentifier(),
        infrastructureEntity.getEnvIdentifier(), infrastructureEntity.getIdentifier());
    return InfrastructureYamlMetadata.builder()
        .infrastructureIdentifier(infrastructureEntity.getIdentifier())
        .infrastructureYaml(infrastructureEntity.getYaml())
        .inputSetTemplateYaml(infrastructureInputSetYaml)
        .build();
  }

  @Override
  public String createInfrastructureInputsFromYaml(String accountId, String orgIdentifier, String projectIdentifier,
      String environmentIdentifier, String infraIdentifier) {
    Map<String, Object> yamlInputs = createInfrastructureInputsYamlInternal(
        accountId, orgIdentifier, projectIdentifier, environmentIdentifier, infraIdentifier);

    if (isEmpty(yamlInputs)) {
      return null;
    }
    return YamlPipelineUtils.writeYamlString(yamlInputs);
  }

  InfrastructureEntity getInfrastructureFromEnvAndInfraIdentifier(
      String accountId, String orgId, String projectId, String envId, String infraId) {
    Optional<InfrastructureEntity> infrastructureEntity =
        infrastructureRepository.findByAccountIdAndOrgIdentifierAndProjectIdentifierAndEnvIdentifierAndIdentifier(
            accountId, orgId, projectId, envId, infraId);
    if (infrastructureEntity.isPresent()) {
      return infrastructureEntity.get();
    } else {
      return null;
    }
  }

  private Map<String, Object> createInfrastructureInputsYamlInternal(
      String accountId, String orgIdentifier, String projectIdentifier, String envIdentifier, String infraIdentifier) {
    Map<String, Object> yamlInputs = new HashMap<>();
    InfrastructureEntity infrastructureEntity = getInfrastructureFromEnvAndInfraIdentifier(
        accountId, orgIdentifier, projectIdentifier, envIdentifier, infraIdentifier);
    ObjectMapper mapper = new ObjectMapper();
    ObjectNode infraDefinition = createInfraDefinitionInputs(infrastructureEntity, mapper);
    if (infraDefinition != null) {
      yamlInputs.put("infrastructureInputs", infraDefinition);
    }
    return yamlInputs;
  }

  private ObjectNode createInfraDefinitionInputs(InfrastructureEntity infraEntity, ObjectMapper mapper) {
    String yaml = infraEntity.getYaml();
    if (isEmpty(yaml)) {
      throw new InvalidRequestException("Infrastructure Yaml cannot be empty");
    }
    try {
      String infraDefinitionInputs = RuntimeInputFormHelper.createRuntimeInputForm(yaml, true);
      if (isEmpty(infraDefinitionInputs)) {
        return null;
      }

      YamlField infrastructureDefinitionYamlField =
          YamlUtils.readTree(infraDefinitionInputs).getNode().getField(YamlTypes.INFRASTRUCTURE_DEF);
      return (ObjectNode) infrastructureDefinitionYamlField.getNode().getCurrJsonNode();
    } catch (IOException e) {
      throw new InvalidRequestException("Error occurred while creating Service Override inputs ", e);
    }
  }
}
