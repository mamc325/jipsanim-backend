package com.jipsanim.property.repository;

import com.jipsanim.property.domain.PropertyStatus;
import com.jipsanim.property.domain.QProperty;
import com.jipsanim.property.domain.QPropertyImage;
import com.jipsanim.property.dto.PropertySearchCondition;
import com.jipsanim.property.dto.PropertySummaryResponse;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.Projections;
import com.querydsl.jpa.impl.JPAQueryFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class PropertySearchRepositoryImpl implements PropertySearchRepository {

    private final JPAQueryFactory queryFactory;

    public PropertySearchRepositoryImpl(JPAQueryFactory queryFactory) {
        this.queryFactory = queryFactory;
    }

    @Override
    public Page<PropertySummaryResponse> search(PropertySearchCondition c, Pageable pageable) {
        QProperty p = QProperty.property;
        QPropertyImage img = QPropertyImage.propertyImage;

        BooleanBuilder where = new BooleanBuilder();
        where.and(p.status.eq(PropertyStatus.ACTIVE)); // 승인(ACTIVE) 매물만 노출 (FR-051)
        if (StringUtils.hasText(c.regionName())) {
            where.and(p.regionName.containsIgnoreCase(c.regionName()));
        }
        if (StringUtils.hasText(c.sigunguCode())) {
            where.and(p.sigunguCode.eq(c.sigunguCode()));
        }
        if (c.dealType() != null) {
            where.and(p.dealType.eq(c.dealType()));
        }
        if (c.propertyType() != null) {
            where.and(p.propertyType.eq(c.propertyType()));
        }
        if (c.minDeposit() != null) {
            where.and(p.deposit.goe(c.minDeposit()));
        }
        if (c.maxDeposit() != null) {
            where.and(p.deposit.loe(c.maxDeposit()));
        }
        if (c.minRent() != null) {
            where.and(p.monthlyRent.goe(c.minRent()));
        }
        if (c.maxRent() != null) {
            where.and(p.monthlyRent.loe(c.maxRent()));
        }
        if (c.minArea() != null) {
            where.and(p.area.goe(c.minArea()));
        }
        if (c.maxArea() != null) {
            where.and(p.area.loe(c.maxArea()));
        }
        if (c.roomCount() != null) {
            where.and(p.roomCount.eq(c.roomCount()));
        }

        List<PropertySummaryResponse> content = queryFactory
                .select(Projections.constructor(PropertySummaryResponse.class,
                        p.id, p.title, p.regionName, p.dealType, p.deposit, p.monthlyRent, p.area, p.roomCount,
                        img.imageUrl))
                .from(p)
                .leftJoin(img).on(img.property.eq(p).and(img.primary.isTrue()))
                .where(where)
                .orderBy(orderBy(pageable, p))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        Long total = queryFactory.select(p.count()).from(p).where(where).fetchOne();
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    private OrderSpecifier<?>[] orderBy(Pageable pageable, QProperty p) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        for (Sort.Order o : pageable.getSort()) {
            Order dir = o.isAscending() ? Order.ASC : Order.DESC;
            switch (o.getProperty()) { // 화이트리스트만 허용
                case "deposit" -> orders.add(new OrderSpecifier<>(dir, p.deposit));
                case "monthlyRent" -> orders.add(new OrderSpecifier<>(dir, p.monthlyRent));
                case "area" -> orders.add(new OrderSpecifier<>(dir, p.area));
                case "createdAt" -> orders.add(new OrderSpecifier<>(dir, p.createdAt));
                default -> { }
            }
        }
        if (orders.isEmpty()) {
            orders.add(p.createdAt.desc());
        }
        return orders.toArray(new OrderSpecifier[0]);
    }
}
