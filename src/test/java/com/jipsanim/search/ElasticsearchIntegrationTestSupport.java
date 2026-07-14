package com.jipsanim.search;

import com.jipsanim.TestcontainersConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;

/**
 * ES 통합 테스트 베이스. nori 플러그인 포함 커스텀 이미지를 빌드해 ElasticsearchContainer 로 띄우고,
 * search.elasticsearch.enabled=true 로 오버라이드(@DynamicPropertySource — 시스템 프로퍼티보다 우선).
 * 일반 테스트(build.gradle systemProperty false)는 ES 없이 기동되므로 영향 없음.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
public abstract class ElasticsearchIntegrationTestSupport {

    protected static final ElasticsearchContainer ES;

    static {
        DockerImageName noriImage = DockerImageName.parse(
                        new ImageFromDockerfile("jipsanim-es-nori:test", false)
                                .withDockerfile(Path.of("docker/elasticsearch-nori/Dockerfile"))
                                .get())
                .asCompatibleSubstituteFor("docker.elastic.co/elasticsearch/elasticsearch");
        ES = new ElasticsearchContainer(noriImage)
                .withEnv("xpack.security.enabled", "false");
        ES.start();
    }

    @DynamicPropertySource
    static void elasticsearchProps(DynamicPropertyRegistry registry) {
        registry.add("search.elasticsearch.enabled", () -> "true");
        registry.add("spring.data.elasticsearch.repositories.enabled", () -> "true");
        registry.add("spring.elasticsearch.uris", () -> "http://" + ES.getHttpHostAddress());
    }
}
