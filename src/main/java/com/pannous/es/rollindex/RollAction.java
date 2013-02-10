package com.pannous.es.rollindex;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TreeMap;
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest;
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesResponse;
import org.elasticsearch.action.admin.indices.close.CloseIndexRequest;
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.AliasMetaData;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.joda.time.format.DateTimeFormat;
import org.elasticsearch.common.joda.time.format.DateTimeFormatter;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.rest.BaseRestHandler;
import org.elasticsearch.rest.RestChannel;
import org.elasticsearch.rest.RestController;
import org.elasticsearch.rest.RestRequest;
import org.elasticsearch.rest.XContentRestResponse;
import org.elasticsearch.rest.XContentThrowableRestResponse;
import static org.elasticsearch.rest.RestRequest.Method.*;
import static org.elasticsearch.rest.RestStatus.*;
import static org.elasticsearch.rest.action.support.RestXContentBuilder.*;

/**
 * @see issue 1500 https://github.com/elasticsearch/elasticsearch/issues/1500
 *
 * Only indices with the rolling alias are involved into rolling.
 * @author Peter Karich
 */
public class RollAction extends BaseRestHandler {

    private String feedEnd = "feed";
    private String searchEnd = "search";
    // helper alias to fetch all indices which are available to roll
    private String rollEnd = "roll";

    @Inject public RollAction(Settings settings, Client client, RestController controller) {
        super(settings, client);

        // Define REST endpoints to do a roll further
        controller.registerHandler(PUT, "/_rollindex", this);
        controller.registerHandler(POST, "/_rollindex", this);
    }

    @Override public void handleRequest(RestRequest request, RestChannel channel) {
        logger.info("RollAction.handleRequest [{}]", request.toString());
        try {
            XContentBuilder builder = restContentBuilder(request);
            String indexPrefix = request.param("indexPrefix", "");
            if (indexPrefix.isEmpty()) {
                builder.startObject();
                builder.field("error", "indexPrefix missing");
                builder.endObject();
                channel.sendResponse(new XContentRestResponse(request, BAD_REQUEST, builder));
                return;
            }
            int searchIndices = request.paramAsInt("searchIndices", 1);
            int rollIndices = request.paramAsInt("rollIndices", 1);
            boolean deleteAfterRoll = request.paramAsBoolean("deleteAfterRoll", false);
            boolean closeAfterRoll = request.paramAsBoolean("closeAfterRoll", true);
            if (deleteAfterRoll && closeAfterRoll) {
                if (request.hasParam("closeAfterRoll"))
                    throw new IllegalArgumentException("Cannot delete and close an index at the same time");
                else
                    // if no param was specified use false as default:
                    closeAfterRoll = false;
            }

            int newIndexShards = request.paramAsInt("newIndexShards", 2);
            int newIndexReplicas = request.paramAsInt("newIndexReplicas", 1);
            String newIndexRefresh = request.param("newIndexRefresh", "10s");
            String indexTimestampPattern = request.param("indexTimestampPattern");

            CreateIndexRequest req;
            if (request.hasContent())
                req = new CreateIndexRequest("").source(request.contentAsString());
            else
                req = new CreateIndexRequest("").settings(toSettings(createIndexSettings(
                        newIndexShards, newIndexReplicas, newIndexRefresh).string()));

            Map<String, Object> map = rollIndex(indexPrefix, rollIndices, searchIndices,
                    deleteAfterRoll, closeAfterRoll, indexTimestampPattern, req);

            builder.startObject();
            for (Entry<String, Object> e : map.entrySet()) {
                builder.field(e.getKey(), e.getValue());
            }
            builder.endObject();
            channel.sendResponse(new XContentRestResponse(request, OK, builder));
        } catch (IOException ex) {
            try {
                channel.sendResponse(new XContentThrowableRestResponse(request, ex));
            } catch (Exception ex2) {
                logger.error("problem while rolling index", ex2);
            }
        }
    }
    
    public DateTimeFormatter createFormatter() {
        return createFormatter(null);
    }

    public DateTimeFormatter createFormatter(String pattern) {
        return DateTimeFormat.forPattern(pattern == null ? "yyyy-MM-dd-HH-mm" : pattern);
    }
    
    public Map<String, Object> rollIndex(String indexPrefix, int maxRollIndices, int maxSearchIndices) {
        return rollIndex(indexPrefix, maxRollIndices, maxSearchIndices, null);
    }

