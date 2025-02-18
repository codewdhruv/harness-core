/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package io.harness.ngmigration.expressions;

import io.harness.expression.ExpressionEvaluatorUtils;

import com.google.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import org.jetbrains.annotations.NotNull;

@Singleton
public class MigratorExpressionUtils {
  public Object render(Object object) {
    Map<String, Object> context = prepareContextMap();
    return ExpressionEvaluatorUtils.updateExpressions(object, new MigratorResolveFunctor(context));
  }

  @NotNull
  Map<String, Object> prepareContextMap() {
    Map<String, Object> context = new HashMap<>();
    // Infra Expressions
    context.put("infra.kubernetes.namespace", "<+infra.namespace>");
    context.put("infra.kubernetes.infraId", "<+INFRA_KEY>");

    // Env Expressions
    context.put("env.name", "<+env.name>");
    context.put("env.description", "<+env.description>");
    context.put("env.environmentType", "<+env.type>");

    // Artifact Expressions
    context.put("artifact.metadata.image", "<+artifact.image>");
    context.put("artifact.source.dockerconfig", "<+artifact.imagePullSecret>");

    // Variables
    context.put("workflow.variables", new WorkflowVariablesMigratorFunctor());
    context.put("pipeline.variables", new PipelineVariablesMigratorFunctor());
    context.put("serviceVariable", new ServiceVariablesMigratorFunctor());

    // Secrets
    context.put("secrets", new SecretMigratorFunctor());

    return context;
  }
}
