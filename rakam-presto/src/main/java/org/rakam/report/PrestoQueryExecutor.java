package org.rakam.report;

import com.facebook.presto.jdbc.internal.airlift.http.client.HttpClientConfig;
import com.facebook.presto.jdbc.internal.airlift.http.client.HttpRequestFilter;
import com.facebook.presto.jdbc.internal.airlift.http.client.Request;
import com.facebook.presto.jdbc.internal.airlift.http.client.jetty.JettyHttpClient;
import com.facebook.presto.jdbc.internal.airlift.http.client.jetty.JettyIoPool;
import com.facebook.presto.jdbc.internal.airlift.http.client.jetty.JettyIoPoolConfig;
import com.facebook.presto.jdbc.internal.airlift.units.Duration;
import com.facebook.presto.jdbc.internal.client.ClientSession;
import com.facebook.presto.jdbc.internal.client.QueryResults;
import com.facebook.presto.jdbc.internal.client.StatementClient;
import com.facebook.presto.jdbc.internal.guava.collect.ImmutableSet;
import com.facebook.presto.jdbc.internal.guava.net.HostAndPort;
import com.facebook.presto.jdbc.internal.guava.net.HttpHeaders;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.Statement;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Singleton;
import org.rakam.util.QueryFormatter;

import javax.inject.Inject;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.facebook.presto.jdbc.internal.airlift.http.client.HttpUriBuilder.uriBuilder;
import static com.facebook.presto.jdbc.internal.airlift.http.client.Request.Builder.fromRequest;
import static com.facebook.presto.jdbc.internal.airlift.json.JsonCodec.jsonCodec;
import static com.facebook.presto.jdbc.internal.guava.base.Preconditions.checkNotNull;
import static java.lang.String.format;

/**
 * Created by buremba <Burak Emre Kabakcı> on 09/03/15 16:10.
 */
@Singleton
public class PrestoQueryExecutor implements QueryExecutor {
    public final static String MATERIALIZED_VIEW_PREFIX = "_materialized_";

    private final SqlParser parser = new SqlParser();
    private final PrestoConfig prestoConfig;
    private final JettyHttpClient httpClient = new JettyHttpClient(
            new HttpClientConfig()
                    .setConnectTimeout(new Duration(10, TimeUnit.SECONDS))
                    .setSocksProxy(getSystemSocksProxy()),
            new JettyIoPool("presto-jdbc", new JettyIoPoolConfig()),
            ImmutableSet.of(new UserAgentRequestFilter("rakam")));

    private final PrestoConfig prestoAddress;

    @Inject
    public PrestoQueryExecutor(PrestoConfig prestoConfig1, PrestoConfig prestoConfig) {
        this.prestoConfig = prestoConfig1;
        this.prestoAddress = prestoConfig;
    }

    public PrestoQueryExecution executeRawQuery(String query) {
        return new PrestoQueryExecution(startQuery(prestoAddress, query));
    }

    @Override
    public QueryExecution executeQuery(String project, String sqlQuery, int limit) {
        return executeRawQuery(buildQuery(project, sqlQuery, limit));
    }

    @Override
    public PrestoQueryExecution executeQuery(String project, String query) {
        return executeRawQuery(buildQuery(project, query, null));
    }

    private Function<QualifiedName, String> tableNameMapper(String project) {
        return  node -> {
            if(node.getPrefix().isPresent()) {
                switch (node.getPrefix().get().toString()) {
                    case "continuous":
                        return "stream." + project + "." + node.getSuffix();
                    case "materialized":
                        return project + "."+ MATERIALIZED_VIEW_PREFIX + node.getSuffix();
                    default:
                        throw new IllegalArgumentException("Schema does not exist: "+node.getPrefix().get().toString());
                }
            }

            QualifiedName prefix = node.getPrefix().orElse(new QualifiedName(prestoConfig.getColdStorageConnector()));
            return prefix.getSuffix() + "." + project + "." + node.getSuffix();
        };
    }

    private String buildQuery(String project, String query, Integer maxLimit) {
        StringBuilder builder = new StringBuilder();
        Query statement;
        synchronized (parser) {
            statement = (Query) parser.createStatement(query);
        }
        new QueryFormatter(builder, tableNameMapper(project)).process(statement, 0);

        if(maxLimit != null) {
            if (statement.getLimit().isPresent() && Long.parseLong(statement.getLimit().get()) > maxLimit) {
                throw new IllegalArgumentException(format("The maximum value of LIMIT statement is %s", statement.getLimit().get()));
            } else {
                builder.append(" LIMIT ").append(maxLimit);
            }
        }

        return builder.toString();
    }

    private String buildStatement(String project, String query) {
        StringBuilder builder = new StringBuilder();
        Statement statement;
        synchronized (parser) {
            statement = parser.createStatement(query);
        }
        new QueryFormatter(builder, tableNameMapper(project)).process(statement, 0);

        return builder.toString();
    }

    @Override
    public QueryExecution executeStatement(String project, String sqlQuery) {
        return executeRawQuery(buildStatement(project, sqlQuery));
    }

    private StatementClient startQuery(PrestoConfig config, String query) {

        HostAndPort hostAndPort = HostAndPort.fromString(config.getAddress());
        URI uri = uriBuilder()
                .scheme("http")
                .host(hostAndPort.getHostText())
                .port(hostAndPort.getPort())
                .build();

        ClientSession session = new ClientSession(
                uri,
                "rakam",
                "api-server",
                config.getColdStorageConnector(),
                "default",
                TimeZone.getDefault().getID(),
                Locale.ENGLISH,
                ImmutableMap.of(),
                false);

        return new StatementClient(httpClient, jsonCodec(QueryResults.class), session, query);
    }

    private static HostAndPort getSystemSocksProxy()
    {
        URI uri = URI.create("socket://0.0.0.0:80");
        for (Proxy proxy : ProxySelector.getDefault().select(uri)) {
            if (proxy.type() == Proxy.Type.SOCKS) {
                if (proxy.address() instanceof InetSocketAddress) {
                    InetSocketAddress address = (InetSocketAddress) proxy.address();
                    return HostAndPort.fromParts(address.getHostString(), address.getPort());
                }
            }
        }
        return null;
    }

    static class UserAgentRequestFilter
            implements HttpRequestFilter
    {
        private final String userAgent;

        public UserAgentRequestFilter(String userAgent)
        {
            this.userAgent = checkNotNull(userAgent, "userAgent is null");
        }

        @Override
        public Request filterRequest(Request request)
        {
            return fromRequest(request)
                    .addHeader(HttpHeaders.USER_AGENT, userAgent)
                    .build();
        }
    }



}