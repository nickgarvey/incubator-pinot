/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.parsers.utils;

import java.util.List;
import org.apache.pinot.common.request.AggregationInfo;
import org.apache.pinot.common.request.BrokerRequest;
import org.apache.pinot.common.request.FilterQuery;
import org.apache.pinot.common.request.FilterQueryMap;
import org.apache.pinot.common.request.GroupBy;
import org.apache.pinot.common.request.Selection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class BrokerRequestComparisonUtils {

  private static final Logger LOGGER = LoggerFactory.getLogger(BrokerRequestComparisonUtils.class);

  public static boolean validate(BrokerRequest br1, BrokerRequest br2) {
    boolean result = br1.equals(br2);
    if (!result) {
      StringBuilder sb = new StringBuilder();

      if (!br1.getQuerySource().getTableName().equals(br2.getQuerySource().getTableName())) {
        sb.append("br1.getQuerySource().getTableName() = ").append(br1.getQuerySource().getTableName()).append("\n")
            .append("br2.getQuerySource().getTableName() = ").append(br2.getQuerySource().getTableName());
        LOGGER.error("QuerySource did not match after conversion.{}", sb);
        return false;
      }

      if (br1.getFilterQuery() != null) {
        if (!validateFilterQuery(br1.getFilterQuery(), br2.getFilterQuery())) {
          sb.append("br1.getFilterQuery() = ").append(br1.getFilterQuery()).append("\n")
              .append("br2.getFilterQuery() = ").append(br2.getFilterQuery());
          LOGGER.error("Filter did not match after conversion.{}", sb);
          return false;
        }

        if (!validateFilterSubQueryMap(br1.getFilterSubQueryMap(), br2.getFilterSubQueryMap())) {
          sb.append("br1.getFilterSubQueryMap() = ").append(br1.getFilterSubQueryMap()).append("\n")
              .append("br2.getFilterSubQueryMap() = ").append(br2.getFilterSubQueryMap());
          LOGGER.error("FilterSubQueryMap did not match after conversion. {}", sb);
          return false;
        }
      }
      if (br1.getSelections() != null) {
        if (!validateSelections(br1.getSelections(), br2.getSelections())) {
          sb.append("br1.getSelections() = ").append(br1.getSelections()).append("\n").append("br2.getSelections() = ")
              .append(br2.getSelections());
          LOGGER.error("Selection did not match after conversion:{}", sb);
          return false;
        }
      }
      if (br1.getGroupBy() != null) {
        if (!validateGroupBy(br1.getGroupBy(), br2.getGroupBy())) {
          sb.append("br1.getGroupBy() = ").append(br1.getGroupBy()).append("\n").append("br2.getGroupBy() = ")
              .append(br2.getGroupBy());
          LOGGER.error("Group By did not match conversion:{}", sb);
          return false;
        }
      }
      if (br1.getAggregationsInfo() != null) {
        if (!validateAggregations(br1.getAggregationsInfo(), br2.getAggregationsInfo())) {
          sb.append("br1.getGroupBy() = ").append(br1.getGroupBy()).append("\n").append("br2.getGroupBy() = ")
              .append(br2.getGroupBy());
          LOGGER.error("Group By did not match conversion:{}", sb);
          return false;
        }
      }
    }
    return true;
  }

  private static boolean validateAggregations(List<AggregationInfo> agg1, List<AggregationInfo> agg2) {
    if (agg1.size() != agg2.size()) {
      LOGGER.error("Failed to validate AggregationInfos: size doesn't match.\n\t{}\n\t{}", agg1, agg2);
      return false;
    }
    for (int i = 0; i < agg1.size(); i++) {
      if (!validateAggregation(agg1.get(i), agg2.get(i))) {
        LOGGER.error("Failed to validate AggregationInfo at idx {} doesn't match.\n\t{}\n\t{}", i, agg1, agg2);
        return false;
      }
    }
    return true;
  }

  private static boolean validateAggregation(AggregationInfo agg1, AggregationInfo agg2) {
    if (!agg1.getAggregationType().equals(agg2.getAggregationType())) {
      LOGGER.error("Failed to validate AggregationInfo: AggregationType doesn't match.\n\t{}\n\t{}", agg1, agg2);
      return false;
    }
    if (agg1.getAggregationParamsSize() != agg2.getAggregationParamsSize()) {
      LOGGER.error("Failed to validate AggregationInfo: AggregationParamsSize doesn't match.\n\t{}\n\t{}", agg1, agg2);
      return false;
    }
    for (int i = 0; i < agg1.getAggregationParamsSize(); i++) {
      for (String key : agg1.getAggregationParams().keySet()) {
        if (!agg1.getAggregationParams().get(key).equals(agg2.getAggregationParams().get(key))) {
          LOGGER
              .error("Failed to validate AggregationInfo: AggregationParams at key {} doesn't match.\n\t{}\n\t{}", key,
                  agg1.getAggregationParams().get(key), agg2.getAggregationParams().get(key));
          return false;
        }
      }
    }
    return true;
  }

  private static boolean validateGroupBy(GroupBy groupBy1, GroupBy groupBy2) {
    if (groupBy1.getTopN() != groupBy2.getTopN()) {
      LOGGER.error("Failed to validate GroupBy: getTopN doesn't match.\n\t{}\n\t{}", groupBy1, groupBy2);
      return false;
    }
    for (int i = 0; i < groupBy1.getExpressions().size(); i++) {
      final String s1 = groupBy1.getExpressions().get(i);
      final String s2 = groupBy2.getExpressions().get(i);
      if (!s1.equals(s2)) {
        LOGGER.error("Failed to validate GroupBy: Expressions at idx {} doesn't match.\n\t{}\n\t{}", i, s1, s2);
        return false;
      }
    }
    return true;
  }

  private static boolean validateSelections(Selection s1, Selection s2) {
    if (s1.getSelectionColumnsSize() != s2.getSelectionColumnsSize()) {
      LOGGER.error("Failed to validate Selections: selectionColumnsSize doesn't match.\n\t{}\n\t{}", s1, s2);
      return false;
    }
    for (int i = 0; i < s1.getSelectionColumns().size(); i++) {
      if (!s1.getSelectionColumns().get(i).equals(s2.getSelectionColumns().get(i))) {
        LOGGER.error("Failed to validate Selections: SelectionColumn at idx {} doesn't match.\n\t{}\n\t{}", i, s1, s2);
        return false;
      }
    }
    if (s1.getSelectionSortSequenceSize() != s2.getSelectionSortSequenceSize()) {
      LOGGER.error("Failed to validate Selections: SelectionSortSequenceSize doesn't match.\n\t{}\n\t{}", s1, s2);
      return false;
    }
    if (s1.getSelectionSortSequence() != null) {
      for (int i = 0; i < s1.getSelectionSortSequence().size(); i++) {
        if (!s1.getSelectionSortSequence().get(i).getColumn()
            .equals(s2.getSelectionSortSequence().get(i).getColumn())) {
          LOGGER
              .error("Failed to validate Selections: SelectionSortSequence Column at idx {} doesn't match.\n\t{}\n\t{}",
                  i, s1, s2);
          return false;
        }
        if (s1.getSelectionSortSequence().get(i).isIsAsc() != s2.getSelectionSortSequence().get(i).isIsAsc()) {
          LOGGER
              .error("Failed to validate Selections: SelectionSortSequence isAsc at idx {} doesn't match.\n\t{}\n\t{}",
                  i, s1, s2);
          return false;
        }
      }
    }
    return true;
  }

  private static boolean validateFilterSubQueryMap(FilterQueryMap map1, FilterQueryMap map2) {
    for (int idx : map1.getFilterQueryMap().keySet()) {
      final FilterQuery q1 = map1.getFilterQueryMap().get(idx);
      final FilterQuery q2 = map2.getFilterQueryMap().get(idx);
      if (!validateFilterQuery(q1, q2)) {
        LOGGER.error("Failed to validate FilterSubQueryMap:\n\t{}\n\t{}", q1, q2);
        return false;
      }
    }
    return true;
  }

  private static boolean validateFilterQuery(FilterQuery fq1, FilterQuery fq2) {
    if (fq1.getId() != fq2.getId()) {
      LOGGER.error("Failed to validate FilterQuery: Id doesn't match.\n\t{}\n\t{}", fq1, fq2);
      return false;
    }
    if (fq1.getColumn() != null) {
      if (!fq1.getColumn().equals(fq2.getColumn())) {
        LOGGER.error("Failed to validate FilterQuery: Column doesn't match.\n\t{}\n\t{}", fq1, fq2);
        return false;
      }
    }
    if (fq1.getOperator() != fq2.getOperator()) {
      LOGGER.error("Failed to validate FilterQuery: Operator doesn't match.\n\t{}\n\t{}", fq1, fq2);
      return false;
    }
    if (fq1.getValue() != null) {
      if (fq1.getValue().size() != fq2.getValue().size()) {
        LOGGER.error("Failed to validate FilterQuery: value size doesn't match.\n\t{}\n\t{}", fq1, fq2);
        return false;
      }
      for (int i = 0; i < fq1.getValue().size(); i++) {
        final String s1 = fq1.getValue().get(i);
        final String s2 = fq2.getValue().get(i);
        if (!s1.equals(s2)) {
          LOGGER.error("Failed to validate FilterQuery: value at idx {} doesn't match.\n\t{}\n\t{}\n\t{}\n\t{}", i, fq1,
              fq2, s1, s2);
          return false;
        }
      }
    }
    if (fq1.getNestedFilterQueryIds() != null) {
      if (fq1.getNestedFilterQueryIds().size() != fq2.getNestedFilterQueryIds().size()) {
        LOGGER.error("Failed to validate FilterQuery: nestedFilterQueryIds size doesn't match.\n\t{}\n\t{}", fq1, fq2);
        return false;
      }
      for (int i = 0; i < fq1.getNestedFilterQueryIds().size(); i++) {
        if (fq1.getNestedFilterQueryIds().get(i) != fq2.getNestedFilterQueryIds().get(i)) {
          LOGGER.error("Failed to validate FilterQuery: nestedFilterQueryIds at idx {} doesn't match.\n\t{}\n\t{}", i,
              fq1, fq2);
          return false;
        }
      }
    }
    return true;
  }
}
