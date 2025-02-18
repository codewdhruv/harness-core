/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.connector.validator;

import static io.harness.connector.ConnectivityStatus.FAILURE;
import static io.harness.connector.ConnectivityStatus.SUCCESS;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.harness.CategoryTest;
import io.harness.category.element.UnitTests;
import io.harness.connector.ConnectorValidationResult;
import io.harness.connector.helper.EncryptionHelper;
import io.harness.delegate.beans.connector.servicenow.ServiceNowConnectorDTO;
import io.harness.delegate.beans.connector.servicenow.connection.ServiceNowTestConnectionTaskNGResponse;
import io.harness.encryption.SecretRefData;
import io.harness.rule.Owner;
import io.harness.rule.OwnerRule;
import io.harness.secretmanagerclient.services.api.SecretManagerClientService;
import io.harness.service.DelegateGrpcClientWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public class ServiceNowConnectorValidatorTest extends CategoryTest {
  private static final String ACCOUNT_IDENTIFIER = "accountIdentifier";
  private static final String SERVICENOW_URL = "https://harness.service-now.com";
  private static final String ORG_IDENTIFIER = "orgIdentifier";
  private static final String PROJECT_IDENTIFIER = "projectIdentifier";
  private static final String IDENTIFIER = "identifier";
  private static final String USERNAME = "username";

  @Mock private DelegateGrpcClientWrapper delegateGrpcClientWrapper;
  @Mock private SecretManagerClientService ngSecretService;
  @Mock private EncryptionHelper encryptionHelper;
  @InjectMocks ServiceNowConnectorValidator connectorValidator;

  @Before
  public void setup() {
    MockitoAnnotations.initMocks(this);
  }

  @Test
  @Owner(developers = OwnerRule.PRABU)
  @Category(UnitTests.class)
  public void shouldValidate() {
    ServiceNowConnectorDTO serviceNowConnectorDTO = ServiceNowConnectorDTO.builder()
                                                        .username(USERNAME)
                                                        .serviceNowUrl(SERVICENOW_URL)
                                                        .passwordRef(SecretRefData.builder().build())
                                                        .build();
    when(encryptionHelper.getEncryptionDetail(any(), any(), any(), any())).thenReturn(null);

    when(ngSecretService.getEncryptionDetails(any(), any())).thenReturn(null);
    when(delegateGrpcClientWrapper.executeSyncTask(any()))
        .thenReturn(ServiceNowTestConnectionTaskNGResponse.builder().canConnect(true).build());

    ConnectorValidationResult result = connectorValidator.validate(
        serviceNowConnectorDTO, ACCOUNT_IDENTIFIER, ORG_IDENTIFIER, PROJECT_IDENTIFIER, IDENTIFIER);

    assertThat(result.getStatus()).isEqualTo(SUCCESS);
    verify(delegateGrpcClientWrapper).executeSyncTask(any());
  }

  @Test
  @Owner(developers = OwnerRule.PRABU)
  @Category(UnitTests.class)
  public void shouldValidateFailed() {
    ServiceNowConnectorDTO serviceNowConnectorDTO = ServiceNowConnectorDTO.builder()
                                                        .username(USERNAME)
                                                        .serviceNowUrl(SERVICENOW_URL)
                                                        .passwordRef(SecretRefData.builder().build())
                                                        .build();
    when(encryptionHelper.getEncryptionDetail(any(), any(), any(), any())).thenReturn(null);

    when(ngSecretService.getEncryptionDetails(any(), any())).thenReturn(null);
    when(delegateGrpcClientWrapper.executeSyncTask(any()))
        .thenReturn(ServiceNowTestConnectionTaskNGResponse.builder().canConnect(false).build());

    ConnectorValidationResult result = connectorValidator.validate(
        serviceNowConnectorDTO, ACCOUNT_IDENTIFIER, ORG_IDENTIFIER, PROJECT_IDENTIFIER, IDENTIFIER);

    assertThat(result.getStatus()).isEqualTo(FAILURE);
    verify(delegateGrpcClientWrapper).executeSyncTask(any());
  }
}
