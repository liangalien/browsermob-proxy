package net.lightbody.bmp.filters.util;

import net.lightbody.bmp.core.har.*;

import java.io.IOException;
import java.util.List;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.RequestOptions;

import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.CreateIndexRequest;

import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.client.indices.GetIndexTemplatesRequest;

import com.alibaba.fastjson.JSONObject;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ElasticSearchUtil {
    public String indexName;
    public RestHighLevelClient client;
    private static final Logger LOG = LoggerFactory.getLogger(ElasticSearchUtil.class);

    public ElasticSearchUtil(Har har) {
        HarElasticSearch harElasticSearch = har.getElasticSearch();
        HttpHost host = new HttpHost(harElasticSearch.getHost(), harElasticSearch.getPort(), HttpHost.DEFAULT_SCHEME_NAME);
        RestClientBuilder builder = RestClient.builder(host);
        if (harElasticSearch.getUser() != null && harElasticSearch.getPassword() != null) {
            CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY, new UsernamePasswordCredentials(harElasticSearch.getUser(), harElasticSearch.getPassword()));
            builder.setHttpClientConfigCallback(f -> f.setDefaultCredentialsProvider(credentialsProvider));
        }

        this.client = new RestHighLevelClient(builder);

        List<HarPage> pages = har.getLog().getPages();
        this.indexName = "bm-proxy-" + (pages.size() > 0 ? pages.get(0).getId().trim() : "public");

        LOG.info("ElasticSearch connected: http://{}:{}/{}", harElasticSearch.getHost(), harElasticSearch.getPort(), indexName);
    }

    public boolean hasIndex() {
        GetIndexRequest getIndexRequest = new GetIndexRequest(indexName);
        try {
            return client.indices().exists(getIndexRequest, RequestOptions.DEFAULT);
        } catch (IOException e) {
            LOG.error("ElasticSearch query failed", e);
            return false;
        }
    }

    public boolean createIndex() {
        boolean result = true;
        GetIndexTemplatesRequest indexRequestExists = new GetIndexTemplatesRequest(indexName);
        if (!hasIndex()) {
            CreateIndexRequest createIndexRequest = new CreateIndexRequest(indexName);

            try {
                CreateIndexResponse createIndexResponse = client.indices().create(createIndexRequest, RequestOptions.DEFAULT);
                //拿到响应状态
                result = createIndexResponse.isAcknowledged();
                LOG.info("ElasticSearch created index");
            } catch (IOException e) {
                result = false;
                LOG.error("ElasticSearch create index failed", e);
            }
        }
        return result;
    }

    public boolean deleteIndex() {
        boolean result = true;
        if (hasIndex()) {
            DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(indexName);
            try {
                AcknowledgedResponse deleteIndexResponse = client.indices().delete(deleteIndexRequest, RequestOptions.DEFAULT);
                result = deleteIndexResponse.isAcknowledged();
                LOG.info("ElasticSearch deleted index");
            } catch (IOException e) {
                result = false;
                LOG.error("ElasticSearch delete index failed", e);
            }
        }

        return result;
    }

    public String add(HarRequest request, HarResponse response) {
        if (!createIndex()) return null;

        IndexRequest addRequest = new IndexRequest(indexName);
        addRequest.source(addHandle(request, response), XContentType.JSON);

        IndexResponse addResponse = null;
        try {
            addResponse = client.index(addRequest, RequestOptions.DEFAULT);
            String docId = addResponse.getId();
            LOG.info("ElasticSearch inserted document: {}", docId);
            return docId;

        } catch (IOException e) {
            LOG.error("ElasticSearch insert document failed", e);
        }

        return null;
    }

    public JSONObject addHandle(HarRequest harRequest, HarResponse harResponse) {
        JSONObject content = new JSONObject();
        JSONObject request = new JSONObject();
        JSONObject response = new JSONObject();

        JSONObject requestHeaders = new JSONObject();
        JSONObject requestCookies = new JSONObject();
        JSONObject requestParams = new JSONObject();

        JSONObject responseHeaders = new JSONObject();
        JSONObject responseCookies = new JSONObject();

        for (HarNameValuePair headers : harRequest.getHeaders()) {
            requestHeaders.put(headers.getName(), headers.getValue());
        }

        for (HarNameValuePair params : harRequest.getQueryString()) {
            requestParams.put(params.getName(), params.getValue());
        }

        for (HarCookie cookies : harRequest.getCookies()) {
            requestCookies.put(cookies.getName(), cookies.getValue());
        }

        for (HarNameValuePair headers : harResponse.getHeaders()) {
            responseHeaders.put(headers.getName(), headers.getValue());
        }

        for (HarCookie cookies : harResponse.getCookies()) {
            responseCookies.put(cookies.getName(), cookies.getValue());
        }

        request.put("headers", requestHeaders);
        //request.put("cookies", requestCookies);
        request.put("params", requestParams);
        if (harRequest.getPostData() != null) request.put("data", harRequest.getPostData().getText());

        response.put("headers", responseHeaders);
        //response.put("cookies", responseCookies);
        response.put("status", harResponse.getStatus());

        if (harResponse.getContent() != null) {
            response.put("content", harResponse.getContent().getText());
        }

        content.put("url", harRequest.getUrl());
        content.put("method", harRequest.getMethod());
        content.put("request", request);
        content.put("response", response);

        return content;
    }

    public void close() {
        try {
            client.close();
        } catch (IOException e) {
            LOG.error("ElasticSearch close connect failed", e);
        }
    }

}


