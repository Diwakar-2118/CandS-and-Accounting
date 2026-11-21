package in.toucan.monitor.collector;

/** Minimal HTTP abstraction so the client can be unit-tested without the network. */
public interface HttpTransport {

    Response post(String url, String jsonBody, String bearerToken);

    Response get(String url, String bearerToken);

    record Response(int status, byte[] body, String contentType) {
        public String bodyAsString() {
            return body == null ? "" : new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
