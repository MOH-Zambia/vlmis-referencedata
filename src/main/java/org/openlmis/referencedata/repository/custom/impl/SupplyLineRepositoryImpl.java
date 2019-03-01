/*
 * This program is part of the OpenLMIS logistics management information system platform software.
 * Copyright © 2017 VillageReach
 *
 * This program is free software: you can redistribute it and/or modify it under the terms
 * of the GNU Affero General Public License as published by the Free Software Foundation, either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Affero General Public License for more details. You should have received a copy of
 * the GNU Affero General Public License along with this program. If not, see
 * http://www.gnu.org/licenses.  For additional information contact info@OpenLMIS.org.
 */

package org.openlmis.referencedata.repository.custom.impl;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.collections4.CollectionUtils.isEmpty;
import static org.apache.commons.collections4.CollectionUtils.isNotEmpty;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.AS;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.FROM;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.INNER_JOIN_FETCH;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.ORDER_BY;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.SELECT_COUNT;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.SELECT_DISTINCT;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.WHERE;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.and;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.dot;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.in;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.isEqual;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.join;
import static org.openlmis.referencedata.repository.custom.impl.SqlConstants.parameter;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.StreamSupport;
import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.Query;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Order;
import javax.persistence.criteria.Path;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import org.openlmis.referencedata.domain.SupplyLine;
import org.openlmis.referencedata.repository.custom.SupplyLineRepositoryCustom;
import org.openlmis.referencedata.util.Pagination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.profiler.Profiler;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public class SupplyLineRepositoryImpl implements SupplyLineRepositoryCustom {

  private static final Logger LOGGER = LoggerFactory.getLogger(SupplyLineRepositoryImpl.class);

  private static final String SUPPLYING_FACILITY = "supplyingFacility";
  private static final String SUPPLYING_FACILITY_IDS = "supplyingFacilityIds";
  private static final String PROGRAM_ID = "programId";
  private static final String SUPERVISORY_NODE_ID = "supervisoryNodeId";

  private static final String SUPPLY_LINE_ALIAS = "sl";
  private static final String SUPERVISORY_NODE_ALIAS = "sn";
  private static final String REQUISITION_GROUP_ALIAS = "rg";

  private static final String FROM_SL = join(FROM, "SupplyLine", AS, SUPPLY_LINE_ALIAS);
  private static final String SELECT_SL = join(SELECT_DISTINCT, SUPPLY_LINE_ALIAS, FROM_SL);
  private static final String COUNT_SL = join(SELECT_COUNT, FROM_SL);

  private static final String SUPERVISORY_NODE_JOIN = join(INNER_JOIN_FETCH,
      dot(SUPPLY_LINE_ALIAS, "supervisoryNode"), AS, SUPERVISORY_NODE_ALIAS);
  private static final String REQUISITION_GROUP_JOIN = join(INNER_JOIN_FETCH,
      dot(SUPERVISORY_NODE_ALIAS, "requisitionGroup"), AS, REQUISITION_GROUP_ALIAS);
  private static final String REQUISITION_GROUP_MEMBERS_JOIN = join(INNER_JOIN_FETCH,
      dot(REQUISITION_GROUP_ALIAS, "memberFacilities"));

  private static final String WITH_PROGRAM_ID =
      isEqual(dot(SUPPLY_LINE_ALIAS, "program.id"), parameter(PROGRAM_ID));
  private static final String WITH_SUPERVISORY_NODE_ID =
      isEqual(dot(SUPPLY_LINE_ALIAS, "supervisoryNode.id"), parameter(SUPERVISORY_NODE_ID));
  private static final String WITH_SUPPLYING_FACILITIES =
      join(dot(SUPPLY_LINE_ALIAS, "supplyingFacility.id"), in(SUPPLYING_FACILITY_IDS));

  @PersistenceContext
  private EntityManager entityManager;

  /**
   * Method returns a page of supply lines matching parameters.
   * Result can be sorted by supplying facility name if
   * "supplyingFacilityName" parameter is used in sort property in pageable object.
   * Using expand
   *
   * @param programId            UUID of the program
   * @param supervisoryNodeId    UUID of the supervisory node
   * @param supplyingFacilityIds UUIDs of the supplying facilities
   * @param expand               set of expand parameters
   * @param pageable             pagination and sorting parameters
   * @return page of supply lines with matched parameters.
   */
  @Override
  public Page<SupplyLine> search(UUID programId, UUID supervisoryNodeId,
      Set<UUID> supplyingFacilityIds, Set<String> expand, Pageable pageable) {

    Profiler profiler = new Profiler("SEARCH_SUPPLY_LINES_WITH_EXPAND_REPOSITORY");
    profiler.setLogger(LOGGER);

    Map<String, Object> params = Maps.newHashMap();
    String whereStatement =
        prepareWhereStatement(programId, supervisoryNodeId, supplyingFacilityIds, params);

    Query countQuery = entityManager.createQuery(join(COUNT_SL, whereStatement), Long.class);
    params.forEach(countQuery::setParameter);
    Long count = (Long) countQuery.getSingleResult();

    if (count < 1) {
      return Pagination.getPage(Collections.emptyList(), pageable, 0);
    }

    String selectStatement = SELECT_SL;
    if (isNotEmpty(expand)) {
      if (expand.contains("supervisoryNode.requisitionGroup")) {
        selectStatement = join(SELECT_SL, SUPERVISORY_NODE_JOIN, REQUISITION_GROUP_JOIN);
      } else if (expand.contains("supervisoryNode.requisitionGroup.memberFacilities")) {
        selectStatement = join(SELECT_SL, SUPERVISORY_NODE_JOIN, REQUISITION_GROUP_JOIN,
            REQUISITION_GROUP_MEMBERS_JOIN);
      }
    }

    Query searchQuery = entityManager.createQuery(
        join(selectStatement, whereStatement, getOrderPredicate(pageable)), SupplyLine.class);
    params.forEach(searchQuery::setParameter);

    List<SupplyLine> result = searchQuery
        .setMaxResults(pageable.getPageSize())
        .setFirstResult(pageable.getOffset())
        .getResultList();

    return Pagination.getPage(result, pageable, count);
  }

  /**
   * Method returns a page of supply lines matching parameters.
   * Result can be sorted by supplying facility name if
   * "supplyingFacilityName" parameter is used in sort property in pageable object.
   *
   * @param programId            UUID of the program
   * @param supervisoryNodeId    UUID of the supervisory node
   * @param supplyingFacilityIds UUIDs of the supplying facilities
   * @param pageable             pagination and sorting parameters
   * @return page of supply lines with matched parameters.
   */
  @Override
  public Page<SupplyLine> search(UUID programId, UUID supervisoryNodeId,
      Set<UUID> supplyingFacilityIds, Pageable pageable) {

    Profiler profiler = new Profiler("SEARCH_SUPPLY_LINES_REPOSITORY");
    profiler.setLogger(LOGGER);

    profiler.start("GET_CRITERIA_BUILDER");
    CriteriaBuilder builder = entityManager.getCriteriaBuilder();

    profiler.start("COUNT_QUERY");
    CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
    countQuery = prepareSearchQuery(
        countQuery, programId, supervisoryNodeId, supplyingFacilityIds, pageable, true);
    Long count = entityManager.createQuery(countQuery).getSingleResult();

    if (count == 0) {
      profiler.stop().log();
      return Pagination.getPage(emptyList(), pageable, 0);
    }

    profiler.start("SEARCH_QUERY");
    CriteriaQuery<SupplyLine> query = builder.createQuery(SupplyLine.class);
    query = prepareSearchQuery(
        query, programId, supervisoryNodeId, supplyingFacilityIds, pageable, false);

    List<SupplyLine> result = entityManager.createQuery(query)
        .setMaxResults(pageable.getPageSize())
        .setFirstResult(pageable.getOffset())
        .getResultList();

    profiler.stop().log();
    return Pagination.getPage(result, pageable, count);
  }

  private <T> CriteriaQuery<T> prepareSearchQuery(CriteriaQuery<T> query, UUID programId,
      UUID supervisoryNodeId, Set<UUID> supplyingFacilityIds, Pageable pageable, boolean count) {
    CriteriaBuilder builder = entityManager.getCriteriaBuilder();
    Root<SupplyLine> root = query.from(SupplyLine.class);

    if (count) {
      CriteriaQuery<Long> countQuery = (CriteriaQuery<Long>) query;
      query = (CriteriaQuery<T>) countQuery.select(builder.count(root));
    }

    Predicate predicate = builder.conjunction();

    if (programId != null) {
      predicate = builder.and(predicate, builder.equal(root.get("program").get("id"), programId));
    }

    if (supervisoryNodeId != null) {
      predicate = builder
          .and(predicate, builder.equal(root.get("supervisoryNode").get("id"), supervisoryNodeId));
    }

    if (isNotEmpty(supplyingFacilityIds)) {
      predicate = builder
          .and(predicate, root.get("supplyingFacility").get("id").in(supplyingFacilityIds));
    }

    query.where(predicate);

    if (!count && pageable != null && pageable.getSort() != null) {
      query = addSortProperties(query, root, pageable);
    }

    return query;
  }

  private <T> CriteriaQuery<T> addSortProperties(CriteriaQuery<T> query,
                                                 Root root, Pageable pageable) {
    CriteriaBuilder builder = entityManager.getCriteriaBuilder();
    List<Order> orders = new ArrayList<>();
    Iterator<Sort.Order> iterator = pageable.getSort().iterator();
    Sort.Order order;

    while (iterator.hasNext()) {
      order = iterator.next();
      String property = order.getProperty();

      Path path;
      if (SUPPLYING_FACILITY.equals(property)) {
        path = root.join(SUPPLYING_FACILITY, JoinType.LEFT).get("name");
      } else {
        path = root.get(property);
      }
      if (order.isAscending()) {
        orders.add(builder.asc(path));
      } else {
        orders.add(builder.desc(path));
      }
    }
    return query.orderBy(orders);
  }

  private String prepareWhereStatement(UUID programId, UUID supervisoryNodeId,
      Set<UUID> supplyingFacilityIds, Map<String, Object> params) {

    List<String> conditions = Lists.newArrayList();

    if (null != programId) {
      conditions.add(WITH_PROGRAM_ID);
      params.put(PROGRAM_ID, programId);
    }

    if (null != supervisoryNodeId) {
      conditions.add(WITH_SUPERVISORY_NODE_ID);
      params.put(SUPERVISORY_NODE_ID, supervisoryNodeId);
    }

    if (isNotEmpty(supplyingFacilityIds)) {
      conditions.add(WITH_SUPPLYING_FACILITIES);
      params.put(SUPPLYING_FACILITY_IDS, supplyingFacilityIds);
    }

    if (isEmpty(conditions)) {
      return "";
    }

    return join(WHERE, and(conditions));
  }

  private String getOrderPredicate(Pageable pageable) {
    if (null != pageable && null != pageable.getSort()) {

      List<String> orderPredicate = StreamSupport.stream(
          Spliterators.spliteratorUnknownSize(pageable.getSort().iterator(), Spliterator.ORDERED),
          false)
          .map(order -> join(
              dot(SUPPLY_LINE_ALIAS, order.getProperty()),
              order.getDirection().toString()))
          .collect(toList());

      return join(ORDER_BY, Joiner.on(", ").join(orderPredicate));
    }

    return "";
  }
}
