## 前端服务器 Nginx 的配置

```shell
server {
    server_name db-genius.hlt.cab;

    root /var/www/db-genius/;
    index index.html;

    # 1. 处理前端静态资源和路由
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 2. 新增：处理后端 API 接口请求
    location /api/ {
        proxy_pass http://xxx:8109/api/; # 转发到后端真实地址
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # SSE 必需：关闭代理缓冲，否则后端逐条 flush 的事件会被 nginx 攒住成批吐给前端
        # （/chat 的"意图识别中/结果同时到达、步骤不实时"就是这个默认行为导致的；
        # 后端响应头 X-Accel-Buffering: no 也能让 nginx 对该响应关缓冲，此处双保险）
        proxy_buffering off;
        # SSE 必需：长连接读超时。默认 60s，Agent 任务中工具执行/summary 生成可能
        # 超过 60s 无事件，会被 nginx 断连；需 >= 后端 SseEmitter 超时（300s）
        proxy_read_timeout 360s;
        # 推荐：默认 HTTP/1.0 连上游无 chunked 编码，SSE 场景升到 1.1
        proxy_http_version 1.1;
        proxy_set_header Connection "";
    }

    gzip on;
    gzip_types text/plain text/css application/json application/javascript text/xml application/xml application/xml+rss text/javascript;



    listen 443 ssl; # managed by Certbot
    ssl_certificate /etc/letsencrypt/live/db-genius.hlt.cab/fullchain.pem; # managed by Certbot
    ssl_certificate_key /etc/letsencrypt/live/db-genius.hlt.cab/privkey.pem; # managed by Certbot
    include /etc/letsencrypt/options-ssl-nginx.conf; # managed by Certbot
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem; # managed by Certbot

}
server {
    if ($host = db-genius.hlt.cab) {
        return 301 https://$host$request_uri;
    } # managed by Certbot


    server_name db-genius.hlt.cab;
    listen 80;
    return 404; # managed by Certbot


}
```


##  后端服务器 Nginx 的配置

裸机开端口的，地址已经在前端 nginx 中配置了，无 nginx