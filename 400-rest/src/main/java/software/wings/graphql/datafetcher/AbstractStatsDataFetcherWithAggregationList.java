/*
 * Copyright 2021 Harness Inc. All rights reserved.
 * Use of this source code is governed by the PolyForm Free Trial 1.0.0 license
 * that can be found in the licenses directory at the root of this repository, also available at
 * https://polyformproject.org/wp-content/uploads/2020/05/PolyForm-Free-Trial-1.0.0.txt.
 */

package software.wings.graphql.datafetcher;

import static io.harness.data.structure.EmptyPredicate.isEmpty;
import static io.harness.logging.AutoLogContext.OverrideBehavior.OVERRIDE_ERROR;

import static org.apache.commons.lang3.BooleanUtils.isTrue;

import io.harness.annotations.dev.HarnessModule;
import io.harness.annotations.dev.TargetModule;
import io.harness.eraro.ResponseMessage;
import io.harness.exception.InvalidRequestException;
import io.harness.exception.WingsException;
import io.harness.exception.WingsException.ReportTarget;
import io.harness.logging.AccountLogContext;
import io.harness.logging.AutoLogContext;
import io.harness.logging.ExceptionLogger;

import software.wings.beans.instance.dashboard.EntitySummary;
import software.wings.dl.WingsPersistence;
import software.wings.graphql.schema.type.aggregation.QLData;
import software.wings.service.impl.AggregateFunctionLogContext;
import software.wings.service.impl.FilterLogContext;
import software.wings.service.impl.GroupByLogContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.inject.Inject;
import graphql.GraphQLContext;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.FieldDefaults;
import lombok.extern.slf4j.Slf4j;

@FieldDefaults(level = AccessLevel.PRIVATE)
@Slf4j
@TargetModule(HarnessModule._380_CG_GRAPHQL)
public abstract class AbstractStatsDataFetcherWithAggregationList<A, F, G, S>
    implements DataFetcher, BaseStatsDataFetcher, CEBaseStatsDataFetcher {
  private static final String AGGREGATE_FUNCTION = "aggregateFunction";
  private static final String FILTERS = "filters";
  private static final String GROUP_BY = "groupBy";
  private static final String SORT_CRITERIA = "sortCriteria";
  private static final String EXCEPTION_MSG_DELIMITER = ";; ";
  protected static final String EXCEPTION_MSG = "An error has occurred. Please contact the Harness support team.";

  @Inject protected DataFetcherUtils utils;
  @Inject protected WingsPersistence wingsPersistence;

  public static final int MAX_RETRY = 3;

  protected abstract QLData fetch(
      String accountId, List<A> aggregateFunction, List<F> filters, List<G> groupBy, List<S> sort);

  protected abstract QLData postFetch(
      String accountId, List<G> groupByList, List<A> aggregations, List<S> sort, QLData qlData);

  @Override
  public final Object get(DataFetchingEnvironment dataFetchingEnvironment) {
    Object result;
    long startTime = System.currentTimeMillis();
    try {
      Type[] typeArguments = ((ParameterizedType) getClass().getGenericSuperclass()).getActualTypeArguments();

      Class<A> aggregationFunctionClass = (Class<A>) typeArguments[0];
      Class<F> filterClass = (Class<F>) typeArguments[1];
      Class<G> groupByClass = (Class<G>) typeArguments[2];
      Class<S> sortClass = (Class<S>) typeArguments[3];

      final List<A> aggregateFunctions =
          fetchObject(dataFetchingEnvironment, AGGREGATE_FUNCTION, aggregationFunctionClass);

      final List<F> filters = fetchObject(dataFetchingEnvironment, FILTERS, filterClass);

      final List<G> groupBy = fetchObject(dataFetchingEnvironment, GROUP_BY, groupByClass);
      final List<S> sort = fetchObject(dataFetchingEnvironment, SORT_CRITERIA, sortClass);

      String accountId = utils.getAccountId(dataFetchingEnvironment);
      final String accountIdDataToFetch =
          isTrue(isCESampleAccountIdAllowed()) ? utils.fetchSampleAccountIdIfNoClusterData(accountId) : accountId;

      try (AutoLogContext ignore1 = new AccountLogContext(accountId, OVERRIDE_ERROR);
           AutoLogContext ignore2 =
               new AggregateFunctionLogContext(aggregationFunctionClass.getSimpleName(), OVERRIDE_ERROR);
           AutoLogContext ignore3 = new FilterLogContext(filterClass.getSimpleName(), OVERRIDE_ERROR);
           AutoLogContext ignore4 = new GroupByLogContext(groupByClass.getSimpleName(), OVERRIDE_ERROR)) {
        QLData qlData = fetch(accountIdDataToFetch, aggregateFunctions, filters, groupBy, sort);
        QLData postFetchResult = postFetch(accountId, groupBy, aggregateFunctions, sort, qlData);
        result = qlData;
        if (postFetchResult != null) {
          result = postFetchResult;
        }
      }
    } catch (WingsException exception) {
      throw new InvalidRequestException(getCombinedErrorMessages(exception), exception, exception.getReportTargets());
    } catch (Exception exception) {
      throw new InvalidRequestException(EXCEPTION_MSG, exception, WingsException.USER_SRE);
    } finally {
      log.info("Time taken for the stats call (abstractStatsDataFetcherWithAggregationList) {}",
          System.currentTimeMillis() - startTime);
    }

    return result;
  }

  private <O> List<O> fetchObject(DataFetchingEnvironment dataFetchingEnvironment, String fieldName, Class<O> klass) {
    Object object = dataFetchingEnvironment.getArguments().get(fieldName);
    if (object == null) {
      return (List<O>) Lists.newArrayList();
    }
    Collection returnCollection = Lists.newArrayList();
    Collection collection = (Collection) object;
    collection.forEach(item -> returnCollection.add(convertToObject(item, klass)));
    return (List<O>) returnCollection;
  }

  private <O> O convertToObject(Object fromValue, Class<O> klass) {
    ObjectMapper mapper = new ObjectMapper();
    return mapper.convertValue(fromValue, klass);
  }

  private String getCombinedErrorMessages(WingsException ex) {
    List<ResponseMessage> responseMessages = ExceptionLogger.getResponseMessageList(ex, ReportTarget.GRAPHQL_API);
    return responseMessages.stream()
        .map(ResponseMessage::getMessage)
        .collect(Collectors.joining(EXCEPTION_MSG_DELIMITER));
  }

  protected String getAccountId(DataFetchingEnvironment environment) {
    GraphQLContext context = environment.getContext();
    String accountId = context.get("accountId");

    if (isEmpty(accountId)) {
      throw new InvalidRequestException("accountId is null in the environment");
    }

    return accountId;
  }

  @Value
  @Builder
  public static class TwoLevelAggregatedData {
    private EntitySummary firstLevelInfo;
    private EntitySummary secondLevelInfo;
    private long count;
  }
}
