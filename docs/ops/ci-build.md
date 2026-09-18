# CI build yolu

CI, Java 21 ve Node 22 kullanır. Backend testleri Testcontainers ile PostgreSQL ve
Redis'i açar; timezone regresyonu UTC ve Europe/Istanbul matrix girişlerinde çalışır.

Web için temiz kurulum yolu `cd web && npm ci`, ardından `npm run build` ve `npm test`'
tir. Yerel `node_modules` içindeki native Rolldown eksikliği bu CI yolunu etkilemez.