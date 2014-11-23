/*
 * Copyright 2014 Cisco Systems, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.cisco.oss.foundation.http.apache;

import com.cisco.oss.foundation.http.AbstractHttpClient;
import com.cisco.oss.foundation.http.ClientException;
import com.cisco.oss.foundation.http.HttpRequest;
import com.cisco.oss.foundation.http.HttpResponse;
import com.cisco.oss.foundation.http.ResponseCallback;
import com.cisco.oss.foundation.loadbalancer.InternalServerProxy;
import com.cisco.oss.foundation.loadbalancer.LoadBalancerStrategy;
import com.google.common.base.Joiner;
import org.apache.commons.configuration.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpOptions;
import org.apache.http.client.methods.HttpPatch;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.conn.ssl.SSLContexts;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyStore;
import java.util.Collection;
import java.util.Map;

/**
 * Created by Yair Ogen on 1/16/14.
 */
class ApacheHttpClient<S extends HttpRequest, R extends HttpResponse> extends AbstractHttpClient<S, R> {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApacheHttpClient.class);
    CloseableHttpAsyncClient httpAsyncClient = null;
    private CloseableHttpClient httpClient = null;
    private X509HostnameVerifier hostnameVerifier;

    public boolean isAutoCloseable() {
        return autoCloseable;
    }

    private boolean autoCloseable = true;


    ApacheHttpClient(String apiName, Configuration configuration, boolean enableLoadBalancing, X509HostnameVerifier hostnameVerifier) {
        super(apiName, configuration, enableLoadBalancing);
        this.hostnameVerifier = hostnameVerifier;
        configureClient();
    }


    ApacheHttpClient(String apiName, LoadBalancerStrategy.STRATEGY_TYPE strategyType, Configuration configuration, boolean enableLoadBalancing, X509HostnameVerifier hostnameVerifier) {
        super(apiName, strategyType, configuration, enableLoadBalancing);
        this.hostnameVerifier = hostnameVerifier;
        configureClient();
    }

    @Override
    protected void configureClient() {

        RequestConfig.Builder requestBuilder = RequestConfig.custom();
        requestBuilder = requestBuilder.setConnectTimeout(metadata.getConnectTimeout());
        requestBuilder = requestBuilder.setSocketTimeout(metadata.getReadTimeout());
        requestBuilder = requestBuilder.setStaleConnectionCheckEnabled(metadata.isStaleConnectionCheckEnabled());

        RequestConfig requestConfig = requestBuilder.build();

        boolean addSslSupport = StringUtils.isNotEmpty(metadata.getKeyStorePath()) && StringUtils.isNotEmpty(metadata.getKeyStorePassword());

        boolean addTrustSupport = StringUtils.isNotEmpty(metadata.getTrustStorePath()) && StringUtils.isNotEmpty(metadata.getTrustStorePassword());

        autoCloseable = metadata.isAutoCloseable();

        SSLContext sslContext = null;

        try {

            String keystoreType = "JKS";
            if (addSslSupport && addTrustSupport) {

                KeyStore keyStore = KeyStore.getInstance(keystoreType);
                keyStore.load(new FileInputStream(metadata.getKeyStorePath()), metadata.getKeyStorePassword().toCharArray());

                KeyStore trustStore = KeyStore.getInstance(keystoreType);
                trustStore.load(new FileInputStream(metadata.getTrustStorePath()), metadata.getTrustStorePassword().toCharArray());

                sslContext = SSLContexts.custom()
                        .useTLS()
                        .loadKeyMaterial(keyStore, metadata.getKeyStorePassword().toCharArray())
                        .loadTrustMaterial(trustStore)
                        .build();

            } else if (addSslSupport) {
                KeyStore keyStore = KeyStore.getInstance(keystoreType);
                keyStore.load(new FileInputStream(metadata.getKeyStorePath()), metadata.getKeyStorePassword().toCharArray());

                sslContext = SSLContexts.custom()
                        .useTLS()
                        .loadKeyMaterial(keyStore, metadata.getKeyStorePassword().toCharArray())
                        .build();

            } else if (addTrustSupport) {

                KeyStore trustStore = KeyStore.getInstance(keystoreType);
                trustStore.load(new FileInputStream(metadata.getTrustStorePath()), metadata.getTrustStorePassword().toCharArray());

                sslContext = SSLContexts.custom()
                        .useTLS()
                        .loadTrustMaterial(trustStore)
                        .build();

            }


        } catch (Exception e) {
            LOGGER.error("can't set TLS Support. Error is: {}", e, e);
        }

        HttpClientBuilder httpClientBuilder = HttpClientBuilder.create()
                .setMaxConnPerRoute(metadata.getMaxConnectionsPerAddress())
                .setMaxConnTotal(metadata.getMaxConnectionsTotal())
                .setDefaultRequestConfig(requestConfig)
                .setKeepAliveStrategy(new InfraConnectionKeepAliveStrategy(metadata.getIdleTimeout()))
                .setSslcontext(sslContext);

        if (hostnameVerifier != null) {
            httpClientBuilder.setHostnameVerifier(hostnameVerifier);
        }

        if (!followRedirects) {
            httpClientBuilder.disableRedirectHandling();
        }

        httpClient = httpClientBuilder.build();

        httpAsyncClient = HttpAsyncClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .setMaxConnPerRoute(metadata.getMaxConnectionsPerAddress())
                .setMaxConnTotal(metadata.getMaxConnectionsTotal())
                .setKeepAliveStrategy(new InfraConnectionKeepAliveStrategy(metadata.getIdleTimeout()))
                .setSSLContext(sslContext)
                .build();

        httpAsyncClient.start();

    }

    @Override
    public HttpResponse executeDirect(HttpRequest request) {

        HttpUriRequest httpUriRequest = null;

        Joiner joiner = Joiner.on(",").skipNulls();
        URI requestUri = buildUri(request, joiner);

        httpUriRequest = buildHttpUriRequest(request, joiner, requestUri);

        try {
//            LOGGER.info("sending request: {}", request.getUri());
            CloseableHttpResponse response = httpClient.execute(httpUriRequest);
            ApacheHttpResponse apacheHttpResponse = new ApacheHttpResponse(response, requestUri, autoCloseable);
//            LOGGER.info("got response status: {} for request: {}",apacheHttpResponse.getStatus(), apacheHttpResponse.getRequestedURI());
            return apacheHttpResponse;
        } catch (IOException e) {
            throw new ClientException(e.toString(), e);
        }
    }

    private HttpUriRequest buildHttpUriRequest(HttpRequest request, Joiner joiner, URI requestUri) {
        HttpUriRequest httpUriRequest;
        switch (request.getHttpMethod()) {
            case GET:
                httpUriRequest = new HttpGet(requestUri);
                break;
            case POST:
                httpUriRequest = new HttpPost(requestUri);
                break;
            case PUT:
                httpUriRequest = new HttpPut(requestUri);
                break;
            case DELETE:
                httpUriRequest = new HttpDelete(requestUri);
                break;
            case HEAD:
                httpUriRequest = new HttpHead(requestUri);
                break;
            case OPTIONS:
                httpUriRequest = new HttpOptions(requestUri);
                break;
            case PATCH:
                httpUriRequest = new HttpPatch(requestUri);
                break;
            default:
                throw new ClientException("You have to one of the REST verbs such as GET, POST etc.");
        }

        byte[] entity = request.getEntity();
        if (entity != null) {
            if (httpUriRequest instanceof HttpEntityEnclosingRequestBase) {
                HttpEntityEnclosingRequestBase httpEntityEnclosingRequestBase = (HttpEntityEnclosingRequestBase) httpUriRequest;
                httpEntityEnclosingRequestBase.setEntity(new ByteArrayEntity(entity, ContentType.create(request.getContentType())));
            } else {
                throw new ClientException("sending content for request type " + request.getHttpMethod() + " is not supported!");
            }
        }


        Map<String, Collection<String>> headers = request.getHeaders();
        for (Map.Entry<String, Collection<String>> stringCollectionEntry : headers.entrySet()) {
            String key = stringCollectionEntry.getKey();
            Collection<String> stringCollection = stringCollectionEntry.getValue();
            String value = joiner.join(stringCollection);
            httpUriRequest.setHeader(key, value);
        }
        return httpUriRequest;
    }

    private URI buildUri(HttpRequest request, Joiner joiner) {
        URI requestUri = request.getUri();

        Map<String, Collection<String>> queryParams = request.getQueryParams();
        if (queryParams != null && !queryParams.isEmpty()) {
            URIBuilder uriBuilder = new URIBuilder();
            for (Map.Entry<String, Collection<String>> stringCollectionEntry : queryParams.entrySet()) {
                String key = stringCollectionEntry.getKey();
                Collection<String> stringCollection = stringCollectionEntry.getValue();
                String value = joiner.join(stringCollection);
                uriBuilder.addParameter(key, value);
            }
            uriBuilder.setFragment(requestUri.getFragment());
            uriBuilder.setHost(requestUri.getHost());
            uriBuilder.setPath(requestUri.getPath());
            uriBuilder.setPort(requestUri.getPort());
            uriBuilder.setScheme(requestUri.getScheme());
            uriBuilder.setUserInfo(requestUri.getUserInfo());
            try {
                requestUri = uriBuilder.build();
            } catch (URISyntaxException e) {
                LOGGER.warn("could not update uri: {}", requestUri);
            }
        }
        return requestUri;
    }

    @Override
    public void execute(HttpRequest request, ResponseCallback responseCallback, LoadBalancerStrategy loadBalancerStrategy, String apiName) {

        InternalServerProxy serverProxy = loadBalancerStrategy.getServerProxy(request);
        Throwable lastCaugtException = null;


        if (serverProxy == null) {
            // server proxy will be null if the configuration was not
            // configured properly
            // or if all the servers are passivated.
            loadBalancerStrategy.handleNullserverProxy(apiName, lastCaugtException);
        }

        request = updateRequestUri((S)request, serverProxy);

//        final HttpRequest tempRequest = request;

        LOGGER.info("sending request: {}-{}", request.getHttpMethod(),request.getUri());
//        final FlowContext fc = FlowContextFactory.getFlowContext();
//        Request httpRequest = prepareRequest(request).onRequestQueued(new Request.QueuedListener() {
//            @Override
//            public void onQueued(Request jettyRequest) {
//                FlowContextFactory.addFlowContext(((S) tempRequest).getFlowContext());
//            }
//        }).onRequestBegin(new Request.BeginListener() {
//            @Override
//            public void onBegin(Request jettyRequest) {
//                FlowContextFactory.addFlowContext(((S) tempRequest).getFlowContext());
//            }
//        }).onRequestFailure(new Request.FailureListener() {
//            @Override
//            public void onFailure(Request jettyRequest, Throwable failure) {
//                FlowContextFactory.addFlowContext(((S) tempRequest).getFlowContext());
//            }
//        });

        HttpUriRequest httpUriRequest = null;

        Joiner joiner = Joiner.on(",").skipNulls();
        URI requestUri = buildUri(request, joiner);

        httpUriRequest = buildHttpUriRequest(request, joiner, requestUri);


        httpAsyncClient.execute(httpUriRequest, new FoundationFutureCallBack(this,request, responseCallback, serverProxy, loadBalancerStrategy, apiName));

    }

    private static class FoundationFutureCallBack implements FutureCallback<org.apache.http.HttpResponse> {
        private ResponseCallback responseCallback;
        private InternalServerProxy serverProxy;
        private LoadBalancerStrategy loadBalancerStrategy;
        private String apiName;
        private HttpRequest request;
        private ApacheHttpClient apacheHttpClient;


        private FoundationFutureCallBack(ApacheHttpClient apacheHttpClient, HttpRequest request, ResponseCallback<ApacheHttpResponse> responseCallback, InternalServerProxy serverProxy, LoadBalancerStrategy loadBalancerStrategy, String apiName) {
            this.responseCallback = responseCallback;
            this.apacheHttpClient = apacheHttpClient;
            this.serverProxy = serverProxy;
            this.loadBalancerStrategy = loadBalancerStrategy;
            this.apiName = apiName;
            this.request = request;
        }

        @Override
        public void completed(org.apache.http.HttpResponse response) {

            serverProxy.setCurrentNumberOfAttempts(0);
            serverProxy.setFailedAttemptTimeStamp(0);

            ApacheHttpResponse apacheHttpResponse = new ApacheHttpResponse(response, request.getUri(), apacheHttpClient.isAutoCloseable());
            LOGGER.info("got response status: {} for request: {}",apacheHttpResponse.getStatus(), apacheHttpResponse.getRequestedURI());
            responseCallback.completed(apacheHttpResponse);
        }

        @Override
        public void failed(Exception ex) {

            try {
                loadBalancerStrategy.handleException(apiName, serverProxy, ex);
            } catch (Exception e) {
                LOGGER.error("Error running request {}. Error is: {}", request.getUri(), e);
                responseCallback.failed(e);
            }

            try {
                apacheHttpClient.execute(request, responseCallback, loadBalancerStrategy, apiName);
            } catch (Throwable e) {
//                result.getRequest().abort(e);
                responseCallback.failed(e);
            }
        }

        @Override
        public void cancelled() {
            responseCallback.cancelled();
        }
    }
}
