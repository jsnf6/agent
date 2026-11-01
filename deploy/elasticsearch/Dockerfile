# Elasticsearch 8.18.0 + IK 中文分词插件
# 项目配置里 analyzer=ik_max_word / search_analyzer=ik_smart，
# 官方镜像不带 IK，未安装时 ES 会直接拒绝建索引（mapper_parsing_exception），因此这里预先装好。
FROM docker.elastic.co/elasticsearch/elasticsearch:8.18.0

RUN curl -fsSL -o /tmp/analysis-ik.zip https://get.infini.cloud/elasticsearch/analysis-ik/8.18.0 \
    && bin/elasticsearch-plugin install --batch file:///tmp/analysis-ik.zip \
    && rm -f /tmp/analysis-ik.zip