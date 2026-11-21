package in.toucan.monitor.collector;

import io.restassured.RestAssured;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.specification.RequestSpecification;

/** Rest Assured implementation. Never logs request or response bodies (they carry tokens and PII). */
public final class RestAssuredTransport implements HttpTransport {

    private final RestAssuredConfig config;
    private final boolean relaxedHttps;

    public RestAssuredTransport(ToucanConfig cfg) {
        int ms = (int) cfg.timeout().toMillis();
        this.config = RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                .setParam("http.connection.timeout", ms)
                .setParam("http.socket.timeout", ms)
                .setParam("http.connection-manager.timeout", (long) ms));
        this.relaxedHttps = cfg.relaxedHttps();
    }

    private RequestSpecification spec(String bearer) {
        RequestSpecification s = RestAssured.given().config(config)
                .header("Accept", "application/json, */*");
        if (relaxedHttps) s = s.relaxedHTTPSValidation();
        if (bearer != null) s = s.header("Authorization", "Bearer " + bearer);
        return s;
    }

    @Override
    public Response post(String url, String jsonBody, String bearerToken) {
        io.restassured.response.Response r = spec(bearerToken)
                .contentType("application/json; charset=utf-8")
                .body(jsonBody)
                .post(url);
        return new Response(r.statusCode(), r.asByteArray(), r.contentType());
    }

    @Override
    public Response get(String url, String bearerToken) {
        io.restassured.response.Response r = spec(bearerToken).get(url);
        return new Response(r.statusCode(), r.asByteArray(), r.contentType());
    }
}
