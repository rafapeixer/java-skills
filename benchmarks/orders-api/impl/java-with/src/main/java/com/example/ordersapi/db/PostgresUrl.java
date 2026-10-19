package com.example.ordersapi.db;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Converts {@code postgres://user:pass@host:5432/db?sslmode=require} into a JDBC URL plus
 * credentials. A URL that is already {@code jdbc:postgresql:} is passed through.
 * Error messages never include the URL, since it carries the password.
 */
record PostgresUrl(String jdbcUrl, @Nullable String user, @Nullable String password) {

    @Override
    public String toString() {
        return "PostgresUrl[" + jdbcUrl + ", user=" + user + ", password=***]";
    }

    static PostgresUrl parse(String raw) {
        if (raw.startsWith("jdbc:postgresql:")) {
            return new PostgresUrl(raw, null, null);
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("DATABASE_URL is not a valid URL");
        }
        String scheme = uri.getScheme();
        if (!"postgres".equals(scheme) && !"postgresql".equals(scheme)) {
            throw new IllegalArgumentException("DATABASE_URL must start with postgres:// or postgresql://");
        }
        if (uri.getHost() == null) {
            throw new IllegalArgumentException("DATABASE_URL has no host");
        }
        String path = uri.getRawPath();
        if (path == null || path.length() <= 1) {
            throw new IllegalArgumentException("DATABASE_URL has no database name");
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String query = uri.getRawQuery();
        String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + path
                + (query == null ? "" : "?" + query);

        String user = null;
        String password = null;
        String userInfo = uri.getRawUserInfo();
        if (userInfo != null) {
            int colon = userInfo.indexOf(':');
            user = decode(colon < 0 ? userInfo : userInfo.substring(0, colon));
            password = colon < 0 ? null : decode(userInfo.substring(colon + 1));
        }
        return new PostgresUrl(jdbcUrl, user, password);
    }

    private static String decode(String s) {
        // URLDecoder turns '+' into a space; in a URL's userinfo '+' is literal.
        return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
