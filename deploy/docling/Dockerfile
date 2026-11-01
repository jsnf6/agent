# Docling Serve 文档结构解析服务
# 固定 v1.38.1；国内默认走南京大学 quay.io 镜像，可通过 DOCLING_BASE_IMAGE 覆盖。
ARG DOCLING_BASE_IMAGE=quay.nju.edu.cn/docling-project/docling-serve:v1.38.1
FROM ${DOCLING_BASE_IMAGE}

ENV HF_ENDPOINT=https://hf-mirror.com \
    HF_HOME=/opt/app-root/src/.cache/huggingface \
    DOCLING_SERVE_ARTIFACTS_PATH=/opt/app-root/src/.cache/docling/models

# 命名卷首次挂载到 /opt/app-root/src/.cache 时会继承镜像内目录权限；
# 提前建好并授权给运行用户 1001，避免模型下载时报 Permission denied。
USER 0
RUN mkdir -p /opt/app-root/src/.cache/huggingface /opt/app-root/src/.cache/docling/models \
    && chown -R 1001:0 /opt/app-root/src/.cache
USER 1001
