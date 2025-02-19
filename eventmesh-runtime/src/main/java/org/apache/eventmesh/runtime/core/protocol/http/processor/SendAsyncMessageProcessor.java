/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.runtime.core.protocol.http.processor;

import org.apache.eventmesh.api.SendCallback;
import org.apache.eventmesh.api.SendResult;
import org.apache.eventmesh.api.exception.OnExceptionContext;
import org.apache.eventmesh.common.protocol.ProtocolTransportObject;
import org.apache.eventmesh.common.protocol.http.HttpCommand;
import org.apache.eventmesh.common.protocol.http.body.message.SendMessageRequestBody;
import org.apache.eventmesh.common.protocol.http.body.message.SendMessageResponseBody;
import org.apache.eventmesh.common.protocol.http.common.EventMeshRetCode;
import org.apache.eventmesh.common.protocol.http.common.ProtocolKey;
import org.apache.eventmesh.common.protocol.http.common.RequestCode;
import org.apache.eventmesh.common.protocol.http.header.message.SendMessageRequestHeader;
import org.apache.eventmesh.common.protocol.http.header.message.SendMessageResponseHeader;
import org.apache.eventmesh.common.utils.IPUtils;
import org.apache.eventmesh.metrics.api.model.HttpSummaryMetrics;
import org.apache.eventmesh.protocol.api.ProtocolAdaptor;
import org.apache.eventmesh.protocol.api.ProtocolPluginFactory;
import org.apache.eventmesh.runtime.acl.Acl;
import org.apache.eventmesh.runtime.boot.EventMeshHTTPServer;
import org.apache.eventmesh.runtime.configuration.EventMeshHTTPConfiguration;
import org.apache.eventmesh.runtime.constants.EventMeshConstants;
import org.apache.eventmesh.runtime.core.protocol.http.async.AsyncContext;
import org.apache.eventmesh.runtime.core.protocol.http.async.CompleteHandler;
import org.apache.eventmesh.runtime.core.protocol.http.processor.inf.HttpRequestProcessor;
import org.apache.eventmesh.runtime.core.protocol.http.producer.EventMeshProducer;
import org.apache.eventmesh.runtime.core.protocol.http.producer.SendMessageContext;
import org.apache.eventmesh.runtime.trace.TraceUtils;
import org.apache.eventmesh.runtime.util.EventMeshUtil;
import org.apache.eventmesh.runtime.util.RemotingHelper;
import org.apache.eventmesh.trace.api.common.EventMeshTraceConstants;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.netty.channel.ChannelHandlerContext;
import io.opentelemetry.api.trace.Span;


public class SendAsyncMessageProcessor implements HttpRequestProcessor {

    private static final Logger MESSAGE_LOGGER = LoggerFactory.getLogger(EventMeshConstants.MESSAGE);

    private static final Logger HTTP_LOGGER = LoggerFactory.getLogger(EventMeshConstants.PROTOCOL_HTTP);

    private static final Logger CMD_LOGGER = LoggerFactory.getLogger(EventMeshConstants.CMD);

    private static final Logger ACL_LOGGER = LoggerFactory.getLogger(EventMeshConstants.ACL);

    private final EventMeshHTTPServer eventMeshHTTPServer;

    private final Acl acl;

    public SendAsyncMessageProcessor(final EventMeshHTTPServer eventMeshHTTPServer) {
        this.eventMeshHTTPServer = eventMeshHTTPServer;
        this.acl = eventMeshHTTPServer.getAcl();
    }

