# Count (Backend)

Spring Boot backend for EazyCount.

The React frontend has been moved to a separate repository: **Count-frontend** (`../Count-frontend` on this machine).

## Run backend

```bash
mvn spring-boot:run
```

Server listens on port `8082` by default.

## Frontend development

Open `Count-frontend` in VS Code/Cursor and run `npm run dev`. Vite proxies `/auth` and `/api` to this backend.
