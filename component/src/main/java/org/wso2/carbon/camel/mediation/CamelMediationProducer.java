/*
 * Copyright (c) 2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.camel.mediation;

import org.apache.camel.AsyncCallback;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.impl.DefaultAsyncProducer;
import org.apache.camel.util.ObjectHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.camel.mediation.common.CamelMediationConstants;
import org.wso2.carbon.messaging.CarbonCallback;
import org.wso2.carbon.messaging.CarbonMessage;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

/**
 * The CamelMediation producer handle the request and response with the backend.
 */
@SuppressWarnings("unchecked")
public class CamelMediationProducer extends DefaultAsyncProducer {

    private static Logger log = LoggerFactory.getLogger(CamelMediationProducer.class);

    private CamelMediationEngine engine;
    private String host;
    private int port;
    private String uri;
    private CarbonCamelMessageUtil carbonCamelMessageUtil;

    public CamelMediationProducer(CamelMediationEndpoint endpoint, CamelMediationEngine engine) {
        super(endpoint);
        this.engine = engine;
        try {
            URL url = new URL(ObjectHelper.after(getEndpoint().getEndpointKey(), "://"));
            host = url.getHost();
            port = (url.getPort() == -1) ? 80 : url.getPort();
            uri = url.getPath();
            carbonCamelMessageUtil = endpoint.getCarbonCamelMessageUtil();
        } catch (MalformedURLException e) {
            log.error("Could not generate endpoint url for : " + getEndpoint().getEndpointKey());
        }
    }

    /**
     * send request to backend. when response in received callback done method will be invoked.
     *
     * @param exchange camel message exchange.
     * @param callback when the response is received from backend callback done method will be invoked.
     */
    public boolean process(Exchange exchange, AsyncCallback callback) {
        //change the header parameters according to the routed endpoint url
        carbonCamelMessageUtil.setCarbonHeadersToBackendRequest(exchange, host, port, uri);
        //This parameter is used to decide whether we need to continue processing in case of a failure (FO endpoint)
        boolean syncNeeded = true;
        try {
            syncNeeded = engine.getSender().send(exchange.getIn().getBody(CarbonMessage.class),
                                                 new NettyHttpBackEndCallback(exchange, callback));
        } catch (Exception exp) {
            //Set the exception to the exchange such that camel can decide on failover
            exchange.setException(exp);
        }
        return syncNeeded;
    }

    @Override
    public Endpoint getEndpoint() {
        return super.getEndpoint();
    }

    private class NettyHttpBackEndCallback implements CarbonCallback {
        private final Exchange exchange;
        private final AsyncCallback callback;

        public NettyHttpBackEndCallback(Exchange exchange, AsyncCallback callback) {
            this.exchange = exchange;
            this.callback = callback;
        }

        /**
         * Invoked when the backend response arrived.
         *
         * @param responseCmsg response carbon message.
         */
        @Override
        public void done(CarbonMessage responseCmsg) {
            if (responseCmsg != null) {
                Map<String, Object> transportHeaders =
                        (Map<String, Object>) responseCmsg.getProperty(CamelMediationConstants.TRANSPORT_HEADERS);
                if (transportHeaders != null) {
                    transportHeaders.put(Exchange.HTTP_RESPONSE_CODE,
                                         responseCmsg.getProperty(CamelMediationConstants.HTTP_STATUS_CODE));
                    CarbonMessage request = exchange.getIn().getBody(CarbonMessage.class);
                    responseCmsg.setProperty(CamelMediationConstants.SRC_HNDLR,
                                             request.getProperty(CamelMediationConstants.SRC_HNDLR));
                    responseCmsg.setProperty(CamelMediationConstants.DISRUPTOR,
                                             request.getProperty(CamelMediationConstants.DISRUPTOR));
                    responseCmsg.setProperty(CamelMediationConstants.CHNL_HNDLR_CTX,
                                             request.getProperty(CamelMediationConstants.CHNL_HNDLR_CTX));
                    Message msg = null;
                    try {
                        msg = carbonCamelMessageUtil.createCamelMessage(responseCmsg, exchange);
                        exchange.setOut(msg);
                        carbonCamelMessageUtil.setCamelHeadersToBackendResponse(exchange, transportHeaders);
                    } catch (Exception e) {
                        log.error("Error occurred during the response camel message creation", e);
                    }
                } else {
                    log.warn("Backend response : Received empty headers in carbon message...");
                }
            } else {
                log.warn("Backend response not received for request...");
            }
            callback.done(false);
        }
    }
}
