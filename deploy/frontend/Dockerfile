# 前端运行时镜像：nginx 托管 vite 构建产物，并反向代理后端接口
# 上游地址通过环境变量 BACKEND_UPSTREAM 注入（容器启动时 envsubst 渲染模板），
# 默认指向宿主机 9082，这样后端跑在容器里或跑在 IDE 里都能用同一个 8088 入口。
FROM nginx:1.27-alpine

ENV TZ=Asia/Shanghai

RUN ln -snf /usr/share/zoneinfo/$TZ /etc/localtime && echo $TZ > /etc/timezone \
    && rm -f /etc/nginx/conf.d/default.conf

COPY nginx.conf.template /etc/nginx/templates/nginx.conf.template
COPY proxy-common.conf /etc/nginx/snippets/proxy-common.conf
COPY dist/ /usr/share/nginx/html/

EXPOSE 80