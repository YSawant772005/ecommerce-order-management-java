package com.ecommerce.ordermanagement.repository;

import com.ecommerce.ordermanagement.config.AppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.util.EntityUtils;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.RestClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Repository;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Elasticsearch access — index maintenance and document writes.
 *
 * <p>Elasticsearch holds a <b>read projection</b> of orders for the admin search
 * screen. It is never the source of truth: every document is produced by
 * {@code SyncService} from committed PostgreSQL rows, so the whole index can be
 * deleted and rebuilt from PostgreSQL at any moment.</p>
 */
@Repository
public class SearchRepository {

    /** A stale external version lost to a newer write — terminal, never retried. */
    public static class EsVersionConflict extends RuntimeException {
        public EsVersionConflict(String message) {
            super(message);
        }
    }

    /** Any other Elasticsearch transport/response failure — retryable. */
    public static class EsIndexException extends RuntimeException {
        public EsIndexException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestClient client;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public SearchRepository(RestClient client, AppProperties props, ObjectMapper mapper) {
        this.client = client;
        this.props = props;
        this.mapper = mapper;
    }

    private String index() {
        return props.getEsOrdersIndex();
    }

    /** The index settings + mappings, read from the checked-in JSON (one mapping). */
    private String loadIndexDefinition() {
        try (InputStream in = new ClassPathResource("search/orders_mapping.json").getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException exc) {
            throw new EsIndexException("orders_mapping.json missing from the classpath", exc);
        }
    }

    /**
     * Whether the index exists.
     *
     * <p>The low-level Elasticsearch {@code RestClient} does <b>not</b> throw for
     * a {@code HEAD} that answers 404 — it returns normally with that status.
     * Relying on a {@code ResponseException} therefore reported a missing index
     * as present, which silently skipped {@link #ensureOrdersIndex()} and let
     * Elasticsearch auto-create the index with dynamic mapping (so
     * {@code status} became {@code text} instead of {@code keyword}, and
     * {@code total_amount} became {@code text} instead of {@code scaled_float},
     * breaking every aggregation). The status code is checked explicitly.</p>
     */
    public boolean indexExists() {
        try {
            Response response = client.performRequest(new Request("HEAD", "/" + index()));
            int status = response.getStatusLine().getStatusCode();
            if (status == 200) {
                return true;
            }
            if (status == 404) {
                return false;
            }
            throw new EsIndexException("HEAD /" + index() + " returned " + status, null);
        } catch (ResponseException exc) {
            if (exc.getResponse().getStatusLine().getStatusCode() == 404) {
                return false;
            }
            throw new EsIndexException("HEAD /" + index() + " failed", exc);
        } catch (IOException exc) {
            throw new EsIndexException("HEAD /" + index() + " failed", exc);
        }
    }

    /** Create the {@code orders} index with its mapping if it does not already exist. */
    public void ensureOrdersIndex() {
        if (indexExists()) {
            return;
        }
        Request req = new Request("PUT", "/" + index());
        req.setJsonEntity(loadIndexDefinition());
        try {
            client.performRequest(req);
        } catch (IOException exc) {
            throw new EsIndexException("create index " + index() + " failed", exc);
        }
    }

    public void deleteIndex() {
        try {
            client.performRequest(new Request("DELETE", "/" + index()));
        } catch (ResponseException exc) {
            if (exc.getResponse().getStatusLine().getStatusCode() == 404) {
                return;
            }
            throw new EsIndexException("delete index " + index() + " failed", exc);
        } catch (IOException exc) {
            throw new EsIndexException("delete index " + index() + " failed", exc);
        }
    }

    /** The stored {@code version} of an indexed order, or {@code null} if absent. */
    public Integer getStoredVersion(long orderId) {
        Request req = new Request("GET", "/" + index() + "/_doc/" + orderId);
        try {
            Response response = client.performRequest(req);
            var node = mapper.readTree(EntityUtils.toString(response.getEntity()));
            var source = node.get("_source");
            if (source == null || source.get("version") == null) {
                return null;
            }
            return source.get("version").asInt();
        } catch (ResponseException exc) {
            if (exc.getResponse().getStatusLine().getStatusCode() == 404) {
                return null;
            }
            throw new EsIndexException("GET order " + orderId + " failed", exc);
        } catch (IOException exc) {
            throw new EsIndexException("GET order " + orderId + " failed", exc);
        }
    }

    /**
     * Index one order under an external version. A 409 is a stale write (a newer
     * version already won) and is surfaced as {@link EsVersionConflict}.
     */
    public void indexOrderDocument(long orderId, String documentJson, int version) {
        Request req = new Request("PUT",
                "/" + index() + "/_doc/" + orderId + "?version=" + version + "&version_type=external");
        req.setJsonEntity(documentJson);
        try {
            client.performRequest(req);
        } catch (ResponseException exc) {
            if (exc.getResponse().getStatusLine().getStatusCode() == 409) {
                throw new EsVersionConflict("stale version " + version + " lost to a newer index write");
            }
            throw new EsIndexException("index order " + orderId + " failed", exc);
        } catch (IOException exc) {
            throw new EsIndexException("index order " + orderId + " failed", exc);
        }
    }

    public void refresh() {
        try {
            client.performRequest(new Request("POST", "/" + index() + "/_refresh"));
        } catch (IOException exc) {
            throw new EsIndexException("refresh " + index() + " failed", exc);
        }
    }

    /** Number of documents in the index; {@code -1} when the index is absent. */
    public long count() {
        try {
            Response response = client.performRequest(new Request("GET", "/" + index() + "/_count"));
            return mapper.readTree(EntityUtils.toString(response.getEntity())).path("count").asLong();
        } catch (ResponseException exc) {
            if (exc.getResponse().getStatusLine().getStatusCode() == 404) {
                return -1L;
            }
            throw new EsIndexException("count " + index() + " failed", exc);
        } catch (IOException exc) {
            throw new EsIndexException("count " + index() + " failed", exc);
        }
    }

    /** Send a raw {@code _search} body and return the raw JSON response. */
    public String searchRaw(String requestBodyJson) {
        Request req = new Request("POST", "/" + index() + "/_search");
        req.setJsonEntity(requestBodyJson);
        try {
            Response response = client.performRequest(req);
            return EntityUtils.toString(response.getEntity());
        } catch (IOException exc) {
            throw new EsIndexException("search " + index() + " failed", exc);
        }
    }
}