    @Override
    public void processRequest(ChannelHandlerContext ctx, AsyncContext<HttpCommand> asyncContext) throws Exception {

        HttpCommand responseEventMeshCommand;
        String localAddress = IPUtils.getLocalAddress();
        HttpCommand request = asyncContext.getRequest();
        String remoteAddr = RemotingHelper.parseChannelRemoteAddr(ctx.channel());
        CMD_LOGGER.info("cmd={}|{}|client2eventMesh|from={}|to={}", RequestCode.get(
                Integer.valueOf(request.getRequestCode())),
            EventMeshConstants.PROTOCOL_HTTP,
                remoteAddr, localAddress);

        SendMessageRequestHeader sendMessageRequestHeader = (SendMessageRequestHeader) request.getHeader();

        EventMeshHTTPConfiguration eventMeshHttpConfiguration = eventMeshHTTPServer.getEventMeshHttpConfiguration();
        SendMessageResponseHeader sendMessageResponseHeader =
            SendMessageResponseHeader.buildHeader(Integer.valueOf(request.getRequestCode()),
                eventMeshHttpConfiguration.getEventMeshCluster(),
                localAddress, eventMeshHttpConfiguration.getEventMeshEnv(),
                eventMeshHttpConfiguration.getEventMeshIDC());

        String protocolType = sendMessageRequestHeader.getProtocolType();
        String protocolVersion = sendMessageRequestHeader.getProtocolVersion();
        ProtocolAdaptor<ProtocolTransportObject> httpCommandProtocolAdaptor =
            ProtocolPluginFactory.getProtocolAdaptor(protocolType);
        CloudEvent event = httpCommandProtocolAdaptor.toCloudEvent(request);

        Span span = TraceUtils.prepareServerSpan(EventMeshUtil.getCloudEventExtensionMap(protocolVersion, event),
            EventMeshTraceConstants.TRACE_UPSTREAM_EVENTMESH_SERVER_SPAN, true);

        //validate event
        if (!ObjectUtils.allNotNull(event, event.getSource(), event.getSpecVersion())
            || StringUtils.isAnyBlank(event.getId(), event.getType(), event.getSubject())) {
            responseEventMeshCommand = request.createHttpCommandResponse(
                sendMessageResponseHeader,
                SendMessageResponseBody.buildBody(EventMeshRetCode.EVENTMESH_PROTOCOL_HEADER_ERR.getRetCode(),
                    EventMeshRetCode.EVENTMESH_PROTOCOL_HEADER_ERR.getErrMsg()));
            asyncContext.onComplete(responseEventMeshCommand);

            spanWithException(event, protocolVersion, EventMeshRetCode.EVENTMESH_PROTOCOL_HEADER_ERR);
            return;
        }

        String idc = Objects.requireNonNull(event.getExtension(ProtocolKey.ClientInstanceKey.IDC)).toString();
        String pid = Objects.requireNonNull(event.getExtension(ProtocolKey.ClientInstanceKey.PID)).toString();
        String sys = Objects.requireNonNull(event.getExtension(ProtocolKey.ClientInstanceKey.SYS)).toString();

        //validate event-extension
        if (StringUtils.isAnyBlank(idc, pid, sys)
            || !StringUtils.isNumeric(pid)) {
            responseEventMeshCommand = request.createHttpCommandResponse(
                sendMessageResponseHeader,
                SendMessageResponseBody.buildBody(EventMeshRetCode.EVENTMESH_PROTOCOL_HEADER_ERR.getRetCode(),
                    EventMeshRetCode.EVENTMESH_PROTOCOL_HEADER_ERR.getErrMsg()));
            asyncContext.onComplete(responseEventMeshCommand);

            spanWithException(event, protocolVersion, EventMeshRetCode.EVENTMESH_PROTOCOL_HEADER_ERR);
            return;
        }

        String bizNo = Objects.requireNonNull(event.getExtension(SendMessageRequestBody.BIZSEQNO)).toString();
        String uniqueId = Objects.requireNonNull(event.getExtension(SendMessageRequestBody.UNIQUEID)).toString();
        String producerGroup = Objects.requireNonNull(event.getExtension(SendMessageRequestBody.PRODUCERGROUP)).toString();
        String topic = event.getSubject();

        //validate body
        if (StringUtils.isAnyBlank(bizNo, uniqueId, producerGroup, topic)
            || event.getData() == null) {
            responseEventMeshCommand = request.createHttpCommandResponse(
                sendMessageResponseHeader,
                SendMessageResponseBody.buildBody(EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_ERR.getRetCode(),
                    EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_ERR.getErrMsg()));
            asyncContext.onComplete(responseEventMeshCommand);

            spanWithException(event, protocolVersion, EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_ERR);
            return;
        }

        //do acl check
        if (eventMeshHttpConfiguration.isEventMeshServerSecurityEnable()) {
            String user = Objects.requireNonNull(event.getExtension(ProtocolKey.ClientInstanceKey.USERNAME)).toString();
            String pass = Objects.requireNonNull(event.getExtension(ProtocolKey.ClientInstanceKey.PASSWD)).toString();
            String subsystem = Objects.requireNonNull(event.getExtension(ProtocolKey.ClientInstanceKey.SYS)).toString();
            int requestCode = Integer.parseInt(request.getRequestCode());
            try {
                this.acl.doAclCheckInHttpSend(remoteAddr, user, pass, subsystem, topic, requestCode);
            } catch (Exception e) {
                responseEventMeshCommand = request.createHttpCommandResponse(
                    sendMessageResponseHeader,
                    SendMessageResponseBody.buildBody(EventMeshRetCode.EVENTMESH_ACL_ERR.getRetCode(), e.getMessage()));
                asyncContext.onComplete(responseEventMeshCommand);
                ACL_LOGGER.warn("CLIENT HAS NO PERMISSION,SendAsyncMessageProcessor send failed", e);

                spanWithException(event, protocolVersion, EventMeshRetCode.EVENTMESH_ACL_ERR);
                return;
            }
        }

        final HttpSummaryMetrics summaryMetrics = eventMeshHTTPServer.getMetrics().getSummaryMetrics();
        // control flow rate limit
        if (!eventMeshHTTPServer.getMsgRateLimiter()
            .tryAcquire(EventMeshConstants.DEFAULT_FASTFAIL_TIMEOUT_IN_MILLISECONDS, TimeUnit.MILLISECONDS)) {
            responseEventMeshCommand = request.createHttpCommandResponse(
                sendMessageResponseHeader,
                SendMessageResponseBody.buildBody(EventMeshRetCode.EVENTMESH_HTTP_MES_SEND_OVER_LIMIT_ERR.getRetCode(),
                    EventMeshRetCode.EVENTMESH_HTTP_MES_SEND_OVER_LIMIT_ERR.getErrMsg()));
            summaryMetrics.recordHTTPDiscard();
            asyncContext.onComplete(responseEventMeshCommand);

            spanWithException(event, protocolVersion, EventMeshRetCode.EVENTMESH_HTTP_MES_SEND_OVER_LIMIT_ERR);
            return;
        }

        EventMeshProducer eventMeshProducer = eventMeshHTTPServer.getProducerManager().getEventMeshProducer(producerGroup);

        if (!eventMeshProducer.isStarted()) {
            responseEventMeshCommand = request.createHttpCommandResponse(
                sendMessageResponseHeader,
                SendMessageResponseBody.buildBody(EventMeshRetCode.EVENTMESH_GROUP_PRODUCER_STOPED_ERR.getRetCode(),
                    EventMeshRetCode.EVENTMESH_GROUP_PRODUCER_STOPED_ERR.getErrMsg()));
            asyncContext.onComplete(responseEventMeshCommand);

            spanWithException(event, protocolVersion, EventMeshRetCode.EVENTMESH_GROUP_PRODUCER_STOPED_ERR);

            return;
        }

        String ttl = String.valueOf(EventMeshConstants.DEFAULT_MSG_TTL_MILLS);
        String ttlExt = Objects.requireNonNull(event.getExtension(SendMessageRequestBody.TTL)).toString();
        if (StringUtils.isBlank(ttlExt) && !StringUtils.isNumeric(ttlExt)) {
            event = CloudEventBuilder.from(event).withExtension(SendMessageRequestBody.TTL, ttl).build();
        }

        String content = event.getData() == null ? "" : new String(Objects.requireNonNull(event.getData()).toBytes(), StandardCharsets.UTF_8);
        if (content.length() > eventMeshHttpConfiguration.getEventMeshEventSize()) {
            HTTP_LOGGER.error("Event size exceeds the limit: {}",
                eventMeshHttpConfiguration.getEventMeshEventSize());

            responseEventMeshCommand = request.createHttpCommandResponse(
                sendMessageResponseHeader,
                SendMessageResponseBody.buildBody(EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_SIZE_ERR.getRetCode(),
                    "Event size exceeds the limit: " + eventMeshHttpConfiguration.getEventMeshEventSize()));
            asyncContext.onComplete(responseEventMeshCommand);

            spanWithException(event, protocolVersion, EventMeshRetCode.EVENTMESH_PROTOCOL_BODY_SIZE_ERR);
            return;
        }

        try {
            event = CloudEventBuilder.from(event)
                .withExtension(EventMeshConstants.MSG_TYPE, EventMeshConstants.PERSISTENT)
                .withExtension(EventMeshConstants.REQ_C2EVENTMESH_TIMESTAMP, request.reqTime)
                .withExtension(EventMeshConstants.REQ_SEND_EVENTMESH_IP,
                    eventMeshHttpConfiguration.getEventMeshServerIp())
                .build();

            if (MESSAGE_LOGGER.isDebugEnabled()) {
                MESSAGE_LOGGER.debug("msg2MQMsg suc, bizSeqNo={}, topic={}", bizNo, topic);
            }
        } catch (Exception e) {
            MESSAGE_LOGGER.error("msg2MQMsg err, bizSeqNo={}, topic={}", bizNo, topic, e);
            responseEventMeshCommand = request.createHttpCommandResponse(
                sendMessageResponseHeader,
                SendMessageResponseBody.buildBody(EventMeshRetCode.EVENTMESH_PACKAGE_MSG_ERR.getRetCode(),
                    EventMeshRetCode.EVENTMESH_PACKAGE_MSG_ERR.getErrMsg() + EventMeshUtil.stackTrace(e, 2)));
            asyncContext.onComplete(responseEventMeshCommand);

            spanWithException(event, protocolVersion, EventMeshRetCode.EVENTMESH_PACKAGE_MSG_ERR);
            return;
        }

        final SendMessageContext sendMessageContext = new SendMessageContext(bizNo, event, eventMeshProducer,
            eventMeshHTTPServer);
        summaryMetrics.recordSendMsg();

        long startTime = System.currentTimeMillis();

        final CompleteHandler<HttpCommand> handler = httpCommand -> {
            try {
                if (HTTP_LOGGER.isDebugEnabled()) {
                    HTTP_LOGGER.debug("{}", httpCommand);
                }
                eventMeshHTTPServer.sendResponse(ctx, httpCommand.httpResponse());

                summaryMetrics.recordHTTPReqResTimeCost(
                    System.currentTimeMillis() - request.getReqTime());
            } catch (Exception ex) {
                //ignore
            }
        };

        try {
            event = CloudEventBuilder.from(sendMessageContext.getEvent())
                .withExtension(EventMeshConstants.REQ_EVENTMESH2MQ_TIMESTAMP, String.valueOf(System.currentTimeMillis()))
                .build();
            sendMessageContext.setEvent(event);

            Span clientSpan = TraceUtils.prepareClientSpan(EventMeshUtil.getCloudEventExtensionMap(protocolVersion, event),
                EventMeshTraceConstants.TRACE_UPSTREAM_EVENTMESH_CLIENT_SPAN, false);
            try {
                eventMeshProducer.send(sendMessageContext, new SendCallback() {

                    @Override
                    public void onSuccess(SendResult sendResult) {
                        HttpCommand succ = request.createHttpCommandResponse(
                            sendMessageResponseHeader,
                            SendMessageResponseBody.buildBody(EventMeshRetCode.SUCCESS.getRetCode(),
                                EventMeshRetCode.SUCCESS.getErrMsg() + sendResult.toString()));
                        asyncContext.onComplete(succ, handler);
                        long endTime = System.currentTimeMillis();
                        summaryMetrics.recordSendMsgCost(endTime - startTime);
                        MESSAGE_LOGGER.info("message|eventMesh2mq|REQ|ASYNC|send2MQCost={}ms|topic={}|bizSeqNo={}|uniqueId={}",
                            endTime - startTime, topic, bizNo, uniqueId);

                        TraceUtils.finishSpan(span, sendMessageContext.getEvent());
                    }

                    @Override
                    public void onException(OnExceptionContext context) {
                        HttpCommand err = request.createHttpCommandResponse(
                            sendMessageResponseHeader,
                            SendMessageResponseBody.buildBody(EventMeshRetCode.EVENTMESH_SEND_ASYNC_MSG_ERR.getRetCode(),
                                EventMeshRetCode.EVENTMESH_SEND_ASYNC_MSG_ERR.getErrMsg()
                                    + EventMeshUtil.stackTrace(context.getException(), 2)));
                        asyncContext.onComplete(err, handler);

                        eventMeshHTTPServer.getHttpRetryer().pushRetry(sendMessageContext.delay(10000));
                        long endTime = System.currentTimeMillis();
                        summaryMetrics.recordSendMsgFailed();
                        summaryMetrics.recordSendMsgCost(endTime - startTime);
                        MESSAGE_LOGGER.error("message|eventMesh2mq|REQ|ASYNC|send2MQCost={}ms|topic={}|bizSeqNo={}|uniqueId={}",
                            endTime - startTime, topic, bizNo, uniqueId, context.getException());

                        TraceUtils.finishSpanWithException(span,
                            EventMeshUtil.getCloudEventExtensionMap(protocolVersion, sendMessageContext.getEvent()),
                            EventMeshRetCode.EVENTMESH_SEND_ASYNC_MSG_ERR.getErrMsg(), context.getException());
                    }
                });
            } finally {
                TraceUtils.finishSpan(clientSpan, event);
            }


        } catch (Exception ex) {
            HttpCommand err = request.createHttpCommandResponse(
                sendMessageResponseHeader,
                SendMessageResponseBody.buildBody(EventMeshRetCode.EVENTMESH_SEND_ASYNC_MSG_ERR.getRetCode(),
                    EventMeshRetCode.EVENTMESH_SEND_ASYNC_MSG_ERR.getErrMsg()
                        + EventMeshUtil.stackTrace(ex, 2)));
            asyncContext.onComplete(err);

            spanWithException(event, protocolVersion, EventMeshRetCode.EVENTMESH_SEND_ASYNC_MSG_ERR);

            eventMeshHTTPServer.getHttpRetryer().pushRetry(sendMessageContext.delay(10000));
            long endTime = System.currentTimeMillis();
            MESSAGE_LOGGER.error("message|eventMesh2mq|REQ|ASYNC|send2MQCost={}ms|topic={}|bizSeqNo={}|uniqueId={}",
                endTime - startTime, topic, bizNo, uniqueId, ex);
            summaryMetrics.recordSendMsgFailed();
            summaryMetrics.recordSendMsgCost(endTime - startTime);
        }
    }

    private void spanWithException(CloudEvent event, String protocolVersion, EventMeshRetCode retCode) {
        Span excepSpan = TraceUtils.prepareServerSpan(EventMeshUtil.getCloudEventExtensionMap(protocolVersion, event),
                EventMeshTraceConstants.TRACE_UPSTREAM_EVENTMESH_SERVER_SPAN, false);
        TraceUtils.finishSpanWithException(excepSpan, EventMeshUtil.getCloudEventExtensionMap(protocolVersion, event),
                retCode.getErrMsg(), null);
    }

    @Override
    public boolean rejectRequest() {
        return false;
    }

}
