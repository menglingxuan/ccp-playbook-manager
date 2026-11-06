package ac.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal HTTP client for end-to-end tests against a random port. */
public final class ApiClient {

    private final String baseUrl;
    private final HttpClient client;
    private final JsonMapper json = JsonMapper.builder().build();

    public ApiClient(int port) {
        this.baseUrl = "http://127.0.0.1:" + port;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * Simple response wrapper.
     *
     * @param status HTTP status
     * @param body   raw body
     */
    public record Response(int status, String body) {

        public JsonNode json() {
            return JsonMapper.builder().build().readTree(body);
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public Response get(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).GET());
    }

    public Response post(String path, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody,
                        StandardCharsets.UTF_8));
        return send(builder);
    }

    public Response patch(String path, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(jsonBody == null ? "{}" : jsonBody,
                        StandardCharsets.UTF_8));
        return send(builder);
    }

    public Response delete(String path) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl + path)).DELETE());
    }

    public Response upload(String path, String fileName, byte[] content) {
        String boundary = "----otc" + UUID.randomUUID().toString().replace("-", "");
        byte[] head = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] tail = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return send(builder);
    }

    public byte[] getBytes(String path) {
        try {
            HttpResponse<byte[]> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return response.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public String toJson(Object value) {
        return json.writeValueAsString(value);
    }

    private Response send(HttpRequest.Builder builder) {
        HttpRequest request = builder.timeout(Duration.ofSeconds(60)).build();
        try {
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
