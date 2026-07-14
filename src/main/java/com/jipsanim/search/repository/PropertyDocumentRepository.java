package com.jipsanim.search.repository;

import com.jipsanim.search.document.PropertyDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;

/**
 * ES 매물 문서 리포지토리. 활성화는 ElasticsearchRepositoryConfig 가 게이팅(search.elasticsearch.enabled).
 */
public interface PropertyDocumentRepository extends ElasticsearchRepository<PropertyDocument, String> {
}
