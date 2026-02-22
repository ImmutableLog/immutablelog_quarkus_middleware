# ImmutableLog Quarkus Filter

Filter JAX-RS para Quarkus que captura automaticamente todas as requisições HTTP e envia eventos de auditoria imutáveis para o ImmutableLog.

Usa `ContainerRequestFilter` + `ContainerResponseFilter`. O `@Provider` é descoberto automaticamente — zero configuração extra. Compatível com compilação nativa GraalVM.

---

## Instalação

```xml
<!-- pom.xml -->
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-resteasy-reactive</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-rest-client-reactive-jackson</artifactId>
</dependency>
```

> Jackson está disponível via `quarkus-resteasy-reactive-jackson`. O `java.net.http.HttpClient` está disponível no Java 11+ — sem dependências extras.

---

## Adicionar ao projeto

Copie `ImmutableLogFilter.java` para dentro do seu projeto:

```
src/main/java/com/example/filter/ImmutableLogFilter.java
```

O Quarkus descobre automaticamente a classe anotada com `@Provider`.

---

## Variáveis de ambiente

| Variável             | Obrigatório | Default        | Descrição                              |
|----------------------|-------------|----------------|----------------------------------------|
| `IMTBL_API_KEY`      | Sim         | —              | Chave de API do ImmutableLog           |
| `IMTBL_SERVICE_NAME` | Não         | `my-service`   | Nome do serviço exibido nos eventos    |
| `IMTBL_ENV`          | Não         | `production`   | Ambiente: `production`, `staging`, `development` |

---

## Configuração

```properties
# src/main/resources/application.properties
immutablelog.api-key=${IMTBL_API_KEY}
immutablelog.service-name=${IMTBL_SERVICE_NAME:my-service}
immutablelog.env=${IMTBL_ENV:production}
immutablelog.url=https://api.immutablelog.com
```

---

## Como funciona

| Etapa                           | Descrição                                                                 |
|---------------------------------|---------------------------------------------------------------------------|
| `ContainerRequestFilter`        | Registra `startedAt` e `requestId` como propriedades do request context   |
| `ContainerResponseFilter`       | Verifica `startedAt`, calcula latência, monta e envia o evento            |
| `@Priority(USER - 100)`         | Garante execução antes dos filtros de auth, capturando tentativas negadas |
| GraalVM Native                  | Compatível — Quarkus gera hints automaticamente para `@Provider`          |

---

## Nome de evento customizado

```java
@Path("/payments")
public class PaymentResource {

    @Context
    ContainerRequestContext requestContext;

    @POST
    public Response create(PaymentDto dto) {
        requestContext.setProperty("imtbl.eventName", "payment.created");
        // ... lógica de negócio ...
        return Response.ok(result).build();
    }
}
```

---

## Payload enviado

```json
{
  "payload": "{\"method\":\"POST\",\"path\":\"payments\",\"status\":200,\"latency_ms\":22,\"client_ip\":\"1.2.3.4\",\"user_agent\":\"...\"}",
  "meta": {
    "type": "success",
    "event_name": "payment.created",
    "service": "my-service",
    "request_id": "uuid",
    "env": "production"
  }
}
```

---

## Comportamento

| Situação                | Resultado                                               |
|-------------------------|---------------------------------------------------------|
| Path em `SKIP_PATHS`    | Evento ignorado, requisição prossegue normalmente       |
| Status 2xx              | `type: success`                                         |
| Status 3xx              | `type: info`                                            |
| Status 4xx / 5xx        | `type: error`                                           |
| Falha no `sendAsync`    | Exceção silenciada — nunca quebra a aplicação           |