    public Map<String, Object> rollIndex(String indexPrefix, int maxRollIndices, int maxSearchIndices, String indexTimestampPattern) {
        try {
            return rollIndex(indexPrefix, maxRollIndices, maxSearchIndices, false, true, indexTimestampPattern,
                    new CreateIndexRequest("").settings(toSettings(createIndexSettings(2, 1, "10s").string())));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    Settings toSettings(String str) {
        return ImmutableSettings.settingsBuilder().loadFromSource(str).build();
    }

    // TODO make client calls async, see RestCreateIndexAction
    public Map<String, Object> rollIndex(String indexPrefix, int maxRollIndices, int maxSearchIndices,
            boolean deleteAfterRoll, boolean closeAfterRoll, String indexTimestampPattern, CreateIndexRequest request) {
        String rollAlias = getRoll(indexPrefix);
        DateTimeFormatter formatter = createFormatter(indexTimestampPattern);
        if (maxRollIndices < 1 || maxSearchIndices < 1)
            throw new RuntimeException("remaining indices, search indices and feeding indices must be at least 1");
        if (maxSearchIndices > maxRollIndices)
            throw new RuntimeException("rollIndices must be higher or equal to searchIndices");

        // get old aliases
        Map<String, AliasMetaData> allRollingAliases = getAliases(rollAlias);

        // always create new index and append aliases
        String searchAlias = getSearch(indexPrefix);
        String feedAlias = getFeed(indexPrefix);
        String newIndexName = indexPrefix + "_" + formatter.print(System.currentTimeMillis());

        client.admin().indices().create(request.index(newIndexName)).actionGet();
        addAlias(newIndexName, searchAlias);
        addAlias(newIndexName, rollAlias);

        String deletedIndices = "";
        String removedAlias = "";
        String closedIndices = "";
        String oldFeedIndexName = null;
        if (allRollingAliases.isEmpty()) {
            // do nothing for now
        } else {
            // latest indices comes first
            TreeMap<Long, String> sortedIndices = new TreeMap<Long, String>(reverseSorter);
            // Map<String, String> indexToConcrete = new HashMap<String, String>();
            String[] concreteIndices = getConcreteIndices(allRollingAliases.keySet());
            Arrays.sort(concreteIndices);
            logger.info("aliases:{}, indices:{}", allRollingAliases, Arrays.toString(concreteIndices));
            // if we cannot parse the time from the index name we just treat them as old indices of time == 0
            long timeFake = 0;
            for (String index : concreteIndices) {
                long timeLong = timeFake++;
                int pos = index.indexOf("_");
                if (pos >= 0) {
                    String indexDateStr = index.substring(pos + 1);
                    try {
                        timeLong = formatter.parseMillis(indexDateStr);
                    } catch (Exception ex) {
                        logger.warn("index " + index + " is not in the format " + formatter + " error:" + ex.getMessage());
                    }
                } else
                    logger.warn("index " + index + " is not in the format " + formatter);

                String old = sortedIndices.put(timeLong, index);
                if (old != null)
                    throw new IllegalStateException("Indices with the identical date are not supported! " + old + " vs. " + index);
            }
            int counter = 1;
            Iterator<String> indexIter = sortedIndices.values().iterator();
            while (indexIter.hasNext()) {
                String currentIndexName = indexIter.next();
                if (counter >= maxRollIndices) {
                    if (deleteAfterRoll) {
                        deleteIndex(currentIndexName);
                        deletedIndices += currentIndexName + " ";
                    } else {
                        removeAlias(currentIndexName, rollAlias);
                        removeAlias(currentIndexName, searchAlias);

                        if (closeAfterRoll) {
                            closeIndex(currentIndexName);
                            closedIndices += currentIndexName + " ";
                        } else {
                            addAlias(currentIndexName, indexPrefix + "_closed");
                        }
                        removedAlias += currentIndexName + " ";
                        removedAlias += currentIndexName + " ";
                    }
                    // close/delete all the older indices
                    continue;
                }

                if (counter == 1)
                    oldFeedIndexName = currentIndexName;

                if (counter >= maxSearchIndices) {
                    removeAlias(currentIndexName, searchAlias);
                    removedAlias += currentIndexName + " ";
                }

                counter++;
            }
        }
        if (oldFeedIndexName != null)
            moveAlias(oldFeedIndexName, newIndexName, feedAlias);
        else
            addAlias(newIndexName, feedAlias);

        Map<String, Object> map = new HashMap<String, Object>();
        map.put("created", newIndexName);
        map.put("deleted", deletedIndices.trim());
        map.put("closed", closedIndices.trim());
        map.put("removedAlias", removedAlias.trim());
        return map;
    }

    XContentBuilder createIndexSettings(int shards, int replicas, String refresh) {
        try {
            XContentBuilder createIndexSettings = JsonXContent.contentBuilder().startObject().
                    field("index.number_of_shards", shards).
                    field("index.number_of_replicas", replicas).
                    field("index.refresh_interval", refresh).endObject();
            return createIndexSettings;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    public void deleteIndex(String indexName) {
        client.admin().indices().delete(new DeleteIndexRequest(indexName)).actionGet();
    }

    public void closeIndex(String indexName) {
        client.admin().indices().close(new CloseIndexRequest(indexName)).actionGet();
    }

    public void addAlias(String indexName, String alias) {
        client.admin().indices().aliases(new IndicesAliasesRequest().addAlias(indexName, alias)).actionGet();
    }

    public void removeAlias(String indexName, String alias) {
        client.admin().indices().aliases(new IndicesAliasesRequest().removeAlias(indexName, alias)).actionGet();
    }

    public void moveAlias(String oldIndexName, String newIndexName, String alias) {
        IndicesAliasesResponse r = client.admin().indices().aliases(new IndicesAliasesRequest().addAlias(newIndexName, alias).
                removeAlias(oldIndexName, alias)).actionGet();
        logger.info("({}) moved {} from {} to {} ", r.acknowledged(), alias, oldIndexName, newIndexName);
    }

    public Map<String, AliasMetaData> getAliases(String alias) {
        Map<String, AliasMetaData> md = client.admin().cluster().state(new ClusterStateRequest()).
                actionGet().getState().getMetaData().aliases().get(alias);
        if (md == null)
            return Collections.emptyMap();

        return md;
    }
    private static Comparator<Long> reverseSorter = new Comparator<Long>() {
        @Override
        public int compare(Long o1, Long o2) {
            return -o1.compareTo(o2);
        }
    };

    public String[] getConcreteIndices(Set<String> set) {
        return client.admin().cluster().state(new ClusterStateRequest()).actionGet().getState().
                getMetaData().concreteIndices(set.toArray(new String[set.size()]));
    }

    String getRoll(String indexName) {
        return indexName + "_" + rollEnd;
    }

    String getFeed(String indexName) {
        if (feedEnd.isEmpty())
            return indexName;
        return indexName + "_" + feedEnd;
    }

    String getSearch(String indexName) {
        if (searchEnd.isEmpty())
            return indexName;
        return indexName + "_" + searchEnd;
    }
}
