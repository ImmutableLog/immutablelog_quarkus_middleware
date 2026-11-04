# ImmutableLog Quarkus Filter

Filter JAX-RS para Quarkus que captura automaticamente as requisições HTTP que chegam a um recurso JAX-RS e recebem resposta do JAX-RS, exceto os caminhos que começam com `/health`, `/q/health` ou `/ping` (ver [Exclusão de paths](#exclusão-de-paths)), e envia eventos de auditoria imutáveis para o ImmutableLog. Exceção não tratada (500), rota inexistente e requisição abortada antes do filtro **não geram evento** — ver [Comportamento](#comportamento).

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

> Jackson está disponível via `quarkus-resteasy-reactive-jackson`. O `java.net.http.HttpClient` vem do JDK — sem dependências extras.

**Requisitos:** Quarkus 3 — o filtro importa `jakarta.*` (`jakarta.ws.rs`, `jakarta.annotation`), não `javax.*`, e o Quarkus 3 exige Java 17+.

**RESTEasy Reactive obrigatório.** Para ler a exceção da requisição, o filtro importa duas classes do RESTEasy Reactive (`org.jboss.resteasy.reactive.common.core.AbstractResteasyReactiveContext` e `org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext`), que vêm com `quarkus-resteasy-reactive` / `quarkus-rest`. Com o RESTEasy clássico (`quarkus-resteasy`) essas classes não existem e o arquivo não compila. A primeira não é API pública do RESTEasy Reactive: se uma versão futura do Quarkus mudar essa classe, o erro aparece na compilação. Para ler o IP da conexão, o filtro também injeta `io.vertx.ext.web.RoutingContext` (`@Inject`): o RESTEasy Reactive roda sobre o Vert.x e não há API servlet, então o endereço remoto vem do Vert.x. Sem requisição Vert.x ativa, esse IP simplesmente não é usado.

---

## Adicionar ao projeto

Copie `ImmutableLogFilter.java` para dentro do seu projeto, por exemplo:

```
src/main/java/com/example/filter/ImmutableLogFilter.java
```

O arquivo **não tem declaração `package`** (ele começa direto nos `import`). Ao copiar, acrescente na primeira linha o pacote da pasta onde ele ficou:

```java
package com.example.filter;
```

O Quarkus descobre a classe anotada com `@Provider`. Depois de copiar, confira que o filtro foi registrado: faça uma requisição a um endpoint da aplicação e verifique se o evento chegou ao ImmutableLog.

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
| `ContainerResponseFilter`       | Verifica `startedAt`, calcula latência, lê a exceção já tratada (se houver) do contexto do RESTEasy Reactive, monta e envia o evento. Só lê: não altera a resposta, o status nem os `ExceptionMapper` da aplicação |
| `@Priority(USER - 100)`         | Prioridade 4900. Filtros de request do JAX-RS rodam em ordem crescente de prioridade, então este roda **depois** de filtros `AUTHENTICATION` (1000) e `AUTHORIZATION` (2000); requisição abortada antes dele fica sem `startedAt` e não gera evento |
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

## Exclusão de paths

A lista é fixa no código e a comparação é **por prefixo** (`startsWith`), depois de garantir a barra inicial no caminho:

```java
private static final Set<String> SKIP_PATHS = Set.of("/health", "/q/health", "/ping");
```

Por ser prefixo, rotas como `/healthcare` ou `/pingback` também deixam de ser auditadas. Para mudar a lista, edite `SKIP_PATHS` no arquivo copiado.

A lista só vale para rotas JAX-RS da aplicação (um `@Path("health")` seu, por exemplo). O `/q/health` do `quarkus-smallrye-health` é uma rota do Vert.x, fora do JAX-RS: o filtro nunca roda nele, com ou sem a entrada na lista.

---

## Payload enviado

Exemplo de uma requisição que chegou com `X-Forwarded-For: 1.2.3.4`:

```json
{
  "payload": "{\"method\":\"POST\",\"path\":\"/payments\",\"status\":200,\"latency_ms\":22,\"client_ip\":\"1.2.3.4\",\"user_agent\":\"...\"}",
  "meta": {
    "type": "success",
    "event_name": "payment.created",
    "service": "my-service",
    "request_id": "uuid",
    "env": "production",
    "environment": "production",
    "client_ip": "1.2.3.4",
    "http_method": "POST",
    "http_status": "200",
    "http_route": "/payments",
    "log_level": "info"
  }
}
```

> `env` é mantido por compatibilidade; `environment` é o nome canônico do contrato
> (`CONTRACT.md` §4.1 do `immutablelog_siem`).
> **Sem `X-Forwarded-For` com IP válido, `client_ip` é o IP da conexão** (lido do `RoutingContext` do Vert.x). Se nem esse for um IP válido, `client_ip` é omitido do `meta` **e** do payload. Cópias antigas mandavam `"unknown"` no payload; a versão atual nunca manda.
> `path` no payload é o valor de `getUriInfo().getPath()` sem tratamento. Com Quarkus 3.15.1 e RESTEasy Reactive ele veio **com** barra inicial (`/payments`); outra implementação JAX-RS pode devolver sem a barra.
> **Quando o recurso lança uma exceção que vira resposta** — `WebApplicationException` (inclusive `NotFoundException` e afins) ou uma exceção tratada por `ExceptionMapper` da aplicação —, o evento leva `meta.error_type` (nome da classe), `meta.error_message` (até 500 caracteres; omitido se vazio) e, no payload, `"error": {"type": ..., "message": ..., "retryable": ...}` (`retryable` é `false` só para `IllegalArgumentException`). O status do evento é o da resposta enviada (ex.: `422` do seu mapper). Exceção **sem** mapper não gera evento nenhum — ver [Comportamento](#comportamento).
>
> **Cópias antigas deste arquivo continuam sem `error_type`, `error_message` e `error`** em todos os eventos, até serem substituídas pela versão atual. Quem copiou antes precisa recopiar (e reacrescentar o `package`).
> `http_route` aqui é o caminho concreto, não um template — o JAX-RS/Quarkus não expõe a rota casada no ponto onde o filtro roda. Caminhos com ID (`/orders/42`) aumentam a cardinalidade de `url.path` no Elasticsearch.

---

## Comportamento

| Situação                | Resultado                                               |
|-------------------------|---------------------------------------------------------|
| Path em `SKIP_PATHS`    | Evento ignorado, requisição prossegue normalmente       |
| Status 2xx              | `type: success`                                         |
| Status 3xx              | `type: info`                                            |
| Status 4xx / 5xx        | `type: error`                                           |
| Falha no `sendAsync`    | Exceção silenciada — nunca quebra a aplicação           |
| `WebApplicationException` ou exceção com `ExceptionMapper` da aplicação | Evento com o status da resposta e `error_type`/`error_message`; resposta ao cliente inalterada |
| Exceção sem `ExceptionMapper` (ex.: `RuntimeException`) | **Nenhum evento.** O Quarkus responde 500 fora do JAX-RS e os `ContainerResponseFilter` não rodam |
| Rota inexistente (404 do roteamento) | **Nenhum evento.** O filtro não é `@PreMatching` e só roda quando um recurso casa |
| Requisição abortada (`abortWith`) por filtro de prioridade menor que 4900 (ex.: autenticação) | **Nenhum evento** (sem `startedAt`) |
| `/q/health` (`quarkus-smallrye-health`) | Nenhum evento — não passa pelo JAX-RS |

---

## Limitações conhecidas

- **`client_ip` vem primeiro de um header que o chamador controla.** O filtro usa o primeiro item de `X-Forwarded-For` se ele for um IP válido; senão, o IP da conexão (`routingContext.request().remoteAddress().host()`), também validado; senão, nenhum. `X-Real-IP` não é lido. Quem chama a aplicação diretamente (sem um proxy que sobrescreva o header) pode forjar o IP registrado, desde que mande um IP válido. Antes de validar, o valor passa por `trim` e perde a porta (`10.0.0.1:8080`, `[2001:db8::1]:8080`) e os colchetes. A validação não faz consulta DNS: IPv4 é conferido por expressão regular, e só chega ao `InetAddress.getByName` um valor com `:` feito apenas de dígitos hexadecimais, `:` e `.`, que ele interpreta como literal IPv6. Sem IP válido, `client_ip` não vai nem no `meta` nem no payload.
- **`Idempotency-Key` é um UUID aleatório gerado pelo filtro a cada requisição**, o mesmo valor de `meta.request_id`. Não vem de nenhum header da requisição de entrada.
- **Entrega sem garantia.** Um único `sendAsync()` por evento: sem timeout configurado, sem retry, sem fila. A resposta do ImmutableLog é descartada e qualquer erro (rede, status 4xx/5xx, falha ao montar o evento) é ignorado em silêncio — o evento simplesmente se perde.
- **Sem suporte a trilha (`immutable_trail`):** o filtro não envia nenhum campo de trilha.
- **Chaves reservadas enviadas em `meta`:** `service`, `environment` (e `env`), `http_method`, `http_status`, `http_route` (caminho concreto) e `log_level` sempre; `client_ip` só quando há IP válido (de `X-Forwarded-For` ou da conexão); `error_type` e `error_message` só quando o recurso lançou uma exceção que virou resposta (ver [Payload enviado](#payload-enviado)). Todos são strings.
- **Erro 500 por exceção não tratada não é auditado.** Uma exceção sem `ExceptionMapper` sai do JAX-RS e o Quarkus responde 500 pelo próprio tratador de erro; nenhum `ContainerResponseFilter` roda e nenhum evento é enviado. Isso não se resolve dentro deste filtro sem mudar a resposta da aplicação. Se precisar desses eventos, mapeie a exceção num `ExceptionMapper` seu (aí o evento sai, com `error_type`).
- **`status`** no payload é o código HTTP numérico da resposta; `meta.http_status` é o mesmo valor como string.
- **Sem body:** o filtro não envia o body da requisição nem hash dele. Também não envia a query string (o caminho vem de `getUriInfo().getPath()`).
- **O caminho concreto vai no evento.** `path` no payload, `http_route` e o `event_name` padrão levam os IDs que estiverem na URL. O evento selado não pode ser apagado depois.
