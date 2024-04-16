package com.devcharly.onedev.plugin.imports.redmine;

import com.fasterxml.jackson.databind.JsonNode;
import io.onedev.commons.utils.TaskLogger;
import io.onedev.server.util.JerseyUtils;
import org.apache.http.client.utils.URIBuilder;

import javax.ws.rs.client.Client;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;

public class RedmineClient {

    private final Client rmClient;
    private final TaskLogger odLogger;

    public RedmineClient(Client client, TaskLogger logger) {
        this.rmClient = client;
        this.odLogger = logger;
    }

    List<JsonNode> list(String apiEndpoint, String dataNodeName) {
        List<JsonNode> result = new ArrayList<>();
        list(apiEndpoint, dataNodeName, new JerseyUtils.PageDataConsumer() {

            @Override
            public void consume(List<JsonNode> pageData) {
                result.addAll(pageData);
            }

        });
        return result;
    }

    void list(String apiEndpoint, String dataNodeName, JerseyUtils.PageDataConsumer pageDataConsumer) {
        URI uri;
        try {
            uri = new URIBuilder(apiEndpoint).build();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

        int offset = 0;
        while (true) {
            try {
                URIBuilder builder = new URIBuilder(uri);
                if (offset > 0)
                    builder.addParameter("offset", String.valueOf(offset));
                builder.addParameter("limit", String.valueOf(ImportUtils.PER_PAGE));
                List<JsonNode> pageData = new ArrayList<>();
                JsonNode resultNode = JerseyUtils.get(this.rmClient, builder.build().toString(), this.odLogger);
                JsonNode dataNode = resultNode.get(dataNodeName);
                for (JsonNode each: dataNode)
                    pageData.add(each);
                pageDataConsumer.consume(pageData);
                JsonNode totalCountNode = resultNode.get("total_count");
                if (totalCountNode == null)
                    break;
                int totalCount = totalCountNode.asInt();
                if (offset + pageData.size() >= totalCount)
                    break;
                offset += pageData.size();
            } catch (URISyntaxException|InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

}
