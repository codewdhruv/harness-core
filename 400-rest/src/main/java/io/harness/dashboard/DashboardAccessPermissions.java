/*
 * Copyright 2020 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Shield 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/06/PolyForm-Shield-1.0.0.txt.
 */

package io.harness.dashboard;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * @author rktummala on 06/30/19
 */
@Value
@Builder
public class DashboardAccessPermissions {
  private List<String> userGroups;
  private List<Action> allowedActions;
}
