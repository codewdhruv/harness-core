/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package software.wings.graphql.schema.type.aggregation.connector;

import io.harness.annotations.dev.HarnessModule;
import io.harness.annotations.dev.TargetModule;

import software.wings.graphql.schema.type.aggregation.EntityFilter;
import software.wings.graphql.schema.type.aggregation.QLIdFilter;
import software.wings.graphql.schema.type.aggregation.QLTimeFilter;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
@TargetModule(HarnessModule._380_CG_GRAPHQL)
public class QLConnectorFilter implements EntityFilter {
  QLIdFilter connector;
  QLConnectorTypeFilter connectorType;
  QLTimeFilter createdAt;
}
