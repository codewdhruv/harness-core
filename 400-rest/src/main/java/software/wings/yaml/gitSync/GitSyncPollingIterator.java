/*
 * Copyright 2022 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package software.wings.yaml.gitSync;

import static io.harness.annotations.dev.HarnessTeam.DX;
import static io.harness.mongo.iterator.MongoPersistenceIterator.SchedulingType.REGULAR;

import static java.time.Duration.ofMinutes;

import io.harness.annotations.dev.OwnedBy;
import io.harness.ff.FeatureFlagService;
import io.harness.iterator.PersistenceIteratorFactory;
import io.harness.mongo.iterator.MongoPersistenceIterator;
import io.harness.mongo.iterator.filter.MorphiaFilterExpander;
import io.harness.mongo.iterator.provider.MorphiaPersistenceProvider;

import software.wings.service.intfc.AccountService;
import software.wings.service.intfc.yaml.YamlChangeSetService;
import software.wings.yaml.gitSync.YamlGitConfig.YamlGitConfigKeys;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.AllArgsConstructor;

@Singleton
@AllArgsConstructor(onConstructor = @__({ @Inject }))
@OwnedBy(DX)
public class GitSyncPollingIterator implements MongoPersistenceIterator.Handler<YamlGitConfig> {
  @Inject PersistenceIteratorFactory persistenceIteratorFactory;
  @Inject AccountService accountService;
  @Inject private MorphiaPersistenceProvider<YamlGitConfig> persistenceProvider;
  @Inject private FeatureFlagService featureFlagService;
  @Inject YamlChangeSetService yamlChangeSetService;

  public void registerIterators(int threadPollSize) {
    persistenceIteratorFactory.createPumpIteratorWithDedicatedThreadPool(
        PersistenceIteratorFactory.PumpExecutorOptions.builder()
            .name("GitSyncPollingIterator")
            .poolSize(threadPollSize)
            .interval(ofMinutes(1))
            .build(),
        YamlGitConfig.class,
        MongoPersistenceIterator.<YamlGitConfig, MorphiaFilterExpander<YamlGitConfig>>builder()
            .clazz(YamlGitConfig.class)
            .fieldName(YamlGitConfigKeys.gitPollingIterator)
            .targetInterval(ofMinutes(5))
            .acceptableNoAlertDelay(ofMinutes(1))
            .acceptableExecutionTime(ofMinutes(1))
            .handler(this)
            .schedulingType(REGULAR)
            .persistenceProvider(persistenceProvider)
            .redistribute(true));
  }

  @Override
  public void handle(YamlGitConfig entity) {
    yamlChangeSetService.pushYamlChangeSetForGitToHarness(entity.getAccountId(), entity.getBranchName(),
        entity.getGitConnectorId(), entity.getRepositoryName(), entity.getAppId());
  }
}
