# Multi-tenant SaaS

## Web uygulaması

PostgreSQL'i başlatıp backend'i çalıştırdıktan sonra:

```bash
cd web
npm install
npm run dev
```

Web istemcisi varsayılan olarak `http://localhost:8080` adresindeki API'ye bağlanır. Farklı bir API adresi için `VITE_API_URL` kullanılabilir.

Kayıt ve giriş ekranları, tenant üyeliklerini ve token yenilemeyi içerir. Access token bellekte tutulur; refresh token tarayıcı depolamasında kalır ve süresi dolan access token'lar Axios interceptor'ındaki tekil refresh kilidiyle yenilenir. Aynı anda gelen 401 yanıtları tek refresh isteğini bekler.