package com.pannous.es.rollindex;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.isEmptyString;

import java.util.Map;

import org.elasticsearch.action.admin.indices.create.CreateIndexRequest;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.joda.time.format.DateTimeFormat;
import org.elasticsearch.common.joda.time.format.DateTimeFormatter;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.rest.RestController;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class RollActionTest extends AbstractNodesTests {

    private Client client;

    @BeforeClass public void createNodes() throws Exception {
        startNode("node1");
        client = getClient();
    }

    @AfterClass public void closeNodes() {
        client.close();
        closeAllNodes();
    }

    protected Client getClient() {
        return client("node1");
    }

    @BeforeMethod
    public void deleteAll() {
        client.admin().indices().delete(new DeleteIndexRequest("_all")).actionGet();
    }

    @Test public void rollingIndex() throws Exception {
        Settings emptySettings = ImmutableSettings.settingsBuilder().build();
        RollAction action = new RollAction(emptySettings, client, new RestController(emptySettings));
        // use millisecond in order to get different indices
        String pattern = "yyyy-MM-dd-HH-mm-ss-S";

        String rollIndexTag = action.getRoll("tweets");
        String searchIndex = action.getSearch("tweets");
        String feedIndex = action.getFeed("tweets");
        action.rollIndex("tweets", 4, 4, pattern);
        assertThat(action.getAliases(rollIndexTag).size(), equalTo(1));
        assertThat(action.getAliases(searchIndex).size(), equalTo(1));
        assertThat(action.getAliases(feedIndex).size(), equalTo(1));

        // TODO sleep is necessary to ensure index name change
        Thread.sleep(20);
        action.rollIndex("tweets", 4, 4, pattern);
        assertThat(action.getAliases(rollIndexTag).size(), equalTo(2));
        assertThat(action.getAliases(searchIndex).size(), equalTo(2));
        assertThat(action.getAliases(feedIndex).size(), equalTo(1));

        Thread.sleep(20);
        action.rollIndex("tweets", 4, 4, pattern);
        assertThat(action.getAliases(rollIndexTag).size(), equalTo(3));
        assertThat(action.getAliases(searchIndex).size(), equalTo(3));
        assertThat(action.getAliases(feedIndex).size(), equalTo(1));

        Thread.sleep(20);
        action.rollIndex("tweets", 4, 4, pattern);
        assertThat(action.getAliases(rollIndexTag).size(), equalTo(4));
        assertThat(action.getAliases(searchIndex).size(), equalTo(4));
        assertThat(action.getAliases(feedIndex).size(), equalTo(1));

        Thread.sleep(20);
        action.rollIndex("tweets", 4, 4, pattern);
        assertThat(action.getAliases(rollIndexTag).size(), equalTo(4));
        assertThat(action.getAliases(searchIndex).size(), equalTo(4));
        assertThat(action.getAliases(feedIndex).size(), equalTo(1));

        Thread.sleep(20);
        action.rollIndex("tweets", 4, 3, pattern);
        assertThat(action.getAliases(rollIndexTag).size(), equalTo(4));
        assertThat(action.getAliases(searchIndex).size(), equalTo(3));
        assertThat(action.getAliases(feedIndex).size(), equalTo(1));
    }

    @Test public void rollingIndex2() throws Exception {
        Settings emptySettings = ImmutableSettings.settingsBuilder().build();
        RollAction action = new RollAction(emptySettings, client, new RestController(emptySettings));
        String pattern = "yyyy-MM-dd-HH-mm-ss-S";

        Map<String, Object> result = action.rollIndex("tweets", 2, 1, pattern);
        String newIndex = result.get("created").toString();
        assertThat(((String) result.get("deleted")), isEmptyString());

        Thread.sleep(40);
        result = action.rollIndex("tweets", 2, 1, pattern);
        assertThat(((String) result.get("deleted")), isEmptyString());

        Thread.sleep(40);
        result = action.rollIndex("tweets", 2, 1, pattern);
        assertThat(((String) result.get("deleted")), isEmptyString());
        assertThat(((String) result.get("closed")), equalTo(newIndex));
    }

    @Test public void incompatibleDateFormatShouldComeLast() throws Exception {
        Settings emptySettings = ImmutableSettings.settingsBuilder().build();
        RollAction action = new RollAction(emptySettings, client, new RestController(emptySettings)) {
            @Override public DateTimeFormatter createFormatter() {
                // use millisecond change for test
                return DateTimeFormat.forPattern("yyyy-MM-dd-HH-mm-ss-S");
            }
        };
        client.admin().indices().create(new CreateIndexRequest("whateverindex-1")).actionGet();
        action.addAlias("whateverindex-1", action.getFeed("tweets"));
        action.addAlias("whateverindex-1", action.getSearch("tweets"));
        action.addAlias("whateverindex-1", action.getRoll("tweets"));

        Map<String, Object> res = action.rollIndex("tweets", 2, 1);

        assertThat(action.getAliases(action.getRoll("tweets")).size(), equalTo(2));
        assertThat(action.getAliases(action.getSearch("tweets")).size(), equalTo(1));
        assertThat(action.getAliases(action.getFeed("tweets")).size(), equalTo(1));
        assertThat(action.getAliases(action.getFeed("tweets")).toString(), containsString("tweets"));
    }
}
