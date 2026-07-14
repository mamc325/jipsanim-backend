# 집사님 5차: nori 한글 형태소 분석 플러그인 포함 Elasticsearch 이미지.
# 기본 ES 이미지엔 nori 가 없어 analysis-nori 플러그인을 설치한다. 버전 pin(리뷰 P2).
FROM docker.elastic.co/elasticsearch/elasticsearch:8.13.4

RUN bin/elasticsearch-plugin install --batch analysis-nori
